package com.yuka.cutebrowser.security

/** HTTPS-only 模式：把 http:// 升级成 https://（本机地址除外，用户允许过的网站除外） */
object HttpsUpgrade {
    /**
     * 取出 URL 的主机名（小写；IPv6 带方括号、不带端口）。
     * 按浏览器（WHATWG）的规则先清理：去掉 Tab/换行，把 `\` 当成 `/`——否则 `http://evil.com\@localhost/` 会被我们读成 localhost
     * （放过 https 升级），而 Chromium 实际连的是 evil.com。
     */
    fun hostOf(url: String): String {
        val cleaned = url.trim().filterNot { it == '\t' || it == '\r' || it == '\n' }.replace('\\', '/')
        val s = cleaned.substringAfter("://", cleaned)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var hostPort = if (end >= 0) s.substring(0, end) else s
        hostPort = hostPort.substringAfterLast('@')
        return if (hostPort.startsWith("[")) hostPort.substringBefore(']').lowercase() + "]" else hostPort.substringBefore(':').lowercase()
    }

    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** 本机地址（不升级 https）：localhost / *.localhost / 127.0.0.0/8（必须是真正的 IPv4 写法，`127.evil.com` 不算）/ ::1 / 0.0.0.0 */
    fun isLocal(host: String): Boolean {
        val h = host.trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h == "[::1]" || h == "0.0.0.0") return true
        val m = V4.matchEntire(h) ?: return false
        val p = m.groupValues.drop(1).map { it.toInt() }
        return p.all { it <= 255 } && p[0] == 127
    }

    /** 需要升级时返回 https 地址，否则 null */
    fun upgrade(url: String, allowHttpHosts: Set<String>): String? {
        if (!url.regionMatches(0, "http://", 0, 7, ignoreCase = true)) return null
        val host = hostOf(url)
        if (host.isEmpty() || isLocal(host) || host in allowHttpHosts) return null
        // 显式写了 :80 端口的去掉（https 默认 443）
        val rest = url.substring(7)
        val fixed = if (rest.startsWith("$host:80/", ignoreCase = true) || rest.equals("$host:80", ignoreCase = true)) {
            host + rest.substring(host.length + 3)
        } else rest
        return "https://$fixed"
    }
}

/** 通用 User-Agent：去掉 WebView 标记（"; wv"、"Version/4.0"）和机型/系统版本细节，只保留 Chrome 主版本 */
object UserAgents {
    private val PLATFORM = Regex("\\(Linux; Android [^)]*\\)")
    private val CHROME = Regex("Chrome/(\\d+)\\.[\\d.]+")

    fun generic(defaultUa: String): String {
        var ua = defaultUa.replace("; wv)", ")").replace(" Version/4.0", "")
        ua = PLATFORM.replace(ua, "(Linux; Android 10; K)")
        // Chrome 的"精简 UA"也是只给主版本号
        ua = CHROME.replace(ua) { "Chrome/${it.groupValues[1]}.0.0.0" }
        return ua
    }
}
