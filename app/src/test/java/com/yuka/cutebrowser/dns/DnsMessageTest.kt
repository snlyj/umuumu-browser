package com.yuka.cutebrowser.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 手工拼 DNS 回答报文，用来测解析 */
class MsgBuilder(id: Int = 0, rcode: Int = 0, qd: Int = 1, an: Int = 0, ns: Int = 0) {
    val out = ByteArrayOutputStream()
    init { u16(id); u16(0x8180 or rcode); u16(qd); u16(an); u16(ns); u16(0) }
    fun u16(v: Int) = apply { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    fun u32(v: Long) = apply { u16((v ushr 16).toInt() and 0xFFFF); u16(v.toInt() and 0xFFFF) }
    fun name(n: String) = apply { n.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
    fun ptr(off: Int) = apply { u16(0xC000 or off) }
    fun bytes(vararg b: Int) = apply { b.forEach { out.write(it) } }
    fun question(n: String, type: Int) = apply { name(n); u16(type); u16(1) }
    fun size() = out.size()
    fun build(): ByteArray = out.toByteArray()
}

class DnsMessageTest {
    @Test
    fun buildsPaddedQuery() {
        val q = DnsMessage.buildQuery("WWW.Example.COM.", DnsMessage.TYPE_A, id = 0x1234)
        assertEquals(0, q.size % 128)
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(0x01, q[2].toInt()); assertEquals(0x00, q[3].toInt()) // RD
        assertEquals(1, q[5].toInt()) // QDCOUNT
        assertEquals(1, q[11].toInt()) // ARCOUNT (OPT)
        val qname = byteArrayOf(3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(), 7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        assertArrayEquals(qname, q.copyOfRange(12, 12 + qname.size))
        val afterQ = 12 + qname.size
        assertEquals(DnsMessage.TYPE_A, ((q[afterQ].toInt() and 0xFF) shl 8) or (q[afterQ + 1].toInt() and 0xFF))
        // OPT：根名字 + TYPE 41
        assertEquals(0, q[afterQ + 4].toInt())
        assertEquals(41, q[afterQ + 6].toInt())
    }

    @Test
    fun buildsUnpaddedQueryExactly() {
        val q = DnsMessage.buildQuery("a.io", DnsMessage.TYPE_AAAA, pad = false)
        val expected = byteArrayOf(
            0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1,
            1, 'a'.code.toByte(), 2, 'i'.code.toByte(), 'o'.code.toByte(), 0, 0, 28, 0, 1,
            0, 0, 41, 16, 0, 0, 0, 0, 0, 0, 0,
        )
        assertArrayEquals(expected, q)
    }

    @Test
    fun rejectsBadNames() {
        for (bad in listOf("", "a..b", "x".repeat(64) + ".com", "a b.com", "例子.com", "a".repeat(254))) {
            try { DnsMessage.buildQuery(bad, DnsMessage.TYPE_A); fail("accepted '$bad'") } catch (e: DnsMessage.FormatException) {}
        }
    }

    @Test
    fun parsesCnameChainWithCompression() {
        val b = MsgBuilder(an = 3).question("www.example.com", 1)
        // www.example.com CNAME cdn.example.net (TTL 300)
        b.ptr(12).u16(5).u16(1).u32(300)
        val cname = MsgBuilder(qd = 0).also { it.out.reset() }.name("cdn.example.net").build()
        val cnameOffset = b.size() + 2
        b.u16(cname.size).bytes(*cname.map { it.toInt() and 0xFF }.toIntArray())
        // cdn.example.net A 1.2.3.4 (TTL 60) / A 5.6.7.8 (TTL 120)，名字用指针
        b.ptr(cnameOffset).u16(1).u16(1).u32(60).u16(4).bytes(1, 2, 3, 4)
        b.ptr(cnameOffset).u16(1).u16(1).u32(120).u16(4).bytes(5, 6, 7, 8)
        val r = DnsMessage.parse(b.build())
        assertEquals(DnsMessage.RCODE_OK, r.rcode)
        assertEquals("www.example.com", r.questionName)
        assertEquals(1, r.questionType)
        assertEquals("cdn.example.net", r.answers[1].name)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), r.addresses(DnsMessage.TYPE_A).map { it.hostAddress })
        assertEquals(60, r.minTtl(DnsMessage.TYPE_A))
        assertTrue(r.addresses(DnsMessage.TYPE_AAAA).isEmpty())
    }

    @Test
    fun parsesAaaa() {
        val b = MsgBuilder(an = 1).question("v6.test", 28)
        b.ptr(12).u16(28).u16(1).u32(3600).u16(16).bytes(0x24, 0x00, 0x32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val r = DnsMessage.parse(b.build())
        assertEquals("2400:3200:0:0:0:0:0:1", r.addresses(DnsMessage.TYPE_AAAA).single().hostAddress)
        assertEquals(3600, r.minTtl(DnsMessage.TYPE_AAAA))
    }

    @Test
    fun parsesNxdomainNegativeTtl() {
        val b = MsgBuilder(rcode = 3, ns = 1).question("nope.example", 1)
        // example SOA：mname/rname 用指针，serial refresh retry expire minimum
        b.ptr(17).u16(6).u16(1).u32(900)
        val rdata = MsgBuilder(qd = 0).also { it.out.reset() }.ptr(12).ptr(12).u32(1).u32(2).u32(3).u32(4).u32(60).build()
        b.u16(rdata.size).bytes(*rdata.map { it.toInt() and 0xFF }.toIntArray())
        val r = DnsMessage.parse(b.build())
        assertEquals(DnsMessage.RCODE_NXDOMAIN, r.rcode)
        assertEquals(60, r.negativeTtl())
        assertTrue(r.answers.isEmpty())
    }

    @Test
    fun rejectsMalformed() {
        val ok = MsgBuilder(an = 1).question("a.b", 1).ptr(12).u16(1).u16(1).u32(5).u16(4).bytes(1, 2, 3, 4).build()
        for (cut in listOf(5, 14, ok.size - 2)) {
            try { DnsMessage.parse(ok.copyOf(cut)); fail("accepted truncated $cut") } catch (e: DnsMessage.FormatException) {}
        }
        // 指针指向自己 → 循环
        val loop = MsgBuilder(an = 0).ptr(12).u16(1).u16(1).build()
        try { DnsMessage.parse(loop); fail("pointer loop accepted") } catch (e: DnsMessage.FormatException) {}
        // 不是回答（QR=0）
        try { DnsMessage.parse(DnsMessage.buildQuery("a.b", 1)); fail("query accepted as response") } catch (e: DnsMessage.FormatException) {}
    }

    @Test
    fun ttlHighBitIsZero() {
        val b = MsgBuilder(an = 1).question("a.b", 1).ptr(12).u16(1).u16(1).u32(0x80000000L).u16(4).bytes(9, 9, 9, 9)
        assertEquals(0, DnsMessage.parse(b.build()).minTtl(1))
    }

    @Test
    fun ipLiterals() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), IpLiteral.parse("1.2.3.4"))
        assertNull(IpLiteral.parse("1.2.3.256"))
        assertNull(IpLiteral.parse("example.com"))
        assertNull(IpLiteral.parse("cafe.be")) // 像十六进制但不是 IPv6
        assertEquals(16, IpLiteral.parse("[2606:4700:4700::1111]")!!.size)
        assertFalse(IpLiteral.parse("::1") == null)
    }

    @Test
    fun providers() {
        assertEquals(5, DohProvider.BUILT_IN.size)
        DohProvider.BUILT_IN.forEach { p ->
            assertTrue(p.url.startsWith("https://"))
            assertTrue(p.bootstrapAddresses().isNotEmpty())
            assertEquals(p.host, p.bootstrapAddresses().first().hostName)
        }
        assertEquals("doh.pub", DohProvider.byId("dnspod").host)
        assertNull(DohProvider.custom("http://x.com/dns-query"))
        assertNull(DohProvider.custom("not a url"))
        assertEquals(listOf("9.9.9.11"), DohProvider.custom("https://9.9.9.11/dns-query")!!.bootstrap)
        assertTrue(DohProvider.custom("https://dns.example/q")!!.bootstrap.isEmpty())
    }
}
