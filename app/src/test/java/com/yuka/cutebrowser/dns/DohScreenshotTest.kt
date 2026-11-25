package com.yuka.cutebrowser.dns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yuka.cutebrowser.ui.DohSettingsSection
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 加密 DNS 设置区的预览图（只在 -Proborazzi.test.record=true 时写文件） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1100dp-xhdpi")
class DohScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() { DohManager.supportedOverride = null }

    private fun shot(supported: Boolean, theme: CuteThemeId, file: String) {
        DohManager.supportedOverride = supported
        rule.setContent {
            CuteTheme(theme) {
                Column(Modifier.fillMaxSize().background(LocalCutePalette.current.background).padding(16.dp)) { DohSettingsSection() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("../../cute-browser-release/screens/$file")
    }

    @Test fun supported() = shot(true, CuteThemeId.STRAWBERRY, "21_doh_settings.png")
    @Test fun supportedStarry() = shot(true, CuteThemeId.STARRY, "22_doh_settings_starry.png")
    @Test fun unsupported() = shot(false, CuteThemeId.STRAWBERRY, "23_doh_unsupported.png")
}
