package com.yuka.cutebrowser.features

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderFlowTest {
    private var h: ControllerHarness? = null

    @After
    fun tearDown() { h?.close(); h = null }

    @Test
    fun readerNeedsAPage() {
        val hh = ControllerHarness().also { h = it }
        hh.controller.features.openReader()
        assertNull(hh.controller.features.reader)
    }

    @Test
    fun readerOpensForCurrentTabAndClosesOnTabSwitchOrNavigation() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage("https://example.com/post")
        val tab = c.current
        c.features.openReader()
        val s = c.features.reader
        assertNotNull(s)
        assertEquals(tab.id, s!!.tabId)
        assertNull(s.article) // 正在整理（Robolectric 的 WebView 不会真的跑 JS）

        c.newTab()
        assertNull("换标签后阅读模式关掉", c.features.reader)

        c.switchTo(c.tabs.indexOf(tab))
        c.features.openReader()
        assertNotNull(c.features.reader)
        c.features.onPageStarted(tab)
        assertNull("网页跳走后阅读模式关掉", c.features.reader)
        assertFalse(tab.readerable)
    }

    @Test
    fun readerOpenClosesFind() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage()
        c.features.openFind()
        assertTrue(c.features.find.open)
        c.features.openReader()
        assertFalse(c.features.find.open)
    }

    @Test
    fun readerViewIsLockedDown() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage()
        val v = c.features.createReaderView(ReaderSession(c.current.id, c.current.url, false))
        assertFalse(v.settings.javaScriptEnabled)
        assertFalse(v.settings.allowFileAccess)
        assertFalse(v.settings.allowContentAccess)
        assertFalse(v.settings.domStorageEnabled)
        c.features.disposeReaderView(v)
    }

    @Test
    fun prefsPersist() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = FeaturePrefs(ctx)
        a.ensureLoaded()
        a.updateReaderStyle(ReaderStyle(zoom = 130, lineHeight = 2.1f, theme = ReaderTheme.NIGHT, font = ReaderFont.SERIF))
        a.translateProvider = TranslateProvider.YANDEX
        val b = FeaturePrefs(ctx)
        b.ensureLoaded()
        assertEquals(130, b.readerStyle.zoom)
        assertEquals(ReaderTheme.NIGHT, b.readerStyle.theme)
        assertEquals(ReaderFont.SERIF, b.readerStyle.font)
        assertEquals(TranslateProvider.YANDEX, b.translateProvider)
    }
}
