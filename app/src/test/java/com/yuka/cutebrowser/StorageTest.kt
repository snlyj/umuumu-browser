package com.yuka.cutebrowser

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.Downloads
import com.yuka.cutebrowser.browser.SessionStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageTest {
    @Test
    fun dataUrls() {
        val (m1, b1) = Downloads.decodeDataUrl("data:text/plain;base64,SGVsbG8=")!!
        assertEquals("text/plain", m1); assertArrayEquals("Hello".toByteArray(), b1)
        val (m2, b2) = Downloads.decodeDataUrl("data:,a%20b")!!
        assertEquals("text/plain", m2); assertArrayEquals("a b".toByteArray(), b2)
        assertNull(Downloads.decodeDataUrl("data:nocomma"))
        assertTrue(Downloads.fileName("data:image/png;base64,xx", null, "image/png").endsWith(".png"))
    }

    @Test
    fun sessionRoundTrip() {
        val store = SessionStore(ApplicationProvider.getApplicationContext())
        val state = Bundle().apply { putByteArray("WEBVIEW_CHROMIUM_STATE", byteArrayOf(1, 2, 3)) }
        store.write(
            SessionStore.Session(listOf(SessionStore.SavedTab(7, "https://a.com", "A", false), SessionStore.SavedTab(8, "", "新标签页", true)), 1),
            mapOf(7L to state), emptySet(),
        )
        val s = store.load()!!
        assertEquals(1, s.active); assertEquals(2, s.tabs.size); assertEquals("https://a.com", s.tabs[0].url)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.readState(7)!!.getByteArray("WEBVIEW_CHROMIUM_STATE"))
        assertNull(store.readState(8))
    }

    @Test
    fun bundledBlocklist() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AdBlocker.resetForTest() // 别的测试里 MainActivity 可能已经加载过
        AdBlocker.load(ctx, "test")
        assertTrue(AdBlocker.loadedCount > 100_000)
        assertTrue(AdBlocker.shouldBlock("https://ads.youtube.com/x.js", "ads.youtube.com", "m.youtube.com", null))
        assertTrue(!AdBlocker.shouldBlock("https://m.youtube.com/s/player.js", "m.youtube.com", "m.youtube.com", null))
        // 第二次从磁盘缓存读
        assertTrue(java.io.File(ctx.filesDir, "filters/compiled-test.bin").length() > 1_000_000)
        val e2 = java.io.DataInputStream(java.io.File(ctx.filesDir, "filters/compiled-test.bin").inputStream().buffered()).use {
            com.yuka.cutebrowser.filter.FilterEngine.readFrom(it)
        }
        assertTrue(e2 != null && e2.networkRuleCount == AdBlocker.engine!!.networkRuleCount)
    }
}
