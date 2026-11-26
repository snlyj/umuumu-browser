package com.yuka.cutebrowser.net

import com.yuka.cutebrowser.dns.DohManager
import com.yuka.cutebrowser.dns.SystemHttpProxy
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * App 自己发的少量网页请求（Worker 脚本、下载前确认跳转）用的 HTTP 客户端。
 * 和 WebView 走同一条路：开着加密 DNS 时经过本地 DoH 代理（它再接系统代理），否则走系统 HTTP 代理。
 * 不自动跟随跳转：调用方要自己看每一跳（Cookie 不能跟着跨站跳转走）。只在用到时创建。
 */
object AppHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val st = DohManager.status.value
                    if (st.active && st.port > 0) return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", st.port)))
                    val p = SystemHttpProxy.current() ?: return listOf(Proxy.NO_PROXY)
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(p.first, p.second)))
                }
                override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {}
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES) // Cookie 由调用方按 WebView 的 CookieManager 显式加
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
