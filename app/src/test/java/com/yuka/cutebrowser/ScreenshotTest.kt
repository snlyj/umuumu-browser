package com.yuka.cutebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.activity.compose.setContent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yuka.cutebrowser.ui.BunnyProgressBar
import com.yuka.cutebrowser.ui.CuteIcons
import com.yuka.cutebrowser.ui.JellyButton
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val OUT = "../../cute-browser-release/screens"

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForBrowser() {
        // 主题和会话是异步读的。完整套件更慢，若一上来就把时钟停掉，主线程协程被冻住，界面会一直是空白底色。
        // 不能用 waitUntil/waitForIdle：主页小兔子的耳朵是无限动画，autoAdvance 开着时永远等不到空闲。
        rule.mainClock.autoAdvance = true
        val deadline = System.currentTimeMillis() + 10_000
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
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150) // 让 DataStore 的 IO 协程有时间写入
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
        }
    }

    /** 滚动动画要靠时钟推进，暂时打开自动推进 */
    private fun scrolled(block: () -> Unit) {
        rule.mainClock.autoAdvance = true
        block()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun appFlow() {
        waitForBrowser()
        rule.mainClock.autoAdvance = false
        try {
        settle()
        captureScreenRoboImage("$OUT/01_home_strawberry.png")

        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        captureScreenRoboImage("$OUT/02_menu.png")

        rule.onNodeWithText("下载").performClick(); settle()
        captureScreenRoboImage("$OUT/09_downloads_empty.png")
        androidx.test.espresso.Espresso.pressBack(); settle()

        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("设置").performClick(); settle()
        captureScreenRoboImage("$OUT/03_settings.png")

        rule.onNodeWithText("星空梦境").performClick(); settle()
        captureScreenRoboImage("$OUT/04_settings_starry.png")

        rule.onNodeWithText("樱花").performClick(); settle()
        androidx.test.espresso.Espresso.pressBack(); settle()
        captureScreenRoboImage("$OUT/05_home_sakura.png")

        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("设置").performClick(); settle()
        rule.onNodeWithText("星空梦境").performClick(); settle()
        androidx.test.espresso.Espresso.pressBack(); settle()
        captureScreenRoboImage("$OUT/06_home_starry.png")

        rule.onNodeWithText("1").performClick(); settle()
        captureScreenRoboImage("$OUT/07_tabs_starry.png")
        androidx.test.espresso.Espresso.pressBack(); settle()

        // 换回默认主题
        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("设置").performClick(); settle()
        rule.onNodeWithText("草莓牛奶").performClick(); settle()

        // v1.3 安全与隐私
        scrolled { rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performScrollTo() }
        rule.onNodeWithText("安全与隐私（HTTPS、Cookie、权限、应用锁…）").performClick(); settle()
        captureScreenRoboImage("$OUT/11_security.png")
        scrolled { rule.onNodeWithText("防指纹").performScrollTo() }; settle()
        captureScreenRoboImage("$OUT/27_security_antifp_merged.png")
        scrolled { rule.onNodeWithText("退出时清除数据").performScrollTo() }; settle()
        captureScreenRoboImage("$OUT/12_security_more.png")
        androidx.test.espresso.Espresso.pressBack(); settle()

        // 隐身模式（菜单开关：进入后再打开菜单应显示「结束隐身」）
        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        rule.onNodeWithText("隐身模式").performClick(); settle()
        captureScreenRoboImage("$OUT/13_incognito_home.png")
        rule.onNodeWithContentDescription("菜单").performClick(); settle()
        captureScreenRoboImage("$OUT/18_incognito_menu.png")
        androidx.test.espresso.Espresso.pressBack(); settle()
        rule.onNodeWithText("2").performClick(); settle()
        captureScreenRoboImage("$OUT/14_tabs_incognito.png")
        androidx.test.espresso.Espresso.pressBack(); settle()
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test
    fun securityDialogs() {
        waitForBrowser()
        rule.mainClock.autoAdvance = false
        settle()
        val act = rule.activity
        val controller = com.yuka.cutebrowser.browser.BrowserController(
            act, com.yuka.cutebrowser.data.CuteRepository(act), {}, { false }, { _, _ -> },
        )
        val tab = controller.current.apply { isHome = false; url = "http://old-site.example/" }
        val warning = com.yuka.cutebrowser.browser.SecurityWarning(
            com.yuka.cutebrowser.browser.SecurityWarning.Kind.HTTPS_FAILED, "https://old-site.example/", "http://old-site.example/",
        )
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.SecurityWarningPage(tab, warning, controller) } }
        settle()
        captureScreenRoboImage("$OUT/15_https_warning.png")
        val prompt = com.yuka.cutebrowser.browser.PermissionPrompt(
            "https://maps.example.com", listOf(com.yuka.cutebrowser.data.SitePerm.LOCATION), false,
        ) { _, _ -> }
        act.setContent { CuteTheme(CuteThemeId.STRAWBERRY) { com.yuka.cutebrowser.ui.PermissionDialog(prompt) } }
        settle()
        captureScreenRoboImage("$OUT/16_permission_dialog.png")
    }

    @Test
    fun components() {
        rule.activity.setContent {
            CuteTheme(CuteThemeId.STRAWBERRY) {
                val p = LocalCutePalette.current
                Column(
                    Modifier.fillMaxWidth().background(p.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("加载进度条 🐰", color = p.text, fontSize = 16.sp)
                    BunnyProgressBar(progress = { 62 }, visible = { true })
                    Text("果冻按钮 & 手绘图标", color = p.text, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(CuteIcons.Back, CuteIcons.Forward, CuteIcons.Home, CuteIcons.HeartFilled, CuteIcons.Bow, CuteIcons.Flower)
                            .forEachIndexed { i, ic ->
                                JellyButton(onClick = {}, background = listOf(p.pink, p.lavender, p.mint)[i % 3]) {
                                    Icon(ic, null, tint = p.accent, modifier = Modifier.size(22.dp))
                                }
                            }
                    }
                    Box(Modifier.height(8.dp))
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$OUT/08_components.png")
    }
}
