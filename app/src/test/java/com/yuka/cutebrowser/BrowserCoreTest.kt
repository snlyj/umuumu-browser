package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.Downloads
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.filter.FilterRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserCoreTest {
    @Test
    fun webUrlFilterRejectsDangerousSchemes() {
        assertTrue(UrlUtils.isWebUrl("https://example.com/a?b=1"))
        assertTrue(UrlUtils.isWebUrl(" HTTP://example.com "))
        assertFalse(UrlUtils.isWebUrl("javascript:alert(1)"))
        assertFalse(UrlUtils.isWebUrl("file:///data/data/com.yuka.cutebrowser/files/session/session.json"))
        assertFalse(UrlUtils.isWebUrl("content://com.android.providers.downloads/x"))
        assertFalse(UrlUtils.isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(UrlUtils.isWebUrl("https://"))
        assertFalse(UrlUtils.isWebUrl(null))
    }

    @Test
    fun percentDecodeKeepsBinaryBytes() {
        // 0xFF 0x00 0x80 不是合法 UTF-8：以前走 Uri.decode().toByteArray() 会被换成 U+FFFD
        assertArrayEquals(byteArrayOf(-1, 0, -128), Downloads.percentDecode("%FF%00%80"))
        assertArrayEquals("a+b c".toByteArray(), Downloads.percentDecode("a+b%20c"))
        // 不完整/非法的转义原样保留
        assertArrayEquals("100%".toByteArray(), Downloads.percentDecode("100%"))
        assertArrayEquals("%zz".toByteArray(), Downloads.percentDecode("%zz"))
        assertArrayEquals("你好".toByteArray(), Downloads.percentDecode("%E4%BD%A0%E5%A5%BD"))
        val (mime, bytes) = Downloads.decodeDataUrl("data:application/octet-stream,%FF%00%80")!!
        assertEquals("application/octet-stream", mime)
        assertArrayEquals(byteArrayOf(-1, 0, -128), bytes)
    }

    @Test
    fun thirdPartyIsLazyButCorrect() {
        assertTrue(FilterRequest("https://cdn.ads.net/x.js", "cdn.ads.net", "news.example.com", 0).thirdParty)
        assertFalse(FilterRequest("https://img.example.com/x.png", "img.example.com", "www.example.com", 0).thirdParty)
        assertTrue(FilterRequest("https://a.com/x", "a.com", null, 0).thirdParty)
        val r = FilterRequest("https://a.com/x", "a.com", "b.com", 0)
        assertEquals(r.thirdParty, r.thirdParty)
    }

    @Test
    fun originOfNormalizesPermissionAndGeo() {
        // PermissionRequest.origin.toString() 常带末尾 /；地理位置回调是 scheme://host[:port]
        assertEquals("https://example.com", UrlUtils.originOf("https://example.com/"))
        assertEquals("https://example.com", UrlUtils.originOf("https://Example.COM/path?q=1"))
        assertEquals("https://example.com", UrlUtils.originOf("https://example.com:443"))
        assertEquals("https://example.com", UrlUtils.originOf("https://example.com:443/"))
        assertEquals("http://example.com", UrlUtils.originOf("http://example.com:80/index.html"))
        assertEquals("https://example.com:8443", UrlUtils.originOf("https://example.com:8443/"))
        assertEquals("http://example.com:8080", UrlUtils.originOf(" http://example.com:8080 "))
        // 两种写法归一之后必须相等，不然记住的权限对不上
        val fromPermission = UrlUtils.originOf("https://news.example.com/")
        val fromGeo = UrlUtils.originOf("https://news.example.com")
        assertEquals(fromPermission, fromGeo)
        assertEquals("https://[2001:db8::1]", UrlUtils.originOf("https://[2001:db8::1]:443/"))
        assertEquals(null, UrlUtils.originOf(null))
        assertEquals(null, UrlUtils.originOf(""))
        assertEquals(null, UrlUtils.originOf("https://"))
    }
}
