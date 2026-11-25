package com.yuka.cutebrowser

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        settle()
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
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/32_shield_with_tzlang.png")
        controller.setSiteTzLang("www.example.com", com.yuka.cutebrowser.privacy.locale.SiteTzLang("Asia/Tokyo", null))
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.SiteTzLangDialog("www.example.com", controller) {} } }
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/33_site_tzlang_dialog.png")
    }
}
