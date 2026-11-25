package com.yuka.cutebrowser.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DohResolverTest {
    /** 假 DoH 服务器：按问题回答 */
    private class FakeTransport(val delayMs: Long = 0, val answer: (String, Int) -> ByteArray?) : DohTransport {
        val calls = AtomicInteger()
        override fun exchange(query: ByteArray): ByteArray {
            calls.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            // 从查询里读出名字和类型（查询本身也是合法报文，只是 QR=0）
            val q = query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
            val parsed = DnsMessage.parse(q)
            return answer(parsed.questionName!!, parsed.questionType) ?: throw IOException("server down")
        }
    }

    private fun reply(name: String, type: Int, ttl: Long, vararg ips: ByteArray, rcode: Int = 0): ByteArray {
        val b = MsgBuilder(rcode = rcode, an = ips.size).question(name, type)
        ips.forEach { b.ptr(12).u16(type).u16(1).u32(ttl).u16(it.size).bytes(*it.map { x -> x.toInt() and 0xFF }.toIntArray()) }
        return b.build()
    }

    private val v4 = byteArrayOf(10, 0, 0, 1)
    private val v4b = byteArrayOf(10, 0, 0, 2)
    private val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }

    private fun resolver(t: DohTransport, strict: Boolean = false, system: (String) -> List<InetAddress> = { throw UnknownHostException(it) }) =
        DohResolver(DohProvider.CLOUDFLARE, strict, t, DnsCache(), system, timeoutMs = 2000)

    @Test
    fun combinesAandAaaaInterleavedAndCaches() {
        val t = FakeTransport { n, type -> if (type == 1) reply(n, 1, 300, v4, v4b) else reply(n, 28, 300, v6) }
        val r = resolver(t)
        val got = r.resolve("Shop.Example.com.").map { it.hostAddress }
        assertEquals(listOf("2001:0:0:0:0:0:0:1", "10.0.0.1", "10.0.0.2").map { InetAddress.getByName(it).hostAddress }, got)
        assertEquals(2, t.calls.get())
        r.resolve("shop.example.com")
        assertEquals(2, t.calls.get()) // 第二次全部命中缓存
        assertEquals(2, r.cacheHits.get())
        r.close()
    }

    @Test
    fun ipLiteralsAndLocalhostSkipDns() {
        val t = FakeTransport { _, _ -> fail("should not query"); null }
        val r = resolver(t)
        assertEquals("1.2.3.4", r.resolve("1.2.3.4").single().hostAddress)
        assertEquals(16, r.resolve("[::1]").single().address.size)
        assertTrue(r.resolve("localhost").single().isLoopbackAddress)
        assertEquals(0, t.calls.get())
        r.close()
    }

    @Test
    fun nxdomainThrowsWithoutFallback() {
        val t = FakeTransport { n, type -> reply(n, type, 0, rcode = 3) }
        var systemCalled = false
        val r = resolver(t) { systemCalled = true; listOf(InetAddress.getByAddress(v4)) }
        try { r.resolve("nope.invalid"); fail() } catch (e: UnknownHostException) {}
        assertTrue(!systemCalled)
        r.close()
    }

    @Test
    fun failOpenFallsBackToSystemDnsAndNotifies() {
        val t = FakeTransport { _, _ -> null }
        var noticed = 0
        val r = resolver(t) { listOf(InetAddress.getByAddress(it, v4)) }
        r.listener = object : DohResolver.Listener { override fun onFallback(host: String, error: Throwable) { noticed++ } }
        assertEquals("10.0.0.1", r.resolve("down.example").single().hostAddress)
        assertEquals(1, noticed)
        assertEquals(1, r.fallbacks.get())
        r.close()
    }

    @Test
    fun strictModeFailsClosed() {
        val t = FakeTransport { _, _ -> null }
        var systemCalled = false
        val r = resolver(t, strict = true) { systemCalled = true; emptyList() }
        try { r.resolve("down.example"); fail() } catch (e: UnknownHostException) {}
        assertTrue(!systemCalled)
        assertEquals(1, r.failures.get())
        r.close()
    }

    @Test
    fun slowAaaaDoesNotBlock() {
        val t = object : DohTransport {
            override fun exchange(query: ByteArray): ByteArray {
                val q = query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
                val p = DnsMessage.parse(q)
                if (p.questionType == 28) Thread.sleep(1500)
                return if (p.questionType == 1) reply(p.questionName!!, 1, 60, v4) else reply(p.questionName!!, 28, 60, v6)
            }
        }
        val r = resolver(t)
        val t0 = System.nanoTime()
        val got = r.resolve("slow6.example")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf("10.0.0.1"), got.map { it.hostAddress })
        assertTrue("took $ms ms", ms < 800)
        r.close()
    }

    @Test
    fun concurrentLookupsAreCoalesced() {
        val t = FakeTransport(delayMs = 200) { n, type -> if (type == 1) reply(n, 1, 60, v4) else reply(n, 28, 60) }
        val r = resolver(t)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) { pool.execute { try { assertEquals(1, r.resolve("same.example").size) } finally { done.countDown() } } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(2, t.calls.get()) // A + AAAA 各一次
        pool.shutdown(); r.close()
    }
}
