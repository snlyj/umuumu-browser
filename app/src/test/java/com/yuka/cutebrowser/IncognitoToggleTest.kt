package com.yuka.cutebrowser

import androidx.activity.ComponentActivity
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.data.CuteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncognitoToggleTest {
    private var activityController: ActivityController<ComponentActivity>? = null
    private var controller: BrowserController? = null

    private fun controller(): BrowserController {
        val ac = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activityController = ac
        return BrowserController(ac.get(), CuteRepository(ac.get()), {}, { false }, { _, _ -> }).also { controller = it }
    }

    @After
    fun tearDown() {
        controller?.destroyAll()
        controller?.releaseGlobals()
        runCatching { activityController?.pause()?.stop()?.destroy() }
        controller = null
        activityController = null
    }

    @Test
    fun toggleEntersReusesAndExitsWithoutPilingUp() {
        val c = controller()
        assertEquals(1, c.tabs.size)
        assertFalse(c.current.incognito)
        val home = c.current

        c.toggleIncognito()
        assertEquals(2, c.tabs.size)
        assertTrue(c.current.incognito)
        val firstIncognito = c.current

        // 再点一次是退出，不会又开一个隐身页
        c.toggleIncognito()
        assertEquals(1, c.tabs.size)
        assertFalse(c.current.incognito)
        assertEquals(home.id, c.current.id)
        assertTrue(c.tabs.none { it.incognito })

        // 再进入
        c.toggleIncognito()
        val incognito = c.current
        assertTrue(incognito.incognito)

        // 标签页里的「新建隐身标签」仍然会新开一个
        c.newTab(incognito = true)
        assertEquals(3, c.tabs.size) // 1 普通 + 2 隐身
        assertTrue(c.current.incognito)
        assertTrue(c.current.id != incognito.id)
        val secondIncognito = c.current
        secondIncognito.lastUsed = (incognito.lastUsed) + 10

        // 回到普通标签后再开隐身：复用最近那个，不再堆一个新的
        c.switchTo(c.tabs.indexOf(home))
        assertFalse(c.current.incognito)
        val n = c.tabs.size
        c.toggleIncognito()
        assertEquals(n, c.tabs.size)
        assertEquals(secondIncognito.id, c.current.id)

        // 退出会关掉全部隐身标签，普通标签留下
        c.exitIncognito()
        assertEquals(1, c.tabs.size)
        assertFalse(c.current.incognito)
        assertEquals(home.id, c.current.id)
        assertTrue(c.tabs.none { it.incognito })
    }

    @Test
    fun exitReturnsToLastUsedNormalTab() {
        val c = controller()
        val first = c.current
        first.lastUsed = 1
        c.newTab()
        val second = c.current
        second.lastUsed = 2
        c.toggleIncognito()
        assertTrue(c.current.incognito)
        c.exitIncognito()
        assertEquals(second.id, c.current.id)
        assertEquals(2, c.tabs.size)
        assertTrue(c.tabs.none { it.incognito })
    }

    @Test
    fun exitCreatesNormalTabWhenNoneRemain() {
        val c = controller()
        val home = c.current
        c.toggleIncognito()
        c.closeTab(home)
        assertTrue(c.tabs.isNotEmpty() && c.tabs.all { it.incognito })
        c.exitIncognito()
        assertEquals(1, c.tabs.size)
        assertFalse(c.current.incognito)
    }
}
