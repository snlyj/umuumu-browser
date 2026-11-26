package com.yuka.cutebrowser.features

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.net.URLEncoder

/** 用户点了「翻译网页」：先弹窗确认，确认前什么都不发 */
@Immutable
data class TranslateAsk(val tabId: Long, val url: String, val incognito: Boolean) {
    val host: String get() = Uri.parse(url).host.orEmpty()
}

/**
 * 网页翻译：WebView 里没有本地翻译引擎，只能像别的浏览器那样把网页交给在线翻译服务打开。
 * 网址会被发给对方（对方会知道你在看哪个网页），所以只在用户每次确认后才做。
 */
object Translate {
    fun url(provider: TranslateProvider, target: TranslateTarget, pageUrl: String): String {
        val page = stripFragment(pageUrl)
        val u = URLEncoder.encode(page, "UTF-8")
        return when (provider) {
            TranslateProvider.GOOGLE -> "https://translate.google.com/translate?sl=auto&tl=${target.google}&hl=zh-CN&u=$u"
            TranslateProvider.YANDEX -> "https://translate.yandex.com/translate?url=$u&lang=${target.yandex}"
        }
    }

    /** #后面的部分本来不会发给任何服务器；拼进翻译地址就会被发出去，所以去掉 */
    fun stripFragment(url: String): String = url.substringBefore('#')

    /** 只有公网上的 http(s) 网页才能在线翻译（翻译服务访问不到你的局域网/本机，也没必要把内网地址发出去） */
    fun isTranslatable(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host.isNotEmpty() && !isPrivateHost(host)
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        if (h.contains(':')) { // IPv6
            return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd") || h == "::"
        }
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }) {
            val b = parts.map { it.toInt() }
            if (b.any { it > 255 }) return true
            return b[0] == 10 || b[0] == 127 || b[0] == 0 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                (b[0] == 100 && b[1] in 64..127)
        }
        return !h.contains('.') // 单个名字（比如 "router"、"nas"）是内网
    }

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa", ".intranet", ".corp")
}
