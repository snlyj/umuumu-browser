package com.yuka.cutebrowser.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class LocalProxyTest {
    private val loop = InetAddress.getByName("127.0.0.1")
    private val upstream = ServerSocket(0, 50, loop)
    private val resolver = HostResolver { h ->
        when (h) {
            "upstream.test" -> listOf(loop)
            else -> throw UnknownHostException(h)
        }
    }
    private val proxy = LocalProxy(resolver)
    private val proxyPort = proxy.start()

    @After
    fun tearDown() { proxy.stop(); upstream.close() }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readHead(i: InputStream): List<String> {
        val lines = ArrayList<String>()
        while (true) { val l = readLine(i); if (l.isEmpty()) return lines; lines += l }
    }

    private fun connectProxy() = Socket().apply { connect(InetSocketAddress(loop, proxyPort), 2000); soTimeout = 5000 }

    @Test
    fun connectTunnelEchoesAndHalfCloses() {
        // 上游：回显，直到读到 EOF，然后再写 "bye" 并关闭（测半关闭）
        Thread {
            upstream.accept().use { s ->
                val buf = ByteArray(4096)
                while (true) { val n = s.getInputStream().read(buf); if (n < 0) break; s.getOutputStream().write(buf, 0, n) }
                s.getOutputStream().write("bye".toByteArray())
            }
        }.start()
        connectProxy().use { c ->
            c.getOutputStream().write("CONNECT upstream.test:${upstream.localPort} HTTP/1.1\r\nHost: upstream.test:${upstream.localPort}\r\n\r\n".toByteArray())
            val head = readHead(c.getInputStream())
            assertEquals("HTTP/1.1 200 Connection Established", head.first())
            val payload = ByteArray(200_000) { (it % 251).toByte() } // 比缓冲区大很多
            val writer = Thread { c.getOutputStream().write(payload); c.shutdownOutput() }.also { it.start() }
            val got = c.getInputStream().readBytes()
            writer.join()
            assertEquals(payload.size + 3, got.size)
            assertTrue(payload.contentEquals(got.copyOf(payload.size)))
            assertEquals("bye", String(got, payload.size, 3))
        }
        Thread.sleep(100)
        assertEquals(0, proxy.tunnels.get())
    }

    @Test
    fun plainHttpIsRewrittenToOriginForm() {
        val seen = ArrayBlockingQueue<List<String>>(1)
        Thread {
            upstream.accept().use { s ->
                seen.put(readHead(s.getInputStream()))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi".toByteArray())
            }
        }.start()
        connectProxy().use { c ->
            c.getOutputStream().write(
                ("GET http://upstream.test:${upstream.localPort}/a/b?c=1 HTTP/1.1\r\nHost: upstream.test\r\n" +
                    "Proxy-Connection: keep-alive\r\nConnection: keep-alive\r\nUser-Agent: t\r\n\r\n").toByteArray(),
            )
            val resp = String(c.getInputStream().readBytes())
            assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK") && resp.endsWith("hi"))
        }
        val head = seen.poll(3, TimeUnit.SECONDS)!!
        assertEquals("GET /a/b?c=1 HTTP/1.1", head[0])
        assertTrue(head.contains("Host: upstream.test"))
        assertTrue(head.contains("User-Agent: t"))
        assertTrue(head.contains("Connection: close"))
        assertFalse(head.any { it.startsWith("Proxy-Connection", true) || it == "Connection: keep-alive" })
    }

    @Test
    fun unknownHostGets502AndSelfLoopGets403() {
        connectProxy().use { c ->
            c.getOutputStream().write("CONNECT nope.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(readLine(c.getInputStream()).startsWith("HTTP/1.1 502"))
        }
        connectProxy().use { c ->
            c.getOutputStream().write("CONNECT 127.0.0.1:$proxyPort HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(readLine(c.getInputStream()).startsWith("HTTP/1.1 403"))
        }
        connectProxy().use { c ->
            c.getOutputStream().write("garbage\r\n\r\n".toByteArray())
            assertTrue(readLine(c.getInputStream()).startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun happyEyeballsFallsThroughToWorkingAddress() {
        // 第一个地址连不上（被拒绝或黑洞），第二个是真的：应该很快连上第二个
        val dead = InetAddress.getByName("127.0.0.2") // 上游只监听 127.0.0.1
        val black = InetAddress.getByName("192.0.2.1") // TEST-NET-1：通常无响应
        Thread { runCatching { upstream.accept().close() } }.start()
        val t0 = System.nanoTime()
        val s = proxy.connectHappyEyeballs(listOf(black, dead, loop), upstream.localPort)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(s != null && s.isConnected)
        assertEquals(loop, s!!.inetAddress)
        assertTrue("took $ms ms", ms < 2000)
        s.close()
    }

    @Test
    fun splitHostPort() {
        assertEquals("a.com" to 443, LocalProxy.splitHostPort("a.com:443", 80))
        assertEquals("a.com" to 80, LocalProxy.splitHostPort("a.com", 80))
        assertEquals("::1" to 8443, LocalProxy.splitHostPort("[::1]:8443", 80))
        assertEquals("::1" to 443, LocalProxy.splitHostPort("[::1]", 443))
        assertEquals(null, LocalProxy.splitHostPort("a.com:99999", 80))
        assertEquals(null, LocalProxy.splitHostPort(":443", 80))
    }
}
