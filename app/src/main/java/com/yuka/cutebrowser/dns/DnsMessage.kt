package com.yuka.cutebrowser.dns

import java.io.IOException
import java.net.InetAddress

/**
 * 最小的 DNS 报文编解码（RFC 1035 线格式，RFC 8484 的 application/dns-message 就是它）。
 * 只做浏览器需要的：A / AAAA 查询，解析回答里的地址和 TTL，NXDOMAIN 的否定缓存 TTL（SOA）。
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val CLASS_IN = 1

    const val RCODE_OK = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    /** RFC 8467：查询填充到 128 字节的整数倍，让查询长度不泄露域名长度 */
    private const val PAD_BLOCK = 128

    class FormatException(msg: String) : IOException(msg)

    class Record(val name: String, val type: Int, val ttl: Long, val address: InetAddress?, val soaMinimum: Long = -1)

    class Response(
        val id: Int,
        val rcode: Int,
        val truncated: Boolean,
        val questionName: String?,
        val questionType: Int,
        val answers: List<Record>,
        val authority: List<Record>,
    ) {
        /** 回答里对应类型的地址（CNAME 链上的最终地址；我们只问一个名字，所以收集同类型记录即可） */
        fun addresses(type: Int): List<InetAddress> = answers.filter { it.type == type && it.address != null }.map { it.address!! }

        /** 正向结果的 TTL：回答链（CNAME + 地址）里最小的 TTL */
        fun minTtl(type: Int): Long {
            val rel = answers.filter { it.type == type || it.type == TYPE_CNAME }
            return if (rel.isEmpty()) -1 else rel.minOf { it.ttl }
        }

        /** 否定缓存 TTL（RFC 2308）：min(SOA 记录 TTL, SOA.minimum)；没有 SOA 返回 -1 */
        fun negativeTtl(): Long {
            val soa = authority.firstOrNull { it.type == TYPE_SOA } ?: return -1
            return minOf(soa.ttl, soa.soaMinimum)
        }
    }

    /** 把主机名规范化成 DNS 名（小写、去掉结尾的点），不合法时抛异常 */
    fun normalizeName(host: String): String {
        val n = host.trim().trimEnd('.').lowercase()
        if (n.isEmpty() || n.length > 253) throw FormatException("bad name length")
        for (label in n.split('.')) {
            if (label.isEmpty() || label.length > 63) throw FormatException("bad label")
            for (c in label) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_')) throw FormatException("bad char")
        }
        return n
    }

    /** 构造查询：RD=1，带 EDNS0 OPT（4096 字节 UDP 大小没意义但无害）+ 填充选项 */
    fun buildQuery(host: String, type: Int, id: Int = 0, pad: Boolean = true): ByteArray {
        val name = normalizeName(host)
        val out = java.io.ByteArrayOutputStream(128)
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(1)
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII)) }
        out.write(0)
        u16(type); u16(CLASS_IN)
        // OPT RR：名字=根, TYPE=41, CLASS=UDP 大小, TTL=0, RDLEN, 选项(12 = Padding)
        val fixed = out.size() + 11 + 4
        val padLen = if (pad) ((PAD_BLOCK - fixed % PAD_BLOCK) % PAD_BLOCK) else 0
        out.write(0); u16(TYPE_OPT); u16(4096); u16(0); u16(0)
        if (pad) {
            u16(4 + padLen); u16(12); u16(padLen)
            repeat(padLen) { out.write(0) }
        } else u16(0)
        return out.toByteArray()
    }

    fun parse(msg: ByteArray): Response {
        val r = Reader(msg)
        val id = r.u16()
        val flags = r.u16()
        if (flags and 0x8000 == 0) throw FormatException("not a response")
        val qd = r.u16(); val an = r.u16(); val ns = r.u16(); r.u16()
        var qName: String? = null
        var qType = 0
        repeat(qd) {
            val n = r.name(); val t = r.u16(); r.u16()
            if (qName == null) { qName = n; qType = t }
        }
        val answers = ArrayList<Record>(an)
        repeat(an) { answers += r.record() }
        val auth = ArrayList<Record>(ns)
        repeat(ns) { auth += r.record() }
        // additional 区（OPT 等）不需要
        return Response(id, flags and 0x0F, flags and 0x0200 != 0, qName, qType, answers, auth)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun need(n: Int) { if (pos + n > b.size) throw FormatException("truncated message") }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun u16(): Int { need(2); val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF); pos += 2; return v }
        fun u32(): Long { val hi = u16().toLong(); val lo = u16().toLong(); return (hi shl 16) or lo }

        /** 读名字，支持压缩指针；防循环 */
        fun name(): String {
            val sb = StringBuilder()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                if (p >= b.size) throw FormatException("name out of range")
                val len = b[p].toInt() and 0xFF
                when {
                    len == 0 -> { p++; break }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= b.size) throw FormatException("bad pointer")
                        val target = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (!jumped) pos = p + 2
                        jumped = true
                        if (++hops > 64 || target >= b.size) throw FormatException("pointer loop")
                        p = target
                    }
                    len and 0xC0 != 0 -> throw FormatException("bad label type")
                    else -> {
                        if (p + 1 + len > b.size) throw FormatException("label out of range")
                        if (sb.isNotEmpty()) sb.append('.')
                        for (i in 1..len) sb.append((b[p + i].toInt() and 0xFF).toChar())
                        p += 1 + len
                        if (sb.length > 255) throw FormatException("name too long")
                    }
                }
            }
            if (!jumped) pos = p
            return sb.toString().lowercase()
        }

        fun record(): Record {
            val n = name()
            val type = u16(); u16()
            val ttl = u32().let { if (it > Int.MAX_VALUE) 0 else it } // RFC 2181：最高位为 1 视为 0
            val rdLen = u16()
            need(rdLen)
            val start = pos
            var addr: InetAddress? = null
            var soaMin = -1L
            when (type) {
                TYPE_A -> if (rdLen == 4) addr = InetAddress.getByAddress(n, b.copyOfRange(start, start + 4))
                TYPE_AAAA -> if (rdLen == 16) addr = InetAddress.getByAddress(n, b.copyOfRange(start, start + 16))
                TYPE_SOA -> { name(); name(); repeat(4) { u32() }; soaMin = u32() }
            }
            pos = start + rdLen
            return Record(n, type, ttl, addr, soaMin)
        }
    }
}
