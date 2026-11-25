package com.yuka.cutebrowser

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v1.5 时区与语言界面截图（只在 -Proborazzi.test.record=true 时写文件） */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class TzLangScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForBrowser() {
        // 主题和会话是异步读的。完整套件更慢，若一上来就把时钟停掉，主线程协程被冻住，界面会一直是空白底色。
        // 不能用 waitUntil/waitForIdle：主页小兔子的耳朵是无限动画，autoAdvance 开着时永远等不到空闲。
        rule.mainClock.autoAdvance = true
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(200)
            if (runCatching { rule.onAllNodesWithContentDescription("菜单").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)) {
                rule.mainClock.autoAdvance = false
                return
            }
        }
        rule.mainClock.autoAdvance = false
        throw AssertionError("waitForBrowser: 20s 内没有等到「菜单」，主页可能一直停在启动底色")
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
        }
    }

    /** 带输入框的弹窗（Robolectric 里 Dialog 里的 BasicTextField 永远"不空闲"），只推进时钟、不等空闲 */
    private fun settleNoIdle() {
        repeat(8) { Thread.sleep(150); rule.mainClock.advanceTimeBy(400) }
    }

    @Test
    fun tzLangUi() {
        waitForBrowser()
        rule.mainClock.autoAdvance = false
        settle()
        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("设置").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("伪造时区和语言").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        rule.onNodeWithText("伪造时区和语言").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("伪造时区和语言").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/28_tzlang_settings_off.png")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("🇯🇵 日本：东京 + 日本語").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        rule.onNodeWithText("🇯🇵 日本：东京 + 日本語").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("🛰️ 小提醒").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/29_tzlang_settings_on.png")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("时区").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
    }

    @Test
    fun langAndSiteDialogs() {
        rule.mainClock.autoAdvance = false
        settleNoIdle()
        val act = rule.activity
        val repo = com.yuka.cutebrowser.data.CuteRepository(act)
        val controller = com.yuka.cutebrowser.browser.BrowserController(act, repo, {}, { false }, { _, _ -> })
        act.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.LangPickerDialog("ja-JP", true, {}, {}) }
        }
        settleNoIdle()
        captureScreenRoboImage("../../cute-browser-release/screens/31_lang_picker.png")
        act.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.TzPickerDialog("Asia/Tokyo", true, {}, {}) }
        }
        settleNoIdle()
        captureScreenRoboImage("../../cute-browser-release/screens/30_tz_picker.png")
        controller.applySecurity(controller.sec.copy(tzOn = true, tz = "America/Los_Angeles", lang = "en-US"))
        val tab = controller.current.apply { isHome = false; url = "https://www.example.com/" }
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.ShieldDialog(tab, controller) {} } }
        settleNoIdle()
        captureScreenRoboImage("../../cute-browser-release/screens/32_shield_with_tzlang.png")
        controller.setSiteTzLang("www.example.com", com.yuka.cutebrowser.privacy.locale.SiteTzLang("Asia/Tokyo", null))
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.SiteTzLangDialog("www.example.com", controller) {} } }
        settleNoIdle()
        captureScreenRoboImage("../../cute-browser-release/screens/33_site_tzlang_dialog.png")
    }
}
