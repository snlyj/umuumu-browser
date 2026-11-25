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

/** v1.4 防指纹界面截图（只在 -Proborazzi.test.record=true 时写文件，写到 cute-browser-release/screens） */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class FpScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
        }
    }

    @Test
    fun antiFingerprintUi() {
        rule.mainClock.autoAdvance = false
        settle()
        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("设置").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performClick(); settle()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("网页自动填充").performScrollTo()
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/24_fp_settings_standard.png")
        rule.onNodeWithText("严格").performClick(); settle()
        captureScreenRoboImage("../../cute-browser-release/screens/25_fp_settings_strict.png")
        rule.onNodeWithText("标准").performClick(); settle()

        val act = rule.activity
        val controller = com.yuka.cutebrowser.browser.BrowserController(
            act, com.yuka.cutebrowser.data.CuteRepository(act), {}, { false }, { _, _ -> },
        )
        val tab = controller.current.apply { isHome = false; url = "https://www.example.com/" }
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.ShieldDialog(tab, controller) {} } }
        settle()
        captureScreenRoboImage("../../cute-browser-release/screens/26_fp_shield_dialog.png")
    }
}
