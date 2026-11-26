package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.HostWarmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostWarmerTest {
    private class Rig(ttl: Long = 60_000, max: Int = 64) {
        var t = 0L
        val resolved = ArrayList<List<String>>()
        val w = HostWarmer(ttl, max, { t }) { resolved += it }
    }

    @Test
    fun warmsHostOfUrlsAndBareDomains() {
        val r = Rig()
        assertTrue(r.w.warm("https://www.example.com/a/b?q=1"))
        assertTrue(r.w.warm("github.com"))
        assertTrue(r.w.warm("HTTP://Cn.Bing.com:8080/search"))
        assertEquals(listOf(listOf("www.example.com"), listOf("github.com"), listOf("cn.bing.com")), r.resolved)
    }

    @Test
    fun sameHostIsWarmedOncePerTtl() {
        val r = Rig(ttl = 1000)
        assertTrue(r.w.warm("https://a.example.com/x"))
        assertFalse(r.w.warm("https://a.example.com/y")) // 刚预热过
        r.t = 999
        assertFalse(r.w.warm("a.example.com"))
        r.t = 1000
        assertTrue(r.w.warm("a.example.com"))            // 过了 TTL 再来一次
        assertEquals(2, r.resolved.size)
    }

    @Test
    fun batchOnlyResolvesFreshHosts() {
        val r = Rig()
        r.w.warm("a.example.com")
        assertTrue(r.w.warmAll(listOf("a.example.com", "b.example.com", "b.example.com", "c.example.com")))
        assertEquals(listOf("b.example.com", "c.example.com"), r.resolved.last())
        assertFalse(r.w.warmAll(listOf("a.example.com", "c.example.com")))
    }

    @Test
    fun rememberedHostsAreBounded() {
        val r = Rig(max = 3)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { r.w.warm(it) }
        // a.com 被挤出去了：再来一次会重新预热；d.com 还记得
        assertTrue(r.w.warm("a.com"))
        assertFalse(r.w.warm("d.com"))
    }

    @Test
    fun neverWarmsAddressesThatShouldNotLeaveTheDevice() {
        for (bad in listOf(
            "", "  ", "localhost", "http://localhost:8080/", "127.0.0.1", "http://192.168.1.1/admin", "https://[::1]/",
            "printer.local", "http://nas.lan/", "router.home.arpa", "intranet", "javascript:alert(1)", "file:///etc/passwd",
            "ftp://example.com/", "two words.com", "https://exa mple.com", "data:text/html,hi",
        )) {
            assertNull("should not warm: '$bad'", HostWarmer.hostOf(bad))
        }
        val r = Rig()
        assertFalse(r.w.warm("http://192.168.0.1/"))
        assertTrue(r.resolved.isEmpty())
    }

    @Test
    fun trickyUrlsUseTheBrowsersHostNotTheTextBeforeAtSign() {
        // 反斜杠按斜杠算（Chromium 对 http/https 就是这样）：host 是 good.example，@evil.example 已经在路径里
        assertEquals("good.example", HostWarmer.hostOf("http://good.example\\@evil.example/"))
        // 真正的 userinfo：@ 前面是用户名，连的是 evil.example
        assertEquals("evil.example", HostWarmer.hostOf("https://good.example@evil.example/"))
        assertEquals("example.com", HostWarmer.hostOf("https://example.com./"))
    }

    @Test
    fun clearForgetsEverything() {
        val r = Rig()
        r.w.warm("a.example.com")
        r.w.clear()
        assertTrue(r.w.warm("a.example.com"))
    }
}
