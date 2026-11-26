package com.yuka.cutebrowser.browser

import android.webkit.CookieManager
import com.yuka.cutebrowser.security.HttpsUpgrade

/**
 * 「清除本站数据」的纯逻辑部分（可以在 JVM 里测）。
 *
 * WebView 的 CookieManager 没有"按网站删除"的接口，只能：读出某个网址能看到的 Cookie 名字，
 * 再用同名、Max-Age=0 的 Cookie 去覆盖（Cookie 的身份 = 名字 + Domain + Path，所以 Domain/Path 的几种可能都要覆盖一遍）。
 * 读不到的（只在别的子域名、别的路径下的）删不到，所以这是"尽力而为"，界面上会写清删了多少个。
 */
object SiteData {
    /** host 属于这个网站（eTLD+1）吗 */
    fun inSite(host: String?, site: String): Boolean {
        if (host.isNullOrEmpty() || site.isEmpty()) return false
        val h = host.lowercase().trimEnd('.')
        return h == site || h.endsWith(".$site")
    }

    fun originInSite(origin: String, site: String): Boolean = inSite(HttpsUpgrade.hostOf(origin), site)

    /** "a=1; b=2; c" → [a, b]（没有名字的 Cookie 没法单独覆盖，跳过） */
    fun cookieNames(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val n = part.substring(0, eq).trim()
            if (n.isNotEmpty() && n.none { it == ',' || it.isWhitespace() }) out += n
        }
        return out.toList()
    }

    /** Domain 的几种可能：null = 只属于这个主机（不带 Domain），然后是 host 一路往上直到网站本身 */
    fun domainVariants(host: String, site: String): List<String?> {
        val out = ArrayList<String?>()
        out += null
        var h = host.lowercase().trimEnd('.')
        while (inSite(h, site)) {
            out += h
            val dot = h.indexOf('.')
            if (dot < 0 || h == site) break
            h = h.substring(dot + 1)
        }
        return out
    }

    /** Path 的几种可能："/"，以及当前页面路径的每一级（/a、/a/b，最多 6 级） */
    fun pathVariants(path: String?): List<String> {
        val out = LinkedHashSet<String>()
        out += "/"
        if (!path.isNullOrEmpty() && path.startsWith("/")) {
            val segs = path.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
            var cur = ""
            for (s in segs.take(6)) {
                cur += "/$s"
                out += cur
            }
        }
        return out.toList()
    }

    /** 用来覆盖（删除）一个 Cookie 的 Set-Cookie 字符串们 */
    fun expiryCookies(name: String, host: String, site: String, path: String?): List<String> {
        val hostPrefix = name.startsWith("__Host-")
        val domains = if (hostPrefix) listOf(null) else domainVariants(host, site)
        val paths = if (hostPrefix) listOf("/") else pathVariants(path)
        val out = ArrayList<String>(domains.size * paths.size)
        for (d in domains) for (p in paths) {
            out += buildString {
                append(name).append("=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=").append(p)
                if (d != null) append("; Domain=").append(d)
                append("; Secure") // 用 https 网址写入：带 Secure 才能覆盖 Secure / __Secure- Cookie
            }
        }
        return out
    }

    /**
     * 在 [hosts]（都属于 [site]）上把能看到的 Cookie 全部过期。返回删之前看得到、删之后看不到的 Cookie 个数。
     * 必须在主线程或 WebView 允许的线程调用。
     */
    fun clearCookies(cm: CookieManager, hosts: Collection<String>, site: String, path: String?): Int {
        fun visible(): Set<String> {
            val s = HashSet<String>()
            for (h in hosts) {
                cookieNames(cm.getCookie("https://$h/")).forEach { s += "$h|$it" }
                if (!path.isNullOrEmpty() && path != "/") cookieNames(cm.getCookie("https://$h$path")).forEach { s += "$h|$it" }
                cookieNames(cm.getCookie("http://$h/")).forEach { s += "$h|$it" }
            }
            return s
        }
        val before = visible()
        val names = before.mapTo(LinkedHashSet()) { it.substringAfter('|') }
        for (h in hosts) for (n in names) for (c in expiryCookies(n, h, site, path)) {
            cm.setCookie("https://$h/", c)
        }
        cm.flush()
        val after = visible()
        return before.count { it !in after }
    }
}
