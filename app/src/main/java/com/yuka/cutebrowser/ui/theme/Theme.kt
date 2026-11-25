package com.yuka.cutebrowser.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 三套可爱主题 */
enum class CuteThemeId(val label: String, val emoji: String) {
    STRAWBERRY("草莓牛奶", "🍓"),
    SAKURA("樱花", "🌸"),
    STARRY("星空梦境", "🌙"),
}

@Immutable
data class CutePalette(
    val background: Color,
    val surface: Color,
    val pink: Color,
    val lavender: Color,
    val mint: Color,
    val accent: Color,
    val text: Color,
    val subText: Color,
    val onAccent: Color,
    val isDark: Boolean,
)

val StrawberryPalette = CutePalette(
    background = Color(0xFFFFF8F3),
    surface = Color(0xFFFFFDFB),
    pink = Color(0xFFFFD6E0),
    lavender = Color(0xFFE4D7FF),
    mint = Color(0xFFD4F5E9),
    accent = Color(0xFFFF8FAB),
    text = Color(0xFF5A4A5E),
    subText = Color(0xFF9C8AA0),
    onAccent = Color.White,
    isDark = false,
)

val SakuraPalette = CutePalette(
    background = Color(0xFFFFFAFC),
    surface = Color(0xFFFFFFFF),
    pink = Color(0xFFFFE1EA),
    lavender = Color(0xFFF7E8F3),
    mint = Color(0xFFEAF7EF),
    accent = Color(0xFFF48FB1),
    text = Color(0xFF6B4C5A),
    subText = Color(0xFFAA8C9A),
    onAccent = Color.White,
    isDark = false,
)

val StarryPalette = CutePalette(
    background = Color(0xFF24254D),
    surface = Color(0xFF30315F),
    pink = Color(0xFF4B3F78),
    lavender = Color(0xFF5A4E92),
    mint = Color(0xFF3B5B7E),
    accent = Color(0xFFCBB8FF),
    text = Color(0xFFF2EDFF),
    subText = Color(0xFFB6AEDA),
    onAccent = Color(0xFF24254D),
    isDark = true,
)

/** 隐身模式：深薰衣草紫 + 戴眼罩的小兔子 */
val IncognitoPalette = CutePalette(
    background = Color(0xFF2B2140),
    surface = Color(0xFF382B52),
    pink = Color(0xFF4A3A6B),
    lavender = Color(0xFF5B4785),
    mint = Color(0xFF3F3563),
    accent = Color(0xFFD7B8FF),
    text = Color(0xFFF1E8FF),
    subText = Color(0xFFB9A8D6),
    onAccent = Color(0xFF2B2140),
    isDark = true,
)

fun paletteOf(id: CuteThemeId): CutePalette = when (id) {
    CuteThemeId.STRAWBERRY -> StrawberryPalette
    CuteThemeId.SAKURA -> SakuraPalette
    CuteThemeId.STARRY -> StarryPalette
}

val LocalCutePalette = staticCompositionLocalOf { StrawberryPalette }

@Composable
fun CuteTheme(id: CuteThemeId, incognito: Boolean = false, content: @Composable () -> Unit) {
    val p = if (incognito) IncognitoPalette else paletteOf(id)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.lavender,
            background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.lavender,
            background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text,
            surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !p.isDark
            c.isAppearanceLightNavigationBars = !p.isDark
        }
    }
    CompositionLocalProvider(LocalCutePalette provides p) {
        // 默认 Locale 会被「伪造语言」改掉；界面永远是简体中文，固定用 zh-CN 选字形，避免汉字变成日文/韩文字形
        MaterialTheme(colorScheme = scheme) {
            ProvideTextStyle(TextStyle(localeList = LocaleList("zh-CN")), content)
        }
    }
}
