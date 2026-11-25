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
    fun genericUa() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            UserAgents.generic(wv),
        )
    }
}
