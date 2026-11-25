package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.AdBlocker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {
    @Test
    fun matchesHostsAndSubdomains() {
        assertTrue(AdBlocker.isBlockedHost("doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("securepubads.g.doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("hm.baidu.com"))
        assertFalse(AdBlocker.isBlockedHost("www.baidu.com"))
        assertFalse(AdBlocker.isBlockedHost("m.bilibili.com"))
        assertFalse(AdBlocker.isBlockedHost("com"))
        assertFalse(AdBlocker.isBlockedHost(null))
    }
}
