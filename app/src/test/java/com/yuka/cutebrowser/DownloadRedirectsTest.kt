package com.yuka.cutebrowser

import com.sun.net.httpserver.HttpServer
import com.yuka.cutebrowser.browser.DownloadRedirects
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** DownloadManager 之前先 HEAD 走一遍跳转：每一跳只带自己的 Cookie */
class DownloadRedirectsTest {
    private lateinit var a: HttpServer
    private lateinit var b: HttpServer
    private val cookiesSeen = ArrayList<Pair<String, String?>>()
    private val methods = ArrayList<String>()
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    private fun server(host: String, routes: Map<String, Pair<Int, String?>>): HttpServer {
        val s = HttpServer.create(InetSocketAddress(host, 0), 0)
        routes.forEach { (path, r) ->
            s.createContext(path) { ex ->
                synchronized(cookiesSeen) { cookiesSeen += "$host$path" to ex.requestHeaders.getFirst("Cookie"); methods += ex.requestMethod }
                r.second?.let { ex.responseHeaders.add("Location", it) }
                ex.responseHeaders.add("Set-Cookie", "hop=$path")
                ex.sendResponseHeaders(r.first, -1)
                ex.close()
            }
        }
        s.start()
        return s
    }

    @Before fun up() {
        b = server("127.0.0.2", mapOf("/file.zip" to (200 to null)))
        val bUrl = "http://127.0.0.2:${b.address.port}/file.zip"
        a = server("127.0.0.1", mapOf(
            "/direct.zip" to (200 to null),
            "/same" to (302 to "/direct.zip"),
            "/cross" to (302 to bUrl),
            "/loop" to (302 to "/loop"),
            "/forbidden" to (403 to null),
        ))
    }

    @After fun down() { a.stop(0); b.stop(0) }

    private val jar = mapOf("127.0.0.1" to "session=SECRET", "127.0.0.2" to "cdn=ok")
    private fun cookieFor(u: String) = jar[u.substringAfter("://").substringBefore(':')]

    private fun urlA(p: String) = "http://127.0.0.1:${a.address.port}$p"

    @Test fun noRedirectKeepsUrl() {
        val r = DownloadRedirects.resolve(client, urlA("/direct.zip"), "UA", ::cookieFor)!!
        assertEquals(urlA("/direct.zip"), r.finalUrl)
        assertFalse(r.redirected)
        assertEquals(listOf("HEAD"), methods) // 只发 HEAD，不下载内容
    }

    @Test fun crossSiteRedirectDoesNotCarryCookie() {
        val setCookies = ArrayList<String>()
        val r = DownloadRedirects.resolve(client, urlA("/cross"), "UA", ::cookieFor, { _, h -> setCookies += h })!!
        assertTrue(r.redirected)
        assertTrue(r.crossSite)
        assertEquals("http://127.0.0.2:${b.address.port}/file.zip", r.finalUrl)
        assertEquals("session=SECRET", cookiesSeen.first { it.first.startsWith("127.0.0.1") }.second)
        assertEquals("cdn=ok", cookiesSeen.first { it.first.startsWith("127.0.0.2") }.second) // 第二跳只带自己的
        assertEquals(listOf("hop=/cross", "hop=/file.zip"), setCookies)
    }

    @Test fun sameSiteRedirect() {
        val r = DownloadRedirects.resolve(client, urlA("/same"), null, ::cookieFor)!!
        assertEquals(urlA("/direct.zip"), r.finalUrl)
        assertTrue(r.redirected)
        assertFalse(r.crossSite)
    }

    @Test fun errorsAndLoops() {
        assertEquals(urlA("/forbidden"), DownloadRedirects.resolve(client, urlA("/forbidden"), null, ::cookieFor)!!.finalUrl)
        assertNull(DownloadRedirects.resolve(client, urlA("/loop"), null, ::cookieFor))
        assertNull(DownloadRedirects.resolve(client, "http://127.0.0.1:1/x", null, ::cookieFor))
        assertNull(DownloadRedirects.resolve(client, "::bad", null, ::cookieFor))
    }
}
