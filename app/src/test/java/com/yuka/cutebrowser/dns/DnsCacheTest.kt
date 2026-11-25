package com.yuka.cutebrowser.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DnsCacheTest {
    private var now = 0L
    private fun sec(s: Long) { now += s * 1_000_000_000L }
    private val ip = listOf(InetAddress.getByAddress("h", byteArrayOf(1, 2, 3, 4)))

    @Test
    fun freshThenStaleThenMiss() {
        val c = DnsCache(minTtlSec = 30, staleSec = 300, clock = { now })
        c.put("a.com", 1, ip, 100)
        assertTrue(c.get("a.com", 1) is DnsCache.Lookup.Fresh)
        assertTrue(c.get("a.com", 28) is DnsCache.Lookup.Miss)
        sec(99); assertTrue(c.get("a.com", 1) is DnsCache.Lookup.Fresh)
        sec(2); assertTrue(c.get("a.com", 1) is DnsCache.Lookup.Stale)
        sec(298); assertTrue(c.get("a.com", 1) is DnsCache.Lookup.Stale)
        sec(2); assertTrue(c.get("a.com", 1) is DnsCache.Lookup.Miss)
        assertEquals(0, c.size())
    }

    @Test
    fun clampsTtl() {
        val c = DnsCache(minTtlSec = 30, maxTtlSec = 3600, staleSec = 0, clock = { now })
        c.put("low.com", 1, ip, 1)       // 太短 → 30 s
        c.put("high.com", 1, ip, 86400)  // 太长 → 1 h
        c.put("none.com", 1, ip, -1)     // 没给 → 30 s
        sec(29)
        assertTrue(c.get("low.com", 1) is DnsCache.Lookup.Fresh)
        assertTrue(c.get("none.com", 1) is DnsCache.Lookup.Fresh)
        sec(2)
        assertTrue(c.get("low.com", 1) is DnsCache.Lookup.Miss)
        sec(3500)
        assertTrue(c.get("high.com", 1) is DnsCache.Lookup.Fresh)
        sec(100)
        assertTrue(c.get("high.com", 1) is DnsCache.Lookup.Miss)
    }

    @Test
    fun negativeEntriesAreShortAndNeverStale() {
        val c = DnsCache(negMinSec = 15, negMaxSec = 300, staleSec = 300, clock = { now })
        c.putNxdomain("nx.com", 1, 86400)
        c.put("nodata.com", 28, emptyList(), -1)
        val e = (c.get("nx.com", 1) as DnsCache.Lookup.Fresh).entry
        assertTrue(e.nxdomain)
        sec(16); assertTrue(c.get("nodata.com", 28) is DnsCache.Lookup.Miss)
        sec(285); assertTrue(c.get("nx.com", 1) is DnsCache.Lookup.Miss) // 300 s 封顶、没有陈旧期
    }

    @Test
    fun lruEviction() {
        val c = DnsCache(maxEntries = 3, clock = { now })
        c.put("1.com", 1, ip, 60); c.put("2.com", 1, ip, 60); c.put("3.com", 1, ip, 60)
        c.get("1.com", 1) // 1 变成最近用过
        c.put("4.com", 1, ip, 60)
        assertTrue(c.get("2.com", 1) is DnsCache.Lookup.Miss)
        assertTrue(c.get("1.com", 1) is DnsCache.Lookup.Fresh)
        assertEquals(3, c.size())
    }
}
