package com.yuka.cutebrowser

import android.content.ComponentCallbacks2
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.perf.StartupMarks
import com.yuka.cutebrowser.perf.WebViewWarmup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfBehaviorTest {
    private var ac: ActivityController<ComponentActivity>? = null
    private var controller: BrowserController? = null

    private fun controller(): BrowserController {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        ac = a
        return BrowserController(a.get(), CuteRepository(a.get()), {}, { false }, { _, _ -> }).also { controller = it }
    }

    @After
    fun tearDown() {
        controller?.destroyAll(); controller?.releaseGlobals()
        runCatching { ac?.pause()?.stop()?.destroy() }
        controller = null; ac = null
        StartupMarks.resetForTest(); AdBlocker.resetForTest(); WebViewWarmup.resetForTest()
    }

    /** 跑完主线程上排着的任务；再塞一个空任务，让 Robolectric 的 Looper 在队列走空时触发 IdleHandler（真机上它自己会触发） */
    private fun idle() {
        val looper = android.os.Looper.getMainLooper()
        shadowOf(looper).idle()
        android.os.Handler(looper).post { }
        shadowOf(looper).idle()
    }

    // ---------- 启动 ----------

    @Test
    fun startupMarksKeepTheFirstTimeAndOrder() {
        StartupMarks.resetForTest()
        StartupMarks.mark("a", now = 1_000)
        StartupMarks.mark("b", now = 1_050)
        StartupMarks.mark("a", now = 9_999) // Activity 重建：不能覆盖冷启动那次
        val s = StartupMarks.snapshot().filter { it.first == "a" || it.first == "b" } // 别的测试的后台线程也可能在记
        assertEquals(listOf("a", "b"), s.map { it.first })
        assertEquals(50L, s[1].second - s[0].second)
        assertTrue(StartupMarks.summary().contains("a ") && StartupMarks.summary().contains("b "))
    }

    @Test
    fun filterPreloadRunsOnceAndPublishesEngine() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "filters").deleteRecursively()
        AdBlocker.resetForTest()
        AdBlocker.preload(ctx)
        AdBlocker.preload(ctx) // 第二次是空操作，不会再起一个加载线程
        waitFor { AdBlocker.engine != null }
        assertNotNull(AdBlocker.engine)
        assertTrue("compiled rules: ${AdBlocker.loadedCount} net=${AdBlocker.engine?.networkRuleCount} info=${AdBlocker.loadInfo}", (AdBlocker.engine?.networkRuleCount ?: 0) > 50_000)
        val first = AdBlocker.loadInfo
        assertTrue(first, first.startsWith("编译"))
        val files = File(ctx.filesDir, "filters").listFiles().orEmpty().map { it.name }
        assertTrue("cache files: $files logs=${org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "UmuFilter" }.map { it.msg + (it.throwable ?: "") }}", files.any { it.startsWith("compiled-") && it.endsWith(".bin") })

        // 第二次冷启动：读缓存，并且规则一样多
        val count = AdBlocker.loadedCount
        AdBlocker.resetForTest()
        AdBlocker.preload(ctx)
        waitFor { AdBlocker.engine != null && AdBlocker.loadInfo.startsWith("读缓存") }
        assertEquals(count, AdBlocker.loadedCount)
        assertTrue(AdBlocker.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "securepubads.g.doubleclick.net", "www.example.org", null))
    }

    @Test
    fun cacheKeyIsStableForOneInstall() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(AdBlocker.cacheKey(ctx), AdBlocker.cacheKey(ctx))
    }

    // ---------- 内存 ----------

    private fun openLiveTabs(c: BrowserController, n: Int) {
        repeat(n) { i ->
            c.newTab("https://t$i.example.com/"); c.current.isHome = false
            c.webViewFor(c.current)
            idle()
        }
    }

    @Test
    fun liveWebViewsAreCappedAtThree() {
        val c = controller()
        openLiveTabs(c, 5)
        assertTrue("live=${c.liveWebViewCount}", c.liveWebViewCount <= 3)
    }

    @Test
    fun trimMemoryEvictsInLevels() {
        val c = controller()
        openLiveTabs(c, 3)
        c.prewarm(); idle()
        assertTrue(c.liveWebViewCount >= 2)
        c.tabs.forEach { it.thumb = ImageBitmap(4, 4) }

        // 切到后台：丢备用 WebView、缩略图只留当前页；标签的 WebView 不动
        val liveBefore = c.liveWebViewCount
        c.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(c.hasSpare)
        assertEquals(liveBefore, c.liveWebViewCount)
        assertNotNull(c.current.thumb)
        assertEquals(1, c.tabs.count { it.thumb != null })

        // 内存真的紧张：后台标签的 WebView 全回收，只留当前这个，缩略图全清
        c.tabs.forEach { it.thumb = ImageBitmap(4, 4) }
        c.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(1, c.liveWebViewCount)
        assertTrue(c.current.webView != null)
        assertTrue(c.tabs.all { it.thumb == null })
        // 回收的标签保存了前进后退记录，之后还能恢复（webViewFor 不会抛）
        c.tabs.filter { it.webView == null }.forEach { assertNotNull(c.webViewFor(it)) }
    }

    @Test
    fun spareIsReplacedInIdleTimeAfterBeingUsedAndDroppedWhileHidden() {
        val c = controller()
        c.prewarm(); idle()
        assertTrue(c.hasSpare)
        // 第一个标签用掉备用 WebView，空闲时再补一个
        c.current.isHome = false
        c.webViewFor(c.current)
        idle()
        assertTrue("spare should be re-created", c.hasSpare)
        // 切到后台丢掉，回到前台（onHostResume）再补
        c.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(c.hasSpare)
        c.onHostResume(); idle()
        assertTrue(c.hasSpare)
    }

    @Test
    fun noSpareIsCreatedWhenLiveWebViewsAreAtTheLimit() {
        val c = controller()
        openLiveTabs(c, 3)
        c.prewarm(); idle()
        c.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        c.onHostResume(); idle()
        assertEquals(3, c.liveWebViewCount)
        assertFalse("limit reached: no extra spare", c.hasSpare)
    }

    @Test
    fun incognitoAndHomeTabsNeverGetThumbnails() {
        val c = controller()
        c.captureThumbnail() // 首页：不截
        assertNull(c.current.thumb)
        c.newTab("https://secret.example.com/", incognito = true); c.current.isHome = false
        c.webViewFor(c.current)
        c.captureThumbnail()
        idle()
        assertNull(c.current.thumb)
    }

    private fun waitFor(timeoutMs: Long = 90_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout")
            Thread.sleep(20)
        }
    }
}
