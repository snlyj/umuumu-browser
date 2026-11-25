package com.yuka.cutebrowser.dns

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 代理用来把域名变成 IP 的接口（测试里可以换成假的） */
fun interface HostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress>
}

/** 发一个 DNS 报文、拿回答报文（阻塞） */
fun interface DohTransport {
    @Throws(IOException::class)
    fun exchange(query: ByteArray): ByteArray
}

/**
 * RFC 8484 客户端：GET /dns-query?dns=<base64url>，HTTP/2 多路复用（OkHttp），连接复用。
 * 连 DoH 服务器用写死的 bootstrap IP；证书照常按域名校验。
 */
class OkHttpDohTransport(private val provider: DohProvider) : DohTransport {
    private val base = provider.url.toHttpUrl()

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(provider.host, true) && provider.bootstrap.isNotEmpty()) provider.bootstrapAddresses()
                else Dns.SYSTEM.lookup(hostname) // 只有"自定义 + 域名"才会走到这里：用系统 DNS 查一次 DoH 服务器自己的域名
        })
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun exchange(query: ByteArray): ByteArray {
        val url = base.newBuilder().addQueryParameter("dns", query.toByteString().base64Url().trimEnd('=')).build()
        val req = Request.Builder().url(url).header("Accept", "application/dns-message").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("DoH HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("DoH empty body")
            if (body.contentLength() > MAX_RESPONSE) throw IOException("DoH response too large")
            // 没有 Content-Length（HTTP/2 / 分块）时 contentLength 是 -1：不能直接 bytes()，否则恶意的自定义服务器能灌爆内存
            val src = body.source()
            src.request(MAX_RESPONSE + 1L)
            if (src.buffer.size > MAX_RESPONSE) throw IOException("DoH response too large")
            return src.readByteArray()
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        const val MAX_RESPONSE = 65535
    }
}

/**
 * 用 DoH 解析域名：A 和 AAAA 并行查；缓存（TTL + 陈旧可用）；同一个名字同时只查一次。
 * 查不了（网络/服务器出错）时：严格模式直接失败；否则退回系统 DNS 并通知一下。
 * NXDOMAIN 是正常回答，不会退回系统 DNS。
 */
class DohResolver(
    val provider: DohProvider,
    @Volatile var strict: Boolean,
    private val transport: DohTransport = OkHttpDohTransport(provider),
    val cache: DnsCache = DnsCache(),
    private val systemLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val timeoutMs: Long = 5000,
    private val aaaaGraceMs: Long = 50,
) : HostResolver {

    interface Listener {
        fun onFallback(host: String, error: Throwable) {}
    }

    @Volatile var listener: Listener? = null

    // 统计（设置页显示）
    val lookups = AtomicLong()
    val cacheHits = AtomicLong()
    val dohQueries = AtomicLong()
    val dohNanos = AtomicLong()
    val fallbacks = AtomicLong()
    val failures = AtomicLong()

    private sealed class R {
        class Addrs(val list: List<InetAddress>) : R()
        object NxDomain : R()
        class Failure(val error: Throwable) : R()
    }

    private val inflight = ConcurrentHashMap<String, CompletableFuture<R>>()
    private val threadN = AtomicInteger()
    private val executor: ExecutorService = ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(null, r, "umu-doh-${threadN.incrementAndGet()}", 512L * 1024).apply { isDaemon = true } // 栈 512 KB：TLS 握手（OkHttp）比普通线程要得多
    }.apply { rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy() }

    override fun resolve(host: String): List<InetAddress> {
        val h = host.trim().trim('[', ']').trimEnd('.').lowercase()
        IpLiteral.toAddress(h)?.let { return listOf(it) }
        if (h == "localhost" || h.endsWith(".localhost")) return listOf(InetAddress.getByName("127.0.0.1"))
        val name = runCatching { DnsMessage.normalizeName(h) }.getOrElse { throw UnknownHostException(host) }
        lookups.incrementAndGet()

        val fa = lookup(name, DnsMessage.TYPE_A)
        val f6 = lookup(name, DnsMessage.TYPE_AAAA)
        val start = System.nanoTime()
        val ra = await(fa, timeoutMs)
        val left = timeoutMs - (System.nanoTime() - start) / 1_000_000
        // 有 IPv4 结果了，IPv6 只再等一小会儿（RFC 8305 的 Resolution Delay），不让慢的 AAAA 拖住页面
        val r6 = if (ra is R.Addrs && ra.list.isNotEmpty()) (if (f6.isDone) f6.get() else await(f6, aaaaGraceMs)) else await(f6, left.coerceAtLeast(1))

        val v4 = (ra as? R.Addrs)?.list.orEmpty()
        val v6 = (r6 as? R.Addrs)?.list.orEmpty()
        if (v4.isNotEmpty() || v6.isNotEmpty()) return interleave(v6, v4)
        if (ra is R.NxDomain || r6 is R.NxDomain || (ra is R.Addrs && r6 is R.Addrs)) {
            throw UnknownHostException(host) // NXDOMAIN / 两种记录都没有：确实不存在
        }
        val err = (ra as? R.Failure)?.error ?: (r6 as? R.Failure)?.error ?: IOException("DoH failed")
        if (strict) {
            failures.incrementAndGet()
            throw UnknownHostException("$host (DoH: ${err.message})").apply { initCause(err) }
        }
        fallbacks.incrementAndGet()
        listener?.onFallback(host, err)
        return systemLookup(name)
    }

    private fun await(f: CompletableFuture<R>, ms: Long): R = try {
        f.get(ms, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        R.Failure(IOException("DoH timeout"))
    } catch (e: Exception) {
        R.Failure(e.cause ?: e)
    }

    private fun lookup(name: String, type: Int): CompletableFuture<R> {
        when (val c = cache.get(name, type)) {
            is DnsCache.Lookup.Fresh -> { cacheHits.incrementAndGet(); return CompletableFuture.completedFuture(c.entry.toR()) }
            is DnsCache.Lookup.Stale -> {
                cacheHits.incrementAndGet()
                query(name, type) // 后台刷新，这次先用旧的
                return CompletableFuture.completedFuture(c.entry.toR())
            }
            DnsCache.Lookup.Miss -> return query(name, type)
        }
    }

    private fun DnsCache.Entry.toR(): R = if (nxdomain) R.NxDomain else R.Addrs(addresses)

    /** 同一个 (名字, 类型) 同时只发一个请求 */
    private fun query(name: String, type: Int): CompletableFuture<R> {
        val key = cache.key(name, type)
        inflight[key]?.let { return it }
        val f = CompletableFuture<R>()
        inflight.putIfAbsent(key, f)?.let { return it }
        executor.execute {
            val r = try { doQuery(name, type) } catch (t: Throwable) { R.Failure(t) }
            inflight.remove(key, f)
            f.complete(r)
        }
        return f
    }

    private fun doQuery(name: String, type: Int): R {
        val t0 = System.nanoTime()
        val resp = DnsMessage.parse(transport.exchange(DnsMessage.buildQuery(name, type)))
        dohQueries.incrementAndGet()
        dohNanos.addAndGet(System.nanoTime() - t0)
        if (resp.questionName != null && (resp.questionName != name || resp.questionType != type)) {
            throw DnsMessage.FormatException("answer for wrong question")
        }
        return when (resp.rcode) {
            DnsMessage.RCODE_OK -> {
                val list = resp.addresses(type).map { InetAddress.getByAddress(name, it.address) }
                cache.put(name, type, list, if (list.isEmpty()) resp.negativeTtl() else resp.minTtl(type))
                R.Addrs(list)
            }
            DnsMessage.RCODE_NXDOMAIN -> { cache.putNxdomain(name, type, resp.negativeTtl()); R.NxDomain }
            else -> R.Failure(IOException("DNS rcode ${resp.rcode}"))
        }
    }

    /** 预热：建好到 DoH 服务器的 TLS/HTTP2 连接，并把常用网站放进缓存 */
    fun prewarm(hosts: List<String>) {
        executor.execute {
            runCatching { lookup(provider.host.takeIf { IpLiteral.parse(it) == null } ?: "example.com", DnsMessage.TYPE_A).get(5, TimeUnit.SECONDS) }
            hosts.forEach { h -> runCatching { lookup(h, DnsMessage.TYPE_A); lookup(h, DnsMessage.TYPE_AAAA) } }
        }
    }

    fun close() {
        executor.shutdownNow()
        (transport as? OkHttpDohTransport)?.close()
    }

    companion object {
        /** IPv6、IPv4 交替排列（RFC 8305），连接时谁先通用谁 */
        fun interleave(v6: List<InetAddress>, v4: List<InetAddress>): List<InetAddress> {
            val out = ArrayList<InetAddress>(v6.size + v4.size)
            val n = maxOf(v6.size, v4.size)
            for (i in 0 until n) { v6.getOrNull(i)?.let(out::add); v4.getOrNull(i)?.let(out::add) }
            return out
        }

        fun isV6(a: InetAddress) = a is Inet6Address
    }
}
