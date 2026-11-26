package com.yuka.cutebrowser.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 系统 DownloadManager 会自己跟随跳转，而且把我们加的 Cookie 头原样带到每一跳——包括跳到别的网站
 * （比如 网盘 → CDN/第三方），等于把这个网站的登录 Cookie 送给了别人。
 *
 * 所以交给 DownloadManager 之前，先用 HEAD 把跳转走一遍（HEAD 不下载内容，按 HTTP 语义也不会消耗一次性下载链接），
 * 每一跳只带"那一跳自己的" Cookie：
 *  - 没有跳转：照旧下载原地址 + 它自己的 Cookie；
 *  - 有跳转：直接下载最终地址，只带最终地址自己的 Cookie；
 *  - HEAD 走不通（网络错误）：返回 null，调用方不带 Cookie 下载（宁可要重新登录，也不把 Cookie 交出去）。
 */
object DownloadRedirects {
    class Result(val finalUrl: String, val redirected: Boolean, val crossSite: Boolean)

    fun resolve(
        client: OkHttpClient,
        url: String,
        userAgent: String?,
        cookieFor: (String) -> String?,
        onSetCookie: (url: String, header: String) -> Unit = { _, _ -> },
        maxHops: Int = 10,
    ): Result? {
        var cur = url.toHttpUrlOrNull() ?: return null
        val startSite = com.yuka.cutebrowser.privacy.fingerprint.FpSeed.site(cur.host)
        var crossSite = false
        for (hop in 0..maxHops) {
            val rb = Request.Builder().url(cur).head()
            userAgent?.let { rb.header("User-Agent", it) }
            cookieFor(cur.toString())?.takeIf { it.isNotEmpty() }?.let { rb.header("Cookie", it) }
            val next = try {
                client.newCall(rb.build()).execute().use { resp ->
                    resp.headers("Set-Cookie").forEach { onSetCookie(cur.toString(), it) }
                    if (resp.code in 300..399 && resp.code != 304) resp.header("Location")?.let { cur.resolve(it) } else null
                }
            } catch (e: IOException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (next == null) return Result(cur.toString(), hop > 0, crossSite)
            // https → http 的降级跳转：不跟（只走 HTTPS 时 WebView 本来也不会接受）
            if (cur.isHttps && !next.isHttps) return null
            if (com.yuka.cutebrowser.privacy.fingerprint.FpSeed.site(next.host) != startSite) crossSite = true
            cur = next
        }
        return null // 跳转太多
    }
}
