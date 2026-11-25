package com.yuka.cutebrowser.browser

import android.net.Uri
import com.yuka.cutebrowser.data.SearchEngine

object UrlUtils {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val hostRegex = Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$")
    private val ipRegex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$")

    /** 把地址栏输入转换成要打开的网址：像网址就直接打开，否则用搜索引擎搜。 */
    fun toUrl(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.contains(' ')) return engine.searchUrl(text)
        if (schemeRegex.matches(text) || text.startsWith("about:") || text.startsWith("data:")) return text
        if (text.startsWith("localhost")) return "http://$text"
        if (hostRegex.matches(text) || ipRegex.matches(text)) return "https://$text"
        return engine.searchUrl(text)
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
