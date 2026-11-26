package com.yuka.cutebrowser.privacy.fingerprint

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * http(s) 的 Worker / SharedWorker / ServiceWorker 脚本：网页里的 document-start 脚本进不去 Worker，
 * 所以在网络层把同一份防护脚本拼到 Worker 脚本最前面（blob:/data: 的 Worker 由 fingerprint.js 自己在页面里处理）。
 *
 * 只有能从请求头认出"这是 Worker 主脚本"时才处理：
 *  - `Service-Worker: script`（规范要求浏览器给 ServiceWorker 脚本请求加这个头）
 *  - `Sec-Fetch-Dest: worker / sharedworker / serviceworker`（WebView 交给 App 的请求头里不一定有）
 * 认不出来就不碰（返回 null，WebView 照常自己加载）。纯 JVM 逻辑，Android 那层见 BrowserController。
 */
object WorkerScripts {
    enum class Kind { DEDICATED, SHARED, SERVICE }

    /** 单个 Worker 脚本最多处理这么大；更大的照原样交给 WebView */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun kind(headers: Map<String, String>?): Kind? {
        if (headers.isNullOrEmpty()) return null
        var sw: String? = null
        var dest: String? = null
        for ((k, v) in headers) {
            when (k.lowercase(Locale.ROOT)) {
                "service-worker" -> sw = v.trim().lowercase(Locale.ROOT)
                "sec-fetch-dest" -> dest = v.trim().lowercase(Locale.ROOT)
            }
        }
        if (sw == "script") return Kind.SERVICE
        return when (dest) {
            "worker" -> Kind.DEDICATED
            "sharedworker" -> Kind.SHARED
            "serviceworker" -> Kind.SERVICE
            else -> null
        }
    }

    private val STRICT = Regex("^(?:\\s|//[^\\n]*\\n|/\\*[\\s\\S]*?\\*/)*(['\"])use strict\\1")

    /** 和 fingerprint.js 的 withShield 一样：保留 'use strict' 指令和 #! 行 */
    fun prefixed(code: String, prefix: String): String {
        var c = code
        if (c.startsWith("#!")) c = "//" + c.substring(2)
        val strict = STRICT.containsMatchIn(c.take(4096))
        return (if (strict) "'use strict';" else "") + prefix + "\n" + c
    }

    /** 处理好的响应（Android 层再包成 WebResourceResponse） */
    class Result(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray, val setCookies: List<String>)

    private val DROP_REQ = setOf(
        "host", "connection", "accept-encoding", "cookie", "content-length", "range", "if-none-match", "if-modified-since",
        "if-match", "if-unmodified-since", "if-range", "proxy-authorization", "proxy-connection", "upgrade", "te", "keep-alive",
    )
    private val DROP_RESP = setOf("content-length", "content-encoding", "transfer-encoding", "connection", "set-cookie", "keep-alive", "content-type")

    /**
     * 自己把脚本取回来，拼上 [prefix]。出任何问题（跳转、非 2xx、不是 JS、太大、网络错误）都返回 null，让 WebView 自己加载
     * （那样这个 Worker 没有防护，但网站照常能用）。
     */
    fun fetch(client: OkHttpClient, url: String, reqHeaders: Map<String, String>?, cookie: String?, prefix: String): Result? = try {
        val rb = Request.Builder().url(url).get()
        reqHeaders?.forEach { (k, v) -> if (k.lowercase(Locale.ROOT) !in DROP_REQ) rb.header(k, v) }
        if (!cookie.isNullOrEmpty()) rb.header("Cookie", cookie)
        client.newCall(rb.build()).execute().use { resp ->
            if (resp.code !in 200..299) return null
            val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            if (!(ct.contains("javascript") || ct.contains("ecmascript"))) return null
            val body = resp.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val src = body.source()
            if (src.request(MAX_BYTES + 1L)) return null // 超过上限
            val bytes = src.buffer.readByteArray()
            val text = String(bytes, Charsets.UTF_8) // Worker 脚本规范上一律按 UTF-8 解码
            val out = prefixed(text, prefix).toByteArray(Charsets.UTF_8)
            val hs = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) if (name.lowercase(Locale.ROOT) !in DROP_RESP) hs[name] = resp.headers.values(name).joinToString(", ")
            Result(resp.code, resp.message.ifBlank { "OK" }, hs, out, resp.headers("Set-Cookie"))
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
