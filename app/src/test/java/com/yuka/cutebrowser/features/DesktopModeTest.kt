package com.yuka.cutebrowser.features

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopModeTest {
    private var h: ControllerHarness? = null

    @After
    fun tearDown() { h?.close(); h = null }

    @Test
    fun desktopUaKeepsOnlyChromeMajor() {
        val webview = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"
        val ua = DesktopMode.userAgent(webview)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", ua)
        assertFalse(ua.contains("Mobile"))
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("Pixel"))
        assertTrue(DesktopMode.userAgent(null).contains("Chrome/"))
    }

    @Test
    fun viewportScriptOnlyTouchesTopFrameViewport() {
        val s = DesktopMode.viewportScript
        assertTrue(s.contains("window.top!==window"))
        assertTrue(s.contains("width=${DesktopMode.VIEWPORT_WIDTH}"))
        assertTrue(s.contains("disconnect"))
    }

    @Test
    fun toggleIsPerTabAndRestoresMobileUa() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage()
        val tab = c.current
        val v = tab.webView!!
        val mobileUa = v.settings.userAgentString

        c.features.toggleDesktop()
        assertTrue(tab.desktop)
        assertTrue(v.settings.userAgentString.contains("X11; Linux x86_64"))
        assertTrue(v.settings.useWideViewPort)
        assertTrue(v.settings.loadWithOverviewMode)

        // 另一个标签不受影响
        c.newTab("https://example.org/")
        c.webViewFor(c.current)
        assertFalse(c.current.desktop)
        assertFalse(c.current.webView!!.settings.userAgentString.contains("X11"))

        c.switchTo(c.tabs.indexOf(tab))
        c.features.toggleDesktop()
        assertFalse(tab.desktop)
        assertEquals(mobileUa, v.settings.userAgentString)
    }

    @Test
    fun desktopSurvivesWebViewRecreation() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage()
        val tab = c.current
        c.features.toggleDesktop()
        // 模拟被回收后重建：新 WebView 也要是桌面 UA
        c.onLowMemory()
        tab.webView = null
        val v2 = c.webViewFor(tab)
        assertTrue(v2.settings.userAgentString.contains("X11; Linux x86_64"))
    }

    @Test
    fun homeTabCannotToggle() {
        val hh = ControllerHarness().also { h = it }
        hh.controller.features.toggleDesktop()
        assertFalse(hh.controller.current.desktop)
    }
}
