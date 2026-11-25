package com.yuka.cutebrowser

import com.yuka.cutebrowser.security.HttpsUpgrade
import com.yuka.cutebrowser.security.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityUtilsTest {
    @Test
    fun upgradesHttp() {
        assertEquals("https://example.com/a?b=1", HttpsUpgrade.upgrade("http://example.com/a?b=1", emptySet()))
        assertEquals("https://Example.com/", HttpsUpgrade.upgrade("HTTP://Example.com/", emptySet()))
        assertEquals("https://example.com/x", HttpsUpgrade.upgrade("http://example.com:80/x", emptySet()))
        assertEquals("https://example.com:8080/x", HttpsUpgrade.upgrade("http://example.com:8080/x", emptySet()))
        assertNull(HttpsUpgrade.upgrade("https://example.com/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://localhost:3000/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://127.0.0.1/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://old.site/", setOf("old.site")))
        assertNull(HttpsUpgrade.upgrade("about:blank", emptySet()))
        assertEquals("10.0.2.2", HttpsUpgrade.hostOf("http://user@10.0.2.2:8000/x"))
    }

    @Test
    fun localExemptionIsNotFooledByLookalikeHosts() {
        // 以前 startsWith("127.") 会把 127.evil.com 当成本机，http 不升级（HTTPS-only 被绕过）
        assertEquals("https://127.0.0.1.evil.com/", HttpsUpgrade.upgrade("http://127.0.0.1.evil.com/", emptySet()))
        assertEquals("https://127.evil.com/", HttpsUpgrade.upgrade("http://127.evil.com/", emptySet()))
        assertEquals("https://localhost.evil.com/", HttpsUpgrade.upgrade("http://localhost.evil.com/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://127.0.0.1:8080/x", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://127.1.2.3/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://dev.localhost/", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://localhost./", emptySet()))
        assertNull(HttpsUpgrade.upgrade("http://[::1]:3000/", emptySet()))
        assertEquals("https://127.0.0.256/", HttpsUpgrade.upgrade("http://127.0.0.256/", emptySet()))
    }

    @Test
    fun hostOfFollowsBrowserParsing() {
        // `\` 在浏览器里等于 `/`：这个地址实际连的是 evil.com，不是 localhost
        assertEquals("evil.com", HttpsUpgrade.hostOf("http://evil.com\\@localhost/"))
        assertEquals("https://evil.com\\@localhost/", HttpsUpgrade.upgrade("http://evil.com\\@localhost/", emptySet()))
        assertEquals("evil.com", HttpsUpgrade.hostOf("http://evil.com/@localhost"))
        assertEquals("evil.com", HttpsUpgrade.hostOf("http://ev\til.com/"))
        assertEquals("[::1]", HttpsUpgrade.hostOf("http://[::1]:8080/a"))
        assertEquals("a.com", HttpsUpgrade.hostOf("http://u:p@A.com:81?x#y"))
    }

    @Test
    fun genericUa() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            UserAgents.generic(wv),
        )
    }
}
