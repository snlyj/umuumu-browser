package com.yuka.cutebrowser.dns

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * App 内的本地 HTTP 代理（只监听 127.0.0.1，随机端口），WebView 通过 ProxyController 走它。
 * 它只做一件事：用 [resolver]（DoH）把域名换成 IP，再把字节原样转发——
 * HTTPS 用 CONNECT 隧道（TLS 端到端，代理看不到内容，证书仍由 WebView 校验）；
 * 明文 HTTP 把绝对 URL 改回路径，并加 Connection: close（避免一条连接被复用去别的网站）。
 *
 * 线程模型：每条隧道两个方向各一个线程（小栈 128 KB），阻塞读写 + TCP_NODELAY。
 * Chromium 对一个代理最多开 32 条连接，所以最多约 64 个转发线程，开销很小，又没有 NIO 的复杂度。
 * 连接上游用 happy eyeballs（RFC 8305）：IPv6/IPv4 交替，每 250 ms 起一个新尝试，谁先连上用谁。
 */
class LocalProxy(
    @Volatile var resolver: HostResolver,
    private val connectTimeoutMs: Long = 10_000,
    private val attemptDelayMs: Long = 250,
) {
    private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    val tunnels = AtomicInteger()
    val totalConnections = AtomicLong()
    val errors = AtomicLong()

    /** IPv6 最近不通（被 IPv4 抢先 / 失败）→ 一段时间内 IPv4 先试，省掉每条连接 250 ms 的等待 */
    @Volatile private var preferV4Until = 0L

    private val threadN = AtomicInteger()
    private val pool: ExecutorService = ThreadPoolExecutor(0, 256, 20, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(null, r, "umu-proxy-${threadN.incrementAndGet()}", 128 * 1024).apply { isDaemon = true }
    }

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return port }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128)
        server = ss
        port = ss.localPort
        Thread(null, { acceptLoop(ss) }, "umu-proxy-accept", 64 * 1024).apply { isDaemon = true; start() }
        return port
    }

    /** 停止接新连接；已经在跑的隧道自然结束（不打断正在加载的页面） */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (e: IOException) { if (ss.isClosed) return else continue }
            totalConnections.incrementAndGet()
            try {
                pool.execute { handle(c) }
            } catch (e: Exception) {
                runCatching { c.close() }
            }
        }
    }

    private class Head(val bytes: ByteArray, val headerEnd: Int, val total: Int)

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000 // 只限制"发请求头"这一步
            val head = readHead(client.getInputStream()) ?: return client.close()
            val text = String(head.bytes, 0, head.headerEnd, Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            val parts = lines[0].split(' ')
            if (parts.size < 3) return reply(client, 400, "Bad Request")
            val method = parts[0].uppercase()
            val out = client.getOutputStream()

            if (method == "CONNECT") {
                val (host, port) = splitHostPort(parts[1], 443) ?: return reply(client, 400, "Bad Request")
                if (isSelf(host, port)) return reply(client, 403, "Forbidden")
                upstream = open(host, port) ?: return reply(client, 502, "Bad Gateway")
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                if (head.total > head.headerEnd) upstream.getOutputStream().write(head.bytes, head.headerEnd, head.total - head.headerEnd)
            } else {
                // 明文 HTTP：GET http://host:port/path HTTP/1.1
                val target = parts[1]
                if (!target.startsWith("http://", true)) return reply(client, 400, "Bad Request")
                val rest = target.substring(7)
                val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
                val (host, port) = splitHostPort(rest.substring(0, slash), 80) ?: return reply(client, 400, "Bad Request")
                if (isSelf(host, port)) return reply(client, 403, "Forbidden")
                val path = rest.substring(slash).ifEmpty { "/" }
                val sb = StringBuilder(head.headerEnd + 32)
                sb.append(method).append(' ').append(path).append(' ').append(parts[2]).append("\r\n")
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    if (l.isEmpty()) continue
                    val name = l.substringBefore(':').trim().lowercase()
                    if (name == "proxy-connection" || name == "proxy-authorization" || name == "connection" || name == "keep-alive") continue
                    if (name == "accept-language") {
                        // 伪造语言时：明文请求的 Accept-Language 换成该网站该用的（https 隧道里改不了）
                        val al = runCatching { DohManager.acceptLanguageFor?.invoke(host) }.getOrNull()
                        if (al != null) { sb.append("Accept-Language: ").append(al).append("\r\n"); continue }
                    }
                    sb.append(l).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                upstream = open(host, port) ?: return reply(client, 502, "Bad Gateway")
                val up = upstream.getOutputStream()
                up.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (head.total > head.headerEnd) up.write(head.bytes, head.headerEnd, head.total - head.headerEnd)
            }
            client.soTimeout = 0
            relay(client, upstream)
        } catch (e: Exception) {
            errors.incrementAndGet()
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** 读到 \r\n\r\n 为止（最多 32 KB）；返回缓冲区和头部结束位置（之后可能已经带了一点数据） */
    private fun readHead(input: InputStream): Head? {
        val buf = ByteArray(32 * 1024)
        var n = 0
        while (n < buf.size) {
            val r = try { input.read(buf, n, buf.size - n) } catch (e: SocketTimeoutException) { return null }
            if (r < 0) return null
            val from = maxOf(0, n - 3)
            n += r
            for (i in from..n - 4) {
                if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() && buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()) {
                    return Head(buf, i + 4, n)
                }
            }
        }
        return null
    }

    private fun reply(client: Socket, code: Int, msg: String) {
        errors.incrementAndGet()
        runCatching {
            client.getOutputStream().write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()
        }
        runCatching { client.close() }
    }

    private fun isSelf(host: String, port: Int): Boolean {
        if (port != this.port) return false
        val h = host.lowercase()
        return h == "localhost" || h.startsWith("127.") || h == "::1" || h == "0.0.0.0"
    }

    /** 解析 + 连接；失败返回 null（让 WebView 显示代理/隧道错误页） */
    private fun open(host: String, port: Int): Socket? {
        val addrs = try { resolver.resolve(host) } catch (e: UnknownHostException) { return null } catch (e: Exception) { return null }
        if (addrs.isEmpty()) return null
        return connectHappyEyeballs(order(addrs), port)?.also { it.tcpNoDelay = true }
    }

    private fun order(addrs: List<InetAddress>): List<InetAddress> {
        if (System.nanoTime() >= preferV4Until) return addrs
        val v4 = addrs.filterIsInstance<Inet4Address>()
        val v6 = addrs.filterIsInstance<Inet6Address>()
        return DohResolver.interleave(v4, v6) // 这里第一个参数先排：IPv4 在前
    }

    /**
     * RFC 8305：按顺序发起非阻塞连接，前一个 250 ms 没连上（或已失败）就起下一个；第一个成功的胜出，其余关掉。
     * 返回阻塞模式的 Socket。
     */
    fun connectHappyEyeballs(addrs: List<InetAddress>, port: Int): Socket? {
        val selector = Selector.open()
        val pending = ArrayList<SocketChannel>()
        var next = 0
        val deadline = System.nanoTime() + connectTimeoutMs * 1_000_000
        var nextStartAt = 0L
        var winner: SocketChannel? = null
        try {
            fun startNext(): Boolean {
                while (next < addrs.size) {
                    val a = addrs[next++]
                    val ch = SocketChannel.open()
                    try {
                        ch.configureBlocking(false)
                        if (ch.connect(InetSocketAddress(a, port))) { winner = ch; return true }
                        ch.register(selector, SelectionKey.OP_CONNECT, a)
                        pending += ch
                        nextStartAt = System.nanoTime() + attemptDelayMs * 1_000_000
                        return true
                    } catch (e: IOException) {
                        runCatching { ch.close() } // 比如没有 IPv6 网络：马上试下一个
                        if (a is Inet6Address) noteV6Broken()
                    }
                }
                return false
            }
            startNext()
            while (winner == null) {
                val now = System.nanoTime()
                if (now >= deadline) return null
                if (pending.isEmpty() && next >= addrs.size) return null
                if (pending.isEmpty() || (next < addrs.size && now >= nextStartAt)) { startNext(); if (winner != null) break; continue }
                val waitUntil = if (next < addrs.size) minOf(nextStartAt, deadline) else deadline
                val waitMs = ((waitUntil - now) / 1_000_000).coerceAtLeast(1)
                selector.select(waitMs)
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next(); it.remove()
                    val ch = k.channel() as SocketChannel
                    val ok = try { ch.finishConnect() } catch (e: IOException) { false }
                    if (ok) {
                        winner = ch
                        if (k.attachment() is Inet4Address && addrs.firstOrNull() is Inet6Address) noteV6Broken()
                        break
                    } else if (!ch.isConnectionPending) {
                        k.cancel(); pending -= ch; runCatching { ch.close() }
                        if (k.attachment() is Inet6Address) noteV6Broken()
                        nextStartAt = 0 // 失败了就立刻试下一个
                    }
                }
            }
            val w = winner!!
            pending.filter { it !== w }.forEach { runCatching { it.close() } }
            selector.close() // 注销所有 key，之后才能切回阻塞模式
            w.configureBlocking(true)
            return w.socket()
        } catch (e: IOException) {
            pending.forEach { runCatching { it.close() } }
            return null
        } finally {
            runCatching { selector.close() }
            if (winner == null) pending.forEach { runCatching { it.close() } }
        }
    }

    private fun noteV6Broken() { preferV4Until = System.nanoTime() + 10 * 60 * 1_000_000_000L }

    /** 双向转发。一个方向读到 EOF 就半关闭另一端的写；两个方向都结束才关 socket */
    private fun relay(client: Socket, upstream: Socket) {
        tunnels.incrementAndGet()
        val done = AtomicInteger(0)
        fun finish() {
            if (done.incrementAndGet() == 2) {
                runCatching { client.close() }; runCatching { upstream.close() }
                tunnels.decrementAndGet()
            }
        }
        try {
            pool.execute {
                pump(upstream.getInputStream(), client.getOutputStream(), client, upstream)
                runCatching { client.shutdownOutput() }
                finish()
            }
        } catch (e: Exception) {
            runCatching { client.close() }; runCatching { upstream.close() }
            tunnels.decrementAndGet()
            return
        }
        pump(client.getInputStream(), upstream.getOutputStream(), client, upstream)
        runCatching { upstream.shutdownOutput() }
        finish()
    }

    private fun pump(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) return
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: IOException) {
            // 一边出错（重置等）：两边都关掉，另一个方向的线程会随之结束
            runCatching { a.close() }; runCatching { b.close() }
        }
    }

    companion object {
        /** "host:port" / "[v6]:port" / "host" */
        fun splitHostPort(s: String, defPort: Int): Pair<String, Int>? {
            if (s.isEmpty()) return null
            val host: String
            val port: Int
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defPort
            } else {
                val c = s.lastIndexOf(':')
                if (c >= 0 && s.indexOf(':') == c) {
                    host = s.substring(0, c); port = s.substring(c + 1).toIntOrNull() ?: return null
                } else if (c >= 0) {
                    host = s; port = defPort // 不带括号的 IPv6
                } else { host = s; port = defPort }
            }
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }
    }
}
