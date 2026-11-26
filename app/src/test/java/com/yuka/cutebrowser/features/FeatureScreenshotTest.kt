package com.yuka.cutebrowser.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yuka.cutebrowser.ui.FindBar
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v1.7 常用功能的界面截图（只在 -Proborazzi.test.record=true 时写文件），也顺便检查几个状态 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class FeatureScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    /** 一个假的「网页」底，让截图看起来像在浏览器里 */
    @Composable
    private fun FakePage(top: @Composable () -> Unit) {
        val p = LocalCutePalette.current
        Column(Modifier.fillMaxSize().background(p.background)) {
            top()
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "兔子是一种很可爱的小动物。兔子喜欢吃胡萝卜，也喜欢在草地上蹦蹦跳跳。\n\n养兔子要注意：兔子的牙齿会一直长，所以要给它磨牙的东西。",
                    color = p.text, fontSize = 16.sp,
                )
            }
        }
    }

    @Test
    fun findBar() {
        val s = FindState().apply { open = true; query = "兔子"; total = 12; active = 2; tabId = 1 }
        rule.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) {
                FakePage { FindBar(s, onQuery = {}, onNext = {}, onClose = {}, autoFocus = false) }
            }
        }
        rule.onNodeWithText("3/12").assertExists()
        rule.onNodeWithContentDescription("下一个").assertIsEnabled()
        captureScreenRoboImage("$OUT/40_find_in_page.png")
    }

    @Test
    fun findBarNoMatchStarry() {
        val s = FindState().apply { open = true; query = "独角兽"; total = 0; counting = false; tabId = 1 }
        rule.setContent {
            CuteTheme(CuteThemeId.STARRY) {
                FakePage { FindBar(s, onQuery = {}, onNext = {}, onClose = {}, autoFocus = false) }
            }
        }
        rule.onNodeWithText("没找到").assertExists()
        rule.onNodeWithContentDescription("上一个").assertIsNotEnabled()
        captureScreenRoboImage("$OUT/41_find_no_match_starry.png")
    }

    companion object {
        const val OUT = "../../cute-browser-feat-screens"
    }
}
