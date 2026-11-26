package com.yuka.cutebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.data.AppearancePrefs
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.Mascot
import com.yuka.cutebrowser.data.PageEntry
import com.yuka.cutebrowser.data.ProgressStyle
import com.yuka.cutebrowser.data.Wallpaper
import com.yuka.cutebrowser.data.WallpaperStore
import com.yuka.cutebrowser.ui.AppearanceSection
import com.yuka.cutebrowser.ui.BunnyProgressBar
import com.yuka.cutebrowser.ui.HomePage
import com.yuka.cutebrowser.ui.MascotView
import com.yuka.cutebrowser.ui.SettingsSheet
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import com.yuka.cutebrowser.ui.theme.ProvideAppearance
import com.yuka.cutebrowser.ui.theme.ThemeOrder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

internal const val THEME_OUT = "../../cute-browser-release/screens/theme"

private val sampleBookmarks = listOf(
    PageEntry("https://example.com", "小红书 - 你的生活指南", 1),
    PageEntry("https://example.org", "好看的壁纸合集 ✨", 2),
)

/** 每套配色的首页 + 设置页，内置壁纸、吉祥物、进度条。用 -Proborazzi.test.record=true 才会写 PNG */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThemeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun settle(n: Int = 6) {
        rule.mainClock.autoAdvance = false // 吉祥物/进度条有无限循环动画，必须手动推进时钟
        repeat(n) {
            Thread.sleep(80)
            runCatching { ShadowLooper.idleMainLooper() }
            rule.mainClock.advanceTimeBy(300)
        }
    }

    private fun home(id: CuteThemeId, ap: AppearancePrefs = AppearancePrefs(), file: String) {
        rule.activity.setContent {
            CuteTheme(id) { ProvideAppearance(ap) {
                Box(Modifier.fillMaxSize()) { HomePage(sampleBookmarks, onOpen = {}) }
            } }
        }
        settle(3)
        rule.onRoot().captureRoboImage("$THEME_OUT/$file")
    }

    @Test
    fun homePerPalette() {
        for (id in ThemeOrder) home(id, file = "home_${id.name.lowercase()}.png")
    }

    @Test
    fun settingsPerPalette() {
        val act = rule.activity
        val repo = CuteRepository(act)
        val controller = BrowserController(act, repo, {}, { false }, { _, _ -> })
        for (id in ThemeOrder) {
            runBlocking { repo.setTheme(id) }
            rule.activity.setContent {
                CuteTheme(id) { ProvideAppearance(AppearancePrefs()) {
                    val p = LocalCutePalette.current
                    Column(Modifier.fillMaxSize().background(p.surface).padding(horizontal = 20.dp)) {
                        SettingsSheet(controller, repo, onClose = {}, onSecurity = {})
                    }
                } }
            }
            settle(8)
            rule.onRoot().captureRoboImage("$THEME_OUT/settings_${id.name.lowercase()}.png")
        }
    }

    @Test
    fun wallpapers() {
        for (w in Wallpaper.entries) {
            if (w == Wallpaper.NONE || w == Wallpaper.CUSTOM) continue
            home(CuteThemeId.STRAWBERRY, AppearancePrefs(wallpaper = w), "wallpaper_${w.name.lowercase()}_strawberry.png")
        }
        for (w in listOf(Wallpaper.CANDY, Wallpaper.HEARTS, Wallpaper.STARS)) {
            home(CuteThemeId.STARRY, AppearancePrefs(wallpaper = w), "wallpaper_${w.name.lowercase()}_starry.png")
        }
        home(CuteThemeId.SODA, AppearancePrefs(wallpaper = Wallpaper.WAVES), "wallpaper_waves_soda.png")
        home(CuteThemeId.MINT, AppearancePrefs(wallpaper = Wallpaper.GINGHAM), "wallpaper_gingham_mint.png")
    }

    /** 一张故意很难看清文字的"照片"：左边纯白、右边纯黑、中间彩色条纹 */
    private fun harshPhoto(): Uri {
        val b = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint()
        paint.color = AColor.WHITE; c.drawRect(0f, 0f, 400f, 1800f, paint)
        paint.color = AColor.BLACK; c.drawRect(800f, 0f, 1200f, 1800f, paint)
        val colors = intArrayOf(0xFFFF0066.toInt(), 0xFF00B3FF.toInt(), 0xFFFFD500.toInt(), 0xFF22CC66.toInt())
        for (i in 0 until 18) { paint.color = colors[i % 4]; c.drawRect(400f, i * 100f, 800f, i * 100f + 50f, paint) }
        val f = File(rule.activity.cacheDir, "harsh.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    @Test
    fun customPhotoWallpaperKeepsTextReadable() {
        val stamp = WallpaperStore.import(rule.activity, harshPhoto())!!
        home(CuteThemeId.STRAWBERRY, AppearancePrefs(wallpaper = Wallpaper.CUSTOM, wallpaperStamp = stamp, scrim = AppearancePrefs.MIN_SCRIM), "wallpaper_custom_photo_min_scrim_strawberry.png")
        home(CuteThemeId.STARRY, AppearancePrefs(wallpaper = Wallpaper.CUSTOM, wallpaperStamp = stamp, scrim = AppearancePrefs.MIN_SCRIM), "wallpaper_custom_photo_min_scrim_starry.png")
        home(CuteThemeId.LILAC, AppearancePrefs(wallpaper = Wallpaper.CUSTOM, wallpaperStamp = stamp), "wallpaper_custom_photo_default_scrim_lilac.png")
    }

    @Test
    fun mascots() {
        for (m in listOf(Mascot.BUNNY, Mascot.CAT, Mascot.RIBBON, Mascot.OFF)) {
            home(CuteThemeId.STRAWBERRY, AppearancePrefs(mascot = m), "mascot_${m.name.lowercase()}_strawberry.png")
        }
        home(CuteThemeId.STARRY, AppearancePrefs(mascot = Mascot.CAT), "mascot_cat_starry.png")
        home(CuteThemeId.PEACH, AppearancePrefs(mascot = Mascot.RIBBON), "mascot_ribbon_peach.png")
        // 三个吉祥物并排（大图，方便看细节）
        rule.activity.setContent {
            CuteTheme(CuteThemeId.LILAC) { ProvideAppearance(AppearancePrefs(reduceMotion = true)) {
                val p = LocalCutePalette.current
                Row(Modifier.fillMaxWidth().background(p.background).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (m in listOf(Mascot.BUNNY, Mascot.CAT, Mascot.RIBBON)) MascotView(m, Modifier.size(110.dp))
                }
            } }
        }
        settle(2)
        rule.onRoot().captureRoboImage("$THEME_OUT/mascots_side_by_side_lilac.png")
    }

    @Test
    fun progressStyles() {
        for (id in listOf(CuteThemeId.STRAWBERRY, CuteThemeId.STARRY)) {
            rule.activity.setContent {
                CuteTheme(id) { ProvideAppearance(AppearancePrefs()) {
                    val p = LocalCutePalette.current
                    Column(Modifier.fillMaxWidth().background(p.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (s in ProgressStyle.entries) {
                            Text(s.label, color = p.text, fontSize = 14.sp)
                            BunnyProgressBar(progress = { 62 }, visible = { true }, style = s)
                        }
                    }
                } }
            }
            settle(3)
            rule.onRoot().captureRoboImage("$THEME_OUT/progress_styles_${id.name.lowercase()}.png")
        }
    }

    /** 减少动画：同一个首页，吉祥物/进度条没有循环动画 */
    @Test
    fun reducedMotionHome() {
        home(CuteThemeId.STRAWBERRY, AppearancePrefs(reduceMotion = true, mascot = Mascot.CAT), "home_reduced_motion_cat.png")
    }
}

/** 设置里「外观」整块（很长，所以用一张很高的画布） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1500dp-xhdpi")
class AppearanceSectionScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun section(id: CuteThemeId, update: (AppearancePrefs) -> AppearancePrefs, file: String) {
        val repo = CuteRepository(rule.activity)
        // DataStore 是进程级单例，先恢复默认，免得上一个测试的开关漏进来
        runBlocking { repo.setTheme(id); repo.updateAppearance { update(AppearancePrefs()) } }
        rule.activity.setContent {
            CuteTheme(id) { ProvideAppearance(AppearancePrefs(reduceMotion = true)) {
                val p = LocalCutePalette.current
                Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AppearanceSection(repo)
                }
            } }
        }
        rule.mainClock.autoAdvance = false
        repeat(8) { Thread.sleep(80); runCatching { ShadowLooper.idleMainLooper() }; rule.mainClock.advanceTimeBy(300) }
        rule.onRoot().captureRoboImage("$THEME_OUT/$file")
    }

    @Test fun strawberryDefaults() = section(CuteThemeId.STRAWBERRY, { it }, "appearance_section_strawberry.png")

    @Test fun starryWithOptionsOn() = section(
        CuteThemeId.STARRY,
        { it.copy(followSystem = true, wallpaper = Wallpaper.HEARTS, mascot = Mascot.CAT, progress = ProgressStyle.HEARTS, haptics = true) },
        "appearance_section_starry_options.png",
    )

    @Test fun sodaCustomPhoto() {
        val f = File(rule.activity.cacheDir, "p.png")
        val b = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF6FC3F3.toInt()) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val stamp = WallpaperStore.import(rule.activity, Uri.fromFile(f))!!
        section(CuteThemeId.SODA, { it.copy(wallpaper = Wallpaper.CUSTOM, wallpaperStamp = stamp, progress = ProgressStyle.RIBBON, reduceMotion = true) }, "appearance_section_soda_custom.png")
    }
}
