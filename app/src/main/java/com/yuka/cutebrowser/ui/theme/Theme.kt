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
    // 注意：枚举名会存进用户设置里，不能改名；只能往后加
    STRAWBERRY("草莓牛奶", "🍓"),
    SAKURA("樱花", "🌸"),
    STARRY("星空梦境", "🌙"),
    MINT("薄荷绿", "🍃"),
    LILAC("芙莉紫", "🔮"),
    SODA("汽水蓝", "💧"),
    PEACH("蜜桃橘", "🍑"),
}

/** 设置里显示的顺序：浅色在前，夜间（星空梦境）放最后 */
val ThemeOrder = listOf(
    CuteThemeId.STRAWBERRY, CuteThemeId.SAKURA, CuteThemeId.MINT, CuteThemeId.LILAC,
    CuteThemeId.SODA, CuteThemeId.PEACH, CuteThemeId.STARRY,
)

/** 「跟随系统」里系统深色时用的夜间主题 */
val NightTheme = CuteThemeId.STARRY

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
    /** 用在文字/小图标上的强调色：浅色主题里比 accent 更深一点，保证对比度 ≥ 4.5:1；深色主题就是 accent 本身 */
    val accentInk: Color = accent,
)

val StrawberryPalette = CutePalette(
    background = Color(0xFFFFF8F3),
    surface = Color(0xFFFFFDFB),
    pink = Color(0xFFFFD6E0),
    lavender = Color(0xFFE4D7FF),
    mint = Color(0xFFD4F5E9),
    accent = Color(0xFFFF8FAB),
    text = Color(0xFF5A4A5E),
    // 原来的 #9C8AA0 在奶白底上只有 3.05:1；稍微加深到 4.5:1 以上，仍是柔和的灰紫色
    subText = Color(0xFF76657C),
    // 粉色按钮上的字改成深莓色：原来的白色在 #FF8FAB 上只有 2.1:1
    onAccent = Color(0xFF4A3A4E),
    isDark = false,
    accentInk = Color(0xFFB83A66),
)

val SakuraPalette = CutePalette(
    background = Color(0xFFFFFAFC),
    surface = Color(0xFFFFFFFF),
    pink = Color(0xFFFFE1EA),
    lavender = Color(0xFFF7E8F3),
    mint = Color(0xFFEAF7EF),
    accent = Color(0xFFF48FB1),
    text = Color(0xFF6B4C5A),
    subText = Color(0xFF86687A),
    onAccent = Color(0xFF4A3A4E),
    isDark = false,
    accentInk = Color(0xFFB83A66),
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

val MintPalette = CutePalette(
    background = Color(0xFFF2FBF6), surface = Color(0xFFFCFFFD),
    pink = Color(0xFFCFF1E0), lavender = Color(0xFFD9EEF5), mint = Color(0xFFE7F5CF),
    accent = Color(0xFF7DD6AE), text = Color(0xFF2C4A3E), subText = Color(0xFF4F7064),
    onAccent = Color(0xFF143D2E), isDark = false, accentInk = Color(0xFF1E7A57),
)

val LilacPalette = CutePalette(
    background = Color(0xFFFAF5FF), surface = Color(0xFFFFFDFF),
    pink = Color(0xFFE6D6FA), lavender = Color(0xFFF6D9EE), mint = Color(0xFFD9E4FF),
    accent = Color(0xFFB996F2), text = Color(0xFF45365F), subText = Color(0xFF6A5886),
    onAccent = Color(0xFF33204F), isDark = false, accentInk = Color(0xFF7140C0),
)

val SodaPalette = CutePalette(
    background = Color(0xFFF0F9FF), surface = Color(0xFFFBFEFF),
    pink = Color(0xFFCCE8FA), lavender = Color(0xFFD8E2FF), mint = Color(0xFFCFF3EE),
    accent = Color(0xFF6FC3F3), text = Color(0xFF2B4560), subText = Color(0xFF506A85),
    onAccent = Color(0xFF0F3956), isDark = false, accentInk = Color(0xFF1A6FAE),
)

val PeachPalette = CutePalette(
    background = Color(0xFFFFF5EC), surface = Color(0xFFFFFCF8),
    pink = Color(0xFFFFD9C4), lavender = Color(0xFFFFE6B5), mint = Color(0xFFFFD3DE),
    accent = Color(0xFFFFA27A), text = Color(0xFF5A4234), subText = Color(0xFF7A5C4B),
    onAccent = Color(0xFF4A2210), isDark = false, accentInk = Color(0xFFAE4416),
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
    CuteThemeId.MINT -> MintPalette
    CuteThemeId.LILAC -> LilacPalette
    CuteThemeId.SODA -> SodaPalette
    CuteThemeId.PEACH -> PeachPalette
}

/** 全部可选配色（含隐身）：测试用来逐个检查对比度 */
val AllPalettes: List<Pair<String, CutePalette>> =
    CuteThemeId.entries.map { it.name to paletteOf(it) } + ("INCOGNITO" to IncognitoPalette)

/**
 * 实际使用的主题：「跟随系统」打开时，系统深色 → 夜间主题；系统浅色 → 用户选的浅色主题
 * （如果用户选的本来就是夜间主题，浅色时退回默认的草莓牛奶）。
 */
fun resolveTheme(selected: CuteThemeId, followSystem: Boolean, systemDark: Boolean): CuteThemeId = when {
    !followSystem -> selected
    systemDark -> NightTheme
    paletteOf(selected).isDark -> CuteThemeId.STRAWBERRY
    else -> selected
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
