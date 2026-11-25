package com.yuka.cutebrowser

import android.webkit.WebViewClient
import com.yuka.cutebrowser.browser.NetErrors
import com.yuka.cutebrowser.dns.SystemHttpProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetErrorsTest {
    @Test
    fun labelsCommonChromiumCodes() {
        val closed = NetErrors.describe(WebViewClient.ERROR_IO, "net::ERR_CONNECTION_CLOSED", dohActive = false, systemProxySet = false)
        assertEquals("net::ERR_CONNECTION_CLOSED", closed.code)
        assertTrue(closed.title.contains("连不上"))
        assertTrue(closed.explain.contains("ERR_CONNECTION_CLOSED"))
        assertNull(closed.hint)

        val dns = NetErrors.describe(WebViewClient.ERROR_HOST_LOOKUP, null, false, false)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", dns.code)
        assertTrue(dns.title.contains("找不到"))

        val blocked = NetErrors.describe(WebViewClient.ERROR_UNSAFE_RESOURCE, "net::ERR_BLOCKED_BY_CLIENT", false, false)
        assertEquals("net::ERR_BLOCKED_BY_CLIENT", blocked.code)
        assertTrue(blocked.explain.contains("拦"))
    }

    @Test
    fun hintMentionsEncryptedDnsWhenActive() {
        val d = NetErrors.describe(WebViewClient.ERROR_IO, "net::ERR_CONNECTION_CLOSED", dohActive = true, systemProxySet = false)
        assertNotNull(d.hint)
        assertTrue(d.hint!!, d.hint!!.contains("加密 DNS"))
    }

    @Test
    fun systemHttpProxyParse() {
        assertNull(SystemHttpProxy.parse(null, 8080))
        assertNull(SystemHttpProxy.parse("127.0.0.1", 0))
        assertEquals("127.0.0.1" to 7890, SystemHttpProxy.parse(" 127.0.0.1 ", 7890))
    }
}
