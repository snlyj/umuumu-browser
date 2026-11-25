package com.yuka.cutebrowser.browser

import android.net.Uri
import com.yuka.cutebrowser.data.SearchEngine

object UrlUtils {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val hostRegex = Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$")
    private val ipRegex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$")
    private val localhostRegex = Regex("^localhost(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
    /** 正文里出现的 http(s) 网址（粘贴「网址 + 换行 + 说明文字」时用） */
    private val embeddedHttp = Regex("""https?://[^\s<>"'）)」\]]+""", RegexOption.IGNORE_CASE)

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

    /**
     * 把地址栏输入转换成要打开的网址：像网址就直接打开，否则用搜索引擎搜。
     * 粘贴时常会带上换行和后面的说明（比如「登录…」）：如果第一行、或去掉换行后的内容是网址，就打开它；
     * 整段里只有一个 http(s) 链接时也打开那个链接，而不是把整段拿去搜索。
     */
    fun toUrl(input: String, engine: SearchEngine): String = runCatching {
        val text = AddressInput.sanitizeText(input).trim()
        if (text.isEmpty()) return@runCatching ""
        asDirectUrl(text)?.let { return@runCatching it }
        val firstLine = text.lineSequence().first().trim()
        if (firstLine.isNotEmpty() && firstLine != text) asDirectUrl(firstLine)?.let { return@runCatching it }
        extractSingleHttpUrl(text)?.let { asDirectUrl(it)?.let { u -> return@runCatching u } }
        engine.searchUrl(text)
    }.getOrDefault("")

    /** 一段输入本身就是一个可打开的网址（不是搜索词）时返回打开用的地址，否则 null */
    fun asDirectUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        if (schemeRegex.matches(text) || text.startsWith("about:", true) || text.startsWith("data:", true)) {
            return if (isLoadable(text)) text else null
        }
        if (localhostRegex.matches(text)) return "http://$text"
        if (hostRegex.matches(text) || ipRegex.matches(text)) return "https://$text"
        return null
    }

    /** 整段文字里恰好出现一个 http(s) 网址时返回它（去掉末尾常见标点） */
    fun extractSingleHttpUrl(text: String): String? {
        val found = embeddedHttp.findAll(text).map { trimUrlPunct(it.value) }.filter { it.isNotEmpty() }.distinct().toList()
        return found.singleOrNull()
    }

    private fun trimUrlPunct(s: String): String = s.trimEnd('.', ',', ';', ':', '!', '?', '。', '，', '、', ')', '］', '】')

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
        // 用和 HTTPS 升级同一套主机名解析（纯 Kotlin，不依赖 android.net.Uri，单元测试也能跑；反斜杠/@ 的写法也按 Chromium 的理解处理）
        return com.yuka.cutebrowser.security.HttpsUpgrade.hostOf(u).isNotBlank()
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
