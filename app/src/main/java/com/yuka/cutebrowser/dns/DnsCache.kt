package com.yuka.cutebrowser.dns

import java.net.InetAddress

/**
 * 带 TTL 的小 LRU 缓存。键 = "host|type"。
 * - 过期后还能"陈旧可用"一小段时间（stale-while-revalidate）：先直接用旧结果、后台再刷新，页面不用等 DNS。
 * - 否定结果（NXDOMAIN / 没有这种记录）也缓存，TTL 短一些。
 * - 时间源可注入，方便单元测试。
 */
class DnsCache(
    private val maxEntries: Int = 512,
    private val minTtlSec: Long = 30,
    private val maxTtlSec: Long = 6 * 3600,
    private val negMinSec: Long = 15,
    private val negMaxSec: Long = 300,
    private val staleSec: Long = 300,
    private val clock: () -> Long = { System.nanoTime() },
) {
    class Entry(val addresses: List<InetAddress>, val nxdomain: Boolean, val expiresAt: Long, val staleUntil: Long)

    sealed class Lookup {
        class Fresh(val entry: Entry) : Lookup()
        class Stale(val entry: Entry) : Lookup()
        object Miss : Lookup()
    }

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun key(host: String, type: Int) = "$host|$type"

    @Synchronized
    fun get(host: String, type: Int): Lookup {
        val k = key(host, type)
        val e = map[k] ?: return Lookup.Miss
        val now = clock()
        return when {
            now < e.expiresAt -> Lookup.Fresh(e)
            now < e.staleUntil -> Lookup.Stale(e)
            else -> { map.remove(k); Lookup.Miss }
        }
    }

    /** 正向结果；ttlSec<0（服务器没给）按最短 TTL 处理。空列表 = NODATA（有域名但没有这种记录） */
    @Synchronized
    fun put(host: String, type: Int, addresses: List<InetAddress>, ttlSec: Long) {
        val ttl = if (addresses.isEmpty()) ttlSec.coerceIn(negMinSec, negMaxSec) else ttlSec.coerceIn(minTtlSec, maxTtlSec)
        store(host, type, Entry(addresses, false, 0, 0), ttl)
    }

    @Synchronized
    fun putNxdomain(host: String, type: Int, ttlSec: Long) {
        store(host, type, Entry(emptyList(), true, 0, 0), ttlSec.coerceIn(negMinSec, negMaxSec))
    }

    private fun store(host: String, type: Int, e: Entry, ttlSec: Long) {
        val now = clock()
        val exp = now + ttlSec * 1_000_000_000L
        // 否定结果不做"陈旧可用"：新注册的域名要能尽快查到
        val stale = if (e.nxdomain || e.addresses.isEmpty()) exp else exp + staleSec * 1_000_000_000L
        map[key(host, type)] = Entry(e.addresses, e.nxdomain, exp, stale)
    }

    @Synchronized fun clear() = map.clear()
    @Synchronized fun size() = map.size
}
