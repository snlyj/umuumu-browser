package com.yuka.cutebrowser

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yuka.cutebrowser.ui.CuteIcons
import com.yuka.cutebrowser.ui.JellyButton
import com.yuka.cutebrowser.ui.ToggleRow
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.StarryPalette
import com.yuka.cutebrowser.ui.theme.StrawberryPalette
import com.yuka.cutebrowser.ui.theme.SakuraPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class UiSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun smallJellyButtonStillHas48dpTouchTarget() {
        rule.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) {
                JellyButton(onClick = {}, size = 34.dp) { Icon(CuteIcons.Close, "清空", modifier = Modifier.size(16.dp)) }
            }
        }
        rule.onNodeWithContentDescription("清空").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun toggleRowIsASwitchThatReportsState() {
        var on by mutableStateOf(false)
        rule.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) { ToggleRow("🧹", "拦截广告", "说明", on) { on = !on } }
        }
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        assertTrue(on)
    }

    // ---- 配色对比度（WCAG）：正文 ≥ 4.5:1 ----
    private fun lum(c: androidx.compose.ui.graphics.Color): Double {
        fun f(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val (hi, lo) = lum(a).let { x -> lum(b).let { y -> if (x > y) x to y else y to x } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun paletteTextContrast() {
        for ((name, p) in listOf("strawberry" to StrawberryPalette, "sakura" to SakuraPalette, "starry" to StarryPalette)) {
            assertTrue("$name text/background", contrast(p.text, p.background) >= 4.5)
            assertTrue("$name subText/background", contrast(p.subText, p.background) >= 4.5)
            assertTrue("$name subText/surface", contrast(p.subText, p.surface) >= 4.5)
            assertTrue("$name onAccent/accent", contrast(p.onAccent, p.accent) >= 4.5)
            assertTrue("$name accentInk/surface", contrast(p.accentInk, p.surface) >= 4.5)
            assertTrue("$name accentInk/pink", contrast(p.accentInk, p.pink) >= 3.0) // 粉底上的粗体小标题
        }
    }
}
