package com.yuka.cutebrowser.dns

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * 读系统 / Wi‑Fi / 代理 App 设置的 HTTP 代理。
 * App 内加密 DNS 会用 [ProxyController] 把 WebView 指到我们的本地代理，系统代理本来会被盖掉；
 * 本地代理连出去时要再经过这个地址，这样 Clash / 小飞机 的「仅 HTTP 代理」模式才不会被绕开。
 * VPN（VpnService）走的是系统收发包路径，不靠这个，始终有效。
 */
object SystemHttpProxy {
    /** 最近一次带着 Context 读到的结果，给 OkHttp 的 DoH 请求用（那边拿不到 Context） */
    @Volatile
    var lastKnown: Pair<String, Int>? = null
        private set

    fun current(context: Context? = null): Pair<String, Int>? {
        if (context != null && Build.VERSION.SDK_INT >= 23) {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val p = cm?.defaultProxy
            parse(p?.host, p?.port)?.let { lastKnown = it; return it }
        }
        parse(
            System.getProperty("https.proxyHost") ?: System.getProperty("http.proxyHost"),
            (System.getProperty("https.proxyPort") ?: System.getProperty("http.proxyPort"))?.toIntOrNull(),
        )?.let {
            if (context != null) lastKnown = it
            return it
        }
        val sel = ProxySelector.getDefault()
        if (sel != null) {
            val found = runCatching { sel.select(URI("https://example.com/")) }.getOrNull()
            if (found != null) {
                for (pr in found) {
                    if (pr.type() != Proxy.Type.HTTP) continue
                    val a = pr.address() as? InetSocketAddress ?: continue
                    parse(a.hostString, a.port)?.let {
                        if (context != null) lastKnown = it
                        return it
                    }
                }
            }
        }
        if (context != null) {
            lastKnown = null
            return null
        }
        return lastKnown
    }

    internal fun parse(host: String?, port: Int?): Pair<String, Int>? {
        val h = host?.trim().orEmpty()
        val p = port ?: 0
        if (h.isEmpty() || p !in 1..65535) return null
        return h to p
    }
}
