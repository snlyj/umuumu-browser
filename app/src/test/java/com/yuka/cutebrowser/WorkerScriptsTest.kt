package com.yuka.cutebrowser

import com.sun.net.httpserver.HttpServer
import com.yuka.cutebrowser.privacy.fingerprint.WorkerScripts
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class WorkerScriptsTest {
    private lateinit var srv: HttpServer
    private val seen = HashMap<String, Map<String, String>>()
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val base get() = "http://127.0.0.1:${srv.address.port}"

    @Before fun up() {
        srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, code: Int, type: String?, body: ByteArray, extra: Map<String, String> = emptyMap()) {
            srv.createContext(path) { ex ->
                seen[path] = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                type?.let { ex.responseHeaders.add("Content-Type", it) }
                extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        route("/w.js", 200, "text/javascript; charset=utf-8", "postMessage('喵')".toByteArray(), mapOf("Set-Cookie" to "sid=2; Path=/", "Cache-Control" to "max-age=60"))
        route("/strict.js", 200, "application/javascript", "\n// hi\n'use strict';\nx=1".toByteArray())
        route("/html", 200, "text/html", "<p>".toByteArray())
        route("/redir", 302, null, ByteArray(0), mapOf("Location" to "/w.js"))
        route("/err", 404, "text/javascript", "nope".toByteArray())
        route("/big.js", 200, "text/javascript", ByteArray(WorkerScripts.MAX_BYTES + 10) { 'a'.code.toByte() })
        srv.start()
    }

    @After fun down() { srv.stop(0) }

    @Test fun kindFromHeaders() {
        assertEquals(WorkerScripts.Kind.SERVICE, WorkerScripts.kind(mapOf("Service-Worker" to "script")))
        assertEquals(WorkerScripts.Kind.DEDICATED, WorkerScripts.kind(mapOf("sec-fetch-dest" to "worker")))
        assertEquals(WorkerScripts.Kind.SHARED, WorkerScripts.kind(mapOf("Sec-Fetch-Dest" to "SharedWorker")))
        assertEquals(WorkerScripts.Kind.SERVICE, WorkerScripts.kind(mapOf("Sec-Fetch-Dest" to "serviceworker")))
        assertNull(WorkerScripts.kind(mapOf("Sec-Fetch-Dest" to "script", "Accept" to "*/*")))
        assertNull(WorkerScripts.kind(emptyMap()))
        assertNull(WorkerScripts.kind(null))
    }

    @Test fun prefixKeepsDirectiveAndHashbang() {
        assertEquals("P\nx=1", WorkerScripts.prefixed("x=1", "P"))
        assertEquals("'use strict';P\n'use strict';x", WorkerScripts.prefixed("'use strict';x", "P"))
        assertEquals("'use strict';P\n/* c */\n\"use strict\"\nx", WorkerScripts.prefixed("/* c */\n\"use strict\"\nx", "P"))
        assertEquals("P\n//usr/bin/env node\nx", WorkerScripts.prefixed("#!/usr/bin/env node\nx", "P"))
        // 不在开头的 'use strict' 不是指令
        assertEquals("P\nx=1;'use strict'", WorkerScripts.prefixed("x=1;'use strict'", "P"))
    }

    @Test fun fetchPrefixesAndPassesCookieButNotValidators() {
        val r = WorkerScripts.fetch(client, "$base/w.js",
            mapOf("User-Agent" to "UA", "If-None-Match" to "\"x\"", "Accept-Encoding" to "br", "Cookie" to "page=leak", "Service-Worker" to "script"),
            "sid=1", "/*SHIELD*/")
        assertNotNull(r)
        assertEquals(200, r!!.status)
        assertEquals("/*SHIELD*/\npostMessage('喵')", String(r.body, Charsets.UTF_8))
        assertEquals(listOf("sid=2; Path=/"), r.setCookies)
        assertTrue(r.headers.keys.none { it.equals("Content-Length", true) || it.equals("Set-Cookie", true) })
        assertEquals("max-age=60", r.headers.entries.first { it.key.equals("Cache-Control", true) }.value)
        val h = seen["/w.js"]!!
        assertEquals("sid=1", h["cookie"]) // 用的是 CookieManager 给的，不是请求头里的
        assertEquals("UA", h["user-agent"])
        assertFalse(h.containsKey("if-none-match")) // 不能拿到 304：我们要完整脚本
        assertEquals("script", h["service-worker"])
    }

    @Test fun strictWorkerScriptKeepsStrict() {
        val r = WorkerScripts.fetch(client, "$base/strict.js", null, null, "P")!!
        assertTrue(String(r.body).startsWith("'use strict';P\n"))
    }

    @Test fun leavesEverythingElseToWebView() {
        assertNull(WorkerScripts.fetch(client, "$base/html", null, null, "P"))
        assertNull(WorkerScripts.fetch(client, "$base/redir", null, null, "P"))
        assertNull(WorkerScripts.fetch(client, "$base/err", null, null, "P"))
        assertNull(WorkerScripts.fetch(client, "$base/big.js", null, null, "P"))
        assertNull(WorkerScripts.fetch(client, "http://127.0.0.1:1/x.js", null, null, "P"))
        assertNull(WorkerScripts.fetch(client, "not a url", null, null, "P"))
    }
}
