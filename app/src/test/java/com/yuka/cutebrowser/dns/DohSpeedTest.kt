package com.yuka.cutebrowser.dns

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.6.2：测速必须把 Exception / Error / 超时都收成结果，不能把 App 打崩 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DohSpeedTest {

    @Test
    fun exceptionBecomesResult() = runBlocking {
        val r = DohManager.test(DohProvider.CLOUDFLARE) {
            throw IllegalStateException("boom")
        }
        assertFalse(r.ok)
        assertEquals(-1, r.coldMs)
        assertTrue(r.detail.contains("boom"))
    }

    @Test
    fun errorBecomesResult() = runBlocking {
        val r = DohManager.test(DohProvider.CLOUDFLARE) {
            throw ExceptionInInitializerError("okhttp missing")
        }
        assertFalse(r.ok)
        assertTrue(r.detail.contains("okhttp missing"))
    }

    @Test
    fun timeoutBecomesResult() = runBlocking {
        val r = DohManager.test(DohProvider.CLOUDFLARE, timeoutMs = 80) {
            delay(5_000)
            error("should not reach")
        }
        assertFalse(r.ok)
        assertTrue(r.detail, r.detail.contains("超时"))
    }

    @Test
    fun badUrlDoesNotThrow() {
        val bad = DohProvider("x", "坏地址", "not a url", emptyList())
        val r = DohManager.probeProvider(bad)
        assertFalse(r.ok)
        assertTrue(r.detail.isNotBlank())
    }

    @Test
    fun successfulFakeTransport() {
        val ip = byteArrayOf(1, 2, 3, 4)
        val transport = DohTransport { query -> aResponse(query, ip) }
        val r = DohManager.probeProvider(DohProvider.CLOUDFLARE, transport)
        assertTrue(r.detail, r.ok)
        assertTrue(r.coldMs >= 0)
        assertTrue(r.warmMs >= 0)
        assertTrue(r.detail.contains("1.2.3.4"))
    }

    @Test
    fun perProviderErrorsStayIsolated() = runBlocking {
        val lines = DohProvider.BUILT_IN.map { pr ->
            val r = DohManager.test(pr) { throw AssertionError("${pr.id} failed") }
            "${pr.label}：连不上 😿（${r.detail}）"
        }
        assertEquals(DohProvider.BUILT_IN.size, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue(line, line.contains(DohProvider.BUILT_IN[i].label))
            assertTrue(line, line.contains("failed"))
        }
    }
}

/** 极简 A 回答：把查询的问题区拷过来，再附一条 IPv4 */
private fun aResponse(query: ByteArray, ip: ByteArray): ByteArray {
    // 跳过 DNS 头部 12 字节，读问题名直到 0，再加上 type+class 4 字节
    var i = 12
    while (i < query.size && query[i].toInt() != 0) {
        i += 1 + (query[i].toInt() and 0xFF)
    }
    i += 1 + 4 // 0 终止符 + type + class
    val question = query.copyOfRange(0, i.coerceAtMost(query.size))
    question[2] = (question[2].toInt() or 0x80).toByte() // QR
    question[4] = 0
    question[5] = 1 // QDCOUNT
    question[6] = 0
    question[7] = 1 // ANCOUNT
    val out = java.io.ByteArrayOutputStream()
    out.write(question)
    // name pointer to offset 12, TYPE A, CLASS IN, TTL 60, RDLEN 4
    out.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
    out.write(ip)
    return out.toByteArray()
}
