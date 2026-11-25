package com.yuka.cutebrowser.security

/** HTTPS-only 模式：把 http:// 升级成 https://（本机地址除外，用户允许过的网站除外） */
object HttpsUpgrade {
    fun hostOf(url: String): String {
        val s = url.substringAfter("://", url)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var hostPort = if (end >= 0) s.substring(0, end) else s
        hostPort = hostPort.substringAfterLast('@')
        return if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':').lowercase()
    }

    fun isLocal(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "[::1]" || host == "0.0.0.0"

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
