package com.yuka.cutebrowser.browser

import android.webkit.WebViewClient
import com.yuka.cutebrowser.dns.DohManager

/**
 * 把 WebView 的错误码 / "net::ERR_…" 说明译成给用户看的中文，并在开着加密 DNS 时给一条建议。
 */
object NetErrors {
    data class Copy(val code: String, val title: String, val explain: String, val hint: String?)

    fun describe(errorCode: Int, description: String?, dohActive: Boolean, systemProxySet: Boolean): Copy {
        val raw = description?.toString().orEmpty()
        val code = Regex("net::[A-Z0-9_]+").find(raw)?.value ?: chromiumName(errorCode, raw)
        val title = titleFor(code, errorCode)
        val explain = explainFor(code, errorCode)
        val hint = hintFor(code, dohActive, systemProxySet)
        return Copy(code, title, explain, hint)
    }

    fun warning(url: String, errorCode: Int, description: String?, httpFallback: String?): SecurityWarning {
        val d = describe(errorCode, description, DohManager.status.value.active, com.yuka.cutebrowser.dns.SystemHttpProxy.current() != null)
        return SecurityWarning(SecurityWarning.Kind.NETWORK, url, httpFallback, d.code, d.title, d.explain, d.hint)
    }

    internal fun chromiumName(errorCode: Int, description: String): String = when {
        description.contains("net::") -> Regex("net::[A-Z0-9_]+").find(description)?.value ?: description
        errorCode == WebViewClient.ERROR_HOST_LOOKUP -> "net::ERR_NAME_NOT_RESOLVED"
        errorCode == WebViewClient.ERROR_CONNECT -> "net::ERR_CONNECTION_FAILED"
        errorCode == WebViewClient.ERROR_TIMEOUT -> "net::ERR_TIMED_OUT"
        errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "net::ERR_SSL_PROTOCOL_ERROR"
        errorCode == WebViewClient.ERROR_IO -> "net::ERR_CONNECTION_CLOSED"
        errorCode == WebViewClient.ERROR_PROXY_AUTHENTICATION -> "net::ERR_PROXY_AUTH_REQUESTED"
        errorCode == WebViewClient.ERROR_BAD_URL -> "net::ERR_INVALID_URL"
        errorCode == WebViewClient.ERROR_REDIRECT_LOOP -> "net::ERR_TOO_MANY_REDIRECTS"
        errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "net::ERR_UNKNOWN_URL_SCHEME"
        errorCode == WebViewClient.ERROR_UNSAFE_RESOURCE -> "net::ERR_BLOCKED_BY_CLIENT"
        errorCode == WebViewClient.ERROR_AUTHENTICATION -> "net::ERR_INVALID_AUTH_CREDENTIALS"
        description.isNotBlank() -> description
        else -> "ERROR_$errorCode"
    }

    private fun titleFor(code: String, errorCode: Int): String = when {
        "NAME_NOT_RESOLVED" in code || "NAME_NOT_RESOLVED" in code || errorCode == WebViewClient.ERROR_HOST_LOOKUP -> "找不到这个网站"
        "CONNECTION_CLOSED" in code || "CONNECTION_RESET" in code || "CONNECTION_REFUSED" in code || "CONNECTION_FAILED" in code || "CONNECTION_ABORTED" in code || "ADDRESS_UNREACHABLE" in code || "NETWORK_CHANGED" in code || "INTERNET_DISCONNECTED" in code || errorCode == WebViewClient.ERROR_CONNECT || errorCode == WebViewClient.ERROR_IO -> "连不上这个网站"
        "TIMED_OUT" in code || errorCode == WebViewClient.ERROR_TIMEOUT -> "连接超时了"
        "SSL" in code || "CERT" in code || errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "安全连接失败"
        "BLOCKED" in code || errorCode == WebViewClient.ERROR_UNSAFE_RESOURCE -> "这个请求被拦住了"
        "PROXY" in code -> "代理出了问题"
        "TOO_MANY_REDIRECTS" in code -> "跳转次数太多"
        "INVALID_URL" in code || "UNKNOWN_URL_SCHEME" in code -> "这个地址不太对"
        else -> "网页打不开"
    }

    private fun explainFor(code: String, errorCode: Int): String = when {
        "NAME_NOT_RESOLVED" in code || errorCode == WebViewClient.ERROR_HOST_LOOKUP ->
            "域名没有解析到地址。可能是网址写错了，或者现在的 DNS（包括加密 DNS）查不到它。"
        "CONNECTION_CLOSED" in code || "CONNECTION_RESET" in code || errorCode == WebViewClient.ERROR_IO ->
            "连接刚建立就被对方或中间的网络关掉了（$code）。有的网络会拦下部分网站；如果开了代理或 VPN，也可能是代理没把流量带出去。"
        "CONNECTION_REFUSED" in code || "CONNECTION_FAILED" in code || errorCode == WebViewClient.ERROR_CONNECT ->
            "没能连上这个网站的服务器（$code）。服务器可能关了，或者中间的网络把它拦下来了。"
        "TIMED_OUT" in code || errorCode == WebViewClient.ERROR_TIMEOUT ->
            "等了很久都没有回应。网络可能比较慢，或者到这个网站的路不通。"
        "SSL" in code || "CERT" in code || errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
            "和网站建立加密连接时失败了（$code）。"
        "BLOCKED" in code || errorCode == WebViewClient.ERROR_UNSAFE_RESOURCE ->
            "这次请求被拦下了（$code）。可能是过滤规则或浏览器的安全策略。"
        "PROXY" in code ->
            "走代理的时候出错了（$code）。"
        else ->
            "加载失败了（$code）。"
    }

    private fun hintFor(code: String, dohActive: Boolean, systemProxySet: Boolean): String? {
        if (!dohActive) return null
        return if (systemProxySet)
            "可以先关掉「App 内加密 DNS」再试一次。如果你用的是代理软件，请确认它在 VPN / 增强模式，或看看加密 DNS 是否选了能连上的服务商。"
        else
            "可以先关掉「App 内加密 DNS」再试一次。开着它的时候，系统里填的 HTTP 代理会改由唔姆唔姆转一下再出去；若你的代理软件不是 VPN 模式，关掉加密 DNS 往往就能恢复。"
    }
}
