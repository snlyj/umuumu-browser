package com.yuka.cutebrowser.browser

import com.yuka.cutebrowser.security.HttpsUpgrade

/**
 * "可能马上要打开的网站"的 DNS 预热（preconnect 的便宜版本）：
 * 地址栏获得焦点时预热搜索引擎的域名，输入的内容像网址时预热那个域名。
 * WebView 的网络栈拿不到我们自己建的连接，所以只做 DNS（加密 DNS 开着时就是 DoH 查询 + 把到 DoH 服务器的连接建好），
 * 这一步省下来的就是第一个请求前的域名解析那几十毫秒。
 *
 * - 同一个域名 [ttlMs] 内只预热一次；最多记 [maxHosts] 个，超出时丢最旧的。
 * - 只预热 http(s) 的主机名；IP 地址、localhost、内网/本机域名不预热（没有意义，也不想把它们发出去）。
 * - 真正去解析的动作由 [resolve] 决定（调用方负责放到 IO 线程、检查代理等隐私条件）。
 * 线程安全。
 */
class HostWarmer(
    private val ttlMs: Long = 60_000,
    private val maxHosts: Int = 64,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val resolve: (List<String>) -> Unit,
) {
    private val seen = object : LinkedHashMap<String, Long>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxHosts
    }

    /** 预热 [urlOrHost] 的主机。返回是否真的触发了一次预热（已预热过 / 不适合预热则 false） */
    fun warm(urlOrHost: String): Boolean = warmAll(listOf(urlOrHost))

    fun warmAll(items: List<String>): Boolean {
        val t = now()
        val fresh = ArrayList<String>(items.size)
        synchronized(seen) {
            for (item in items) {
                val h = hostOf(item) ?: continue
                val last = seen[h]
                if (last != null && t - last < ttlMs) continue
                seen.remove(h) // 重新放到队尾
                seen[h] = t
                fresh += h
            }
        }
        if (fresh.isEmpty()) return false
        resolve(fresh)
        return true
    }

    fun clear() = synchronized(seen) { seen.clear() }

    companion object {
        /** 从网址或裸域名里取主机名；不适合预热的返回 null */
        fun hostOf(input: String): String? {
            val t = input.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in t) t else "https://$t"
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = HttpsUpgrade.hostOf(withScheme).lowercase().trimEnd('.')
            if (host.isEmpty() || host.length > 253) return null
            if (host.startsWith("[")) return null                                 // IPv6 字面量
            if (host.all { it.isDigit() || it == '.' }) return null               // IPv4 字面量
            if ('.' !in host) return null                                         // localhost / 局域网里的单标签主机名
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal") ||
                host.endsWith(".home.arpa") || host.endsWith(".localhost")
            ) return null
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
            return host
        }
    }
}
