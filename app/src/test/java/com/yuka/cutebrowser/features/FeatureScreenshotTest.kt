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
import com.yuka.cutebrowser.browser.DlStatus
import com.yuka.cutebrowser.browser.DownloadItem
import com.yuka.cutebrowser.ui.DownloadsContent
import com.yuka.cutebrowser.ui.FindBar
import com.yuka.cutebrowser.ui.ReaderLoading
import com.yuka.cutebrowser.ui.ReaderToolbar
import com.yuka.cutebrowser.ui.TranslateCard
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

    @Test
    fun readerToolbarLoading() {
        rule.setContent {
            CuteTheme(CuteThemeId.SAKURA) {
                Column(Modifier.fillMaxSize().background(LocalCutePalette.current.background)) {
                    ReaderToolbar(ReaderStyle(zoom = 110, theme = ReaderTheme.SEPIA), onStyle = {}, onClose = {})
                    ReaderLoading()
                }
            }
        }
        rule.onNodeWithText("110%").assertExists()
        rule.onNodeWithContentDescription("退出阅读模式").assertExists()
        captureScreenRoboImage("$OUT/45_reader_toolbar_loading.png")
    }

    @Test
    fun translateConfirm() {
        rule.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) {
                Box(Modifier.fillMaxSize().background(LocalCutePalette.current.text.copy(alpha = 0.35f)).padding(22.dp)) {
                    TranslateCard(
                        TranslateAsk(1, "https://en.wikipedia.org/wiki/Rabbit", incognito = false),
                        TranslateProvider.GOOGLE, TranslateTarget.ZH_CN, onConfirm = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }
        rule.onNodeWithText("会发送：en.wikipedia.org 的网址").assertExists()
        rule.onNodeWithText("发送并翻译 🌏").assertExists()
        captureScreenRoboImage("$OUT/46_translate_confirm.png")
    }

    @Test
    fun downloadsManager() {
        val now = 1_764_000_000_000L
        val rows = listOf(
            DownloadRow(DownloadItem("dm:5", 5, null, "兔子壁纸合集.zip", "application/zip", DlStatus.RUNNING, 31_457_280, 52_428_800, now), DownloadRecord(5, "https://cdn.example.com/a.zip", "兔子壁纸合集.zip", "application/zip", null, now)),
            DownloadRow(DownloadItem("dm:4", 4, null, "可爱贴纸.png", "image/png", DlStatus.DONE, 245_760, 245_760, now - 60_000), DownloadRecord(4, "https://example.com/s.png", "可爱贴纸.png", "image/png", null, now - 60_000)),
            DownloadRow(DownloadItem("dm:3", 3, null, "养兔指南.pdf", "application/pdf", DlStatus.FAILED, 0, -1, now - 120_000), DownloadRecord(3, "https://example.com/g.pdf", "养兔指南.pdf", "application/pdf", null, now - 120_000)),
            DownloadRow(null, DownloadRecord(2, "https://music.example.com/song.mp3", "晚安曲.mp3", "audio/mpeg", null, now - 86_400_000)),
        )
        rule.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) {
                Column(Modifier.fillMaxSize().background(LocalCutePalette.current.surface).padding(horizontal = 20.dp)) {
                    DownloadsContent(rows, {}, {}, {}, {}, {}, {})
                }
            }
        }
        rule.onNodeWithText("正在下载 1 个 ⏬").assertExists()
        rule.onNodeWithText("清掉失效记录").assertExists()
        captureScreenRoboImage("$OUT/47_downloads_manager.png")
    }

    companion object {
        const val OUT = "../../cute-browser-feat-screens"
    }
}
