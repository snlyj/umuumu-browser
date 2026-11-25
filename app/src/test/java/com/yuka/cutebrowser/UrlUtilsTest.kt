package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun parsesInput() {
        assertEquals("https://bilibili.com", UrlUtils.toUrl("bilibili.com", SearchEngine.BING))
        assertEquals("https://www.zhihu.com/question/1", UrlUtils.toUrl(" www.zhihu.com/question/1 ", SearchEngine.BING))
        assertEquals("http://example.com", UrlUtils.toUrl("http://example.com", SearchEngine.BING))
        assertEquals("https://192.168.1.1:8080", UrlUtils.toUrl("192.168.1.1:8080", SearchEngine.BING))
        assertEquals("https://www.baidu.com/s?wd=%E5%B0%8F%E7%8C%AB", UrlUtils.toUrl("小猫", SearchEngine.BAIDU))
        assertEquals("https://cn.bing.com/search?q=cute+cat", UrlUtils.toUrl("cute cat", SearchEngine.BING))
        assertEquals("https://www.google.com/search?q=kotlin", UrlUtils.toUrl("kotlin", SearchEngine.GOOGLE))
    }

    @Test
    fun nonWebSchemesNeverLoadDirectly() {
        val e = SearchEngine.BING
        // 这些以前会原样交给 WebView（javascript:// 会在当前页面里执行）
        for (bad in listOf("javascript://%0aalert(1)", "JavaScript://x%0Aalert(1)", "file:///data/data/x", "content://a/b", "intent://x#Intent;end", "view-source://a.com")) {
            assertTrue(bad, UrlUtils.toUrl(bad, e).startsWith("https://cn.bing.com/search?q="))
        }
        assertEquals("data:text/html,hi", UrlUtils.toUrl("data:text/html,hi", e))
        assertEquals("about:blank", UrlUtils.toUrl("about:blank", e))
        assertTrue(UrlUtils.toUrl("about:config", e).startsWith("https://cn.bing.com/search"))
        assertFalse(UrlUtils.isLoadable("javascript:alert(1)"))
        assertFalse(UrlUtils.isLoadable(" file:///etc/hosts"))
        assertTrue(UrlUtils.isLoadable("HTTPS://a.com"))
        assertTrue(UrlUtils.isWebUrl("http://a.com") && UrlUtils.isWebUrl(" https://a.com") && !UrlUtils.isWebUrl("javascript:1") && !UrlUtils.isWebUrl(null))
    }

    @Test
    fun localhostOnlyForRealLocalhost() {
        val e = SearchEngine.BING
        assertEquals("http://localhost:3000/x", UrlUtils.toUrl("localhost:3000/x", e))
        assertEquals("http://localhost", UrlUtils.toUrl("localhost", e))
        // 以前 startsWith("localhost") 会把 localhostevil.com 当成 http://localhostevil.com（明文）
        assertEquals("https://localhostevil.com", UrlUtils.toUrl("localhostevil.com", e))
    }
}
