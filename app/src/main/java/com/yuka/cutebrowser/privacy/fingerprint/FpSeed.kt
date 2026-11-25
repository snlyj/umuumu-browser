package com.yuka.cutebrowser.privacy.fingerprint

import com.yuka.cutebrowser.filter.Domains
import java.security.SecureRandom

/**
 * 防指纹的"网站"和"种子"。必须和 assets/fingerprint.min.js（源码 fp-js/fingerprint.js）里的实现一模一样，
 * FpSeedTest 里有对照用的固定向量（同样的向量在 fp-js/seed-vectors.mjs 里用 node 跑 JS 版核对）。
 */
object FpSeed {
    /** 近似 eTLD+1：小写、去掉结尾的点；IP 和 IPv6 原样返回；常见二级后缀（co.uk、com.cn…）多留一级 */
    fun site(host: String?): String {
        var h = (host ?: "").lowercase()
        if (h.endsWith('.')) h = h.dropLast(1)
        if (h.isEmpty() || ':' in h || h.all { it in '0'..'9' || it == '.' }) return h
        val p = h.split('.')
        if (p.size <= 2) return h
        val t2 = p.takeLast(2).joinToString(".")
        return if (t2 in Domains.SECOND_LEVEL) p.takeLast(3).joinToString(".") else t2
    }

    /** FNV-1a 32 位（按 UTF-16 码元，和 JS 的 charCodeAt 一致） */
    fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in s) { h = h xor c.code; h *= 0x01000193 }
        return h
    }

    /** murmur3 的 fmix32 */
    fun fmix(x: Int): Int {
        var h = x
        h = h xor (h ushr 16); h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13); h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** 每个网站的种子（无符号 32 位，用 Long 表示，方便和 JS 的 >>>0 比较） */
    fun seed(key: String, site: String): Long = fmix(fnv1a("$key|$site")).toLong() and 0xffffffffL

    private val rnd by lazy { SecureRandom() }

    /** 新的会话密钥：16 字节随机数的十六进制 */
    fun newKey(): String {
        val b = ByteArray(16).also { rnd.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }
}
