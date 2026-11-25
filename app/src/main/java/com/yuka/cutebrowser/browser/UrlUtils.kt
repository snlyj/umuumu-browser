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

    /** 只允许网页链接（别的 App 通过 ACTION_VIEW 传进来的地址只收 http/https） */
    fun isWebUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
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

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
