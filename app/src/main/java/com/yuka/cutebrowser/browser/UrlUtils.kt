package com.yuka.cutebrowser.browser

import android.net.Uri
import com.yuka.cutebrowser.data.SearchEngine

object UrlUtils {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val hostRegex = Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$")
    private val ipRegex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$")
    private val localhostRegex = Regex("^localhost(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)

    /** 地址栏 / 外部 App / 书签 能让 WebView 直接加载的协议。javascript: file: content: intent: 等一律不行 */
    fun isLoadable(url: String): Boolean {
        val scheme = url.trim().substringBefore(':', "").lowercase()
        return when (scheme) {
            "http", "https" -> true
            "data" -> true
            "about" -> url.trim().equals("about:blank", ignoreCase = true)
            else -> false
        }
    }

    /** 把地址栏输入转换成要打开的网址：像网址就直接打开，否则用搜索引擎搜。 */
    fun toUrl(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.contains(' ')) return engine.searchUrl(text)
        if (schemeRegex.matches(text) || text.startsWith("about:", true) || text.startsWith("data:", true)) {
            // `javascript://%0aalert(1)`、file://、content://、intent:// 这类不是网页：当成搜索词，不执行也不加载
            return if (isLoadable(text)) text else engine.searchUrl(text)
        }
        if (localhostRegex.matches(text)) return "http://$text"
        if (hostRegex.matches(text) || ipRegex.matches(text)) return "https://$text"
        return engine.searchUrl(text)
    }

    /**
     * 网站来源（scheme://host[:port]），去掉路径和末尾斜杠。
     * 默认端口（http 80 / https 443）不写出来，这样 PermissionRequest 和地理位置回调两种写法能对上。
     * 对不上就记不成同一条网站权限。
     */
    fun originOf(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val u = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        var host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in host && !host.startsWith("[")) host = "[$host]"
        val port = u.port
        val def = when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
        return if (port != -1 && port != def) "$scheme://$host:$port" else "$scheme://$host"
    }

    /** 只放行带主机名的 http(s) 网址（外部 App 传来的链接、intent 的 fallback 地址都用它过滤，挡掉 javascript: / file: / content: 等） */
    fun isWebUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return false
        return runCatching { !Uri.parse(u).host.isNullOrBlank() }.getOrDefault(false)
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
