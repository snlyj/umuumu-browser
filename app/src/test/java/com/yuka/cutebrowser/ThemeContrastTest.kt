package com.yuka.cutebrowser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.yuka.cutebrowser.data.AppearancePrefs
import com.yuka.cutebrowser.ui.TEXT_PLATE_ALPHA
import com.yuka.cutebrowser.ui.theme.AllPalettes
import com.yuka.cutebrowser.ui.theme.CutePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x 对比度，每套配色（含隐身）逐项检查 */
internal fun lum(c: Color): Double {
    fun f(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
}

internal fun contrast(a: Color, b: Color): Double {
    val x = lum(a)
    val y = lum(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

class ThemeContrastTest {
    private val failures = mutableListOf<String>()

    /** 先把所有不达标的项收集起来，最后一起报出来（方便一次调完所有配色） */
    private fun check(name: String, what: String, fg: Color, bg: Color, min: Double) {
        val c = contrast(fg, bg)
        if (c < min) failures += "$name: $what 对比度 %.2f < $min".format(c)
    }

    private fun done() = assertTrue(failures.distinct().joinToString("\n"), failures.isEmpty())

    @Test
    fun everyPaletteMeetsWcag() {
        assertTrue("至少 8 套配色（7 个主题 + 隐身）", AllPalettes.size >= 8)
        for ((name, p) in AllPalettes) {
            // 正文：普通界面 + 彩色小卡片/胶囊（粉/紫/薄荷底）
            for ((bgName, bg) in listOf("background" to p.background, "surface" to p.surface, "pink" to p.pink, "lavender" to p.lavender, "mint" to p.mint)) {
                check(name, "text/$bgName", p.text, bg, 4.5)
            }
            check(name, "subText/background", p.subText, p.background, 4.5)
            check(name, "subText/surface", p.subText, p.surface, 4.5)
            check(name, "onAccent/accent", p.onAccent, p.accent, 4.5)
            check(name, "accentInk/surface", p.accentInk, p.surface, 4.5)
            check(name, "accentInk/background", p.accentInk, p.background, 4.5)
            // 彩色底上的粗体小标题
            for ((bgName, bg) in listOf("pink" to p.pink, "lavender" to p.lavender, "mint" to p.mint)) {
                check(name, "accentInk/$bgName", p.accentInk, bg, 3.0)
            }
        }
        done()
    }

    /** 内置壁纸：画面上可能出现的所有底色（含半透明叠加），首页文字在上面都要读得清 */
    private fun wallpaperPixels(p: CutePalette): List<Color> {
        val bg = p.background
        return listOf(
            bg, p.pink, p.lavender, p.mint,
            p.pink.copy(alpha = 0.55f).compositeOver(bg), p.lavender.copy(alpha = 0.6f).compositeOver(bg),
            p.mint.copy(alpha = 0.55f).compositeOver(bg), p.mint.copy(alpha = 0.7f).compositeOver(bg),
            p.accent.copy(alpha = 0.3f).compositeOver(bg),        // 星星
            p.accent.copy(alpha = 0.22f).compositeOver(p.pink),    // 棉花糖的柔光
            p.pink.copy(alpha = 0.45f).compositeOver(p.pink.copy(alpha = 0.45f).compositeOver(bg)), // 格子交叉处
        )
    }

    @Test
    fun textStaysReadableOnBuiltInWallpapers() {
        // 隐身首页没有壁纸；有壁纸时首页的问候/页脚用正文色（见 HomePage），所以这里检查 text 和 accentInk
        for ((name, p) in AllPalettes.filter { it.first != "INCOGNITO" }) for (px in wallpaperPixels(p)) {
            check(name, "text/wallpaper", p.text, px, 4.5)
            check(name, "accentInk/wallpaper", p.accentInk, px, 3.0)
        }
        done()
    }

    /** 自定义照片：不管照片是纯黑还是纯白，遮罩 + 文字底板之后文字都要 ≥ 4.5:1（遮罩取最小值，最坏情况） */
    @Test
    fun textStaysReadableOnAnyPhoto() {
        for ((name, p) in AllPalettes) for (photo in listOf(Color.Black, Color.White, Color(0xFF808080))) {
            val scrimmed = p.background.copy(alpha = AppearancePrefs.MIN_SCRIM).compositeOver(photo)
            val underText = p.surface.copy(alpha = TEXT_PLATE_ALPHA).compositeOver(scrimmed)
            check(name, "text/photo($photo)", p.text, underText, 4.5)
            check(name, "subText/photo($photo)", p.subText, underText, 4.5)
            check(name, "accentInk/photo($photo)", p.accentInk, underText, 4.5)
        }
        done()
    }

    @Test
    fun scrimRangeSane() {
        assertTrue(AppearancePrefs.MIN_SCRIM in 0.5f..AppearancePrefs.MAX_SCRIM)
        assertEquals(0.7f, AppearancePrefs.DEFAULT_SCRIM, 0f)
    }
}
