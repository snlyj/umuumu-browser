package com.yuka.cutebrowser.features

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 阅读模式的配色：跟随 App 主题 / 护眼米黄 / 夜间 */
enum class ReaderTheme(val label: String) { AUTO("跟随主题"), SEPIA("护眼"), NIGHT("夜间") }

enum class ReaderFont(val label: String, val css: String) {
    SANS("圆润黑体", "system-ui, -apple-system, 'Noto Sans CJK SC', 'Noto Sans SC', sans-serif"),
    SERIF("书卷宋体", "'Noto Serif CJK SC', 'Noto Serif SC', 'Source Han Serif SC', serif"),
}

@Immutable
data class ReaderStyle(
    /** WebView textZoom，百分比 */
    val zoom: Int = 100,
    /** 行高（倍数） */
    val lineHeight: Float = 1.8f,
    val theme: ReaderTheme = ReaderTheme.AUTO,
    val font: ReaderFont = ReaderFont.SANS,
) {
    fun zoomIn() = copy(zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM))
    fun zoomOut() = copy(zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM))
    fun nextLineHeight(): ReaderStyle {
        val i = LINE_HEIGHTS.indexOfFirst { it >= lineHeight - 0.01f }.let { if (it < 0) 0 else it }
        return copy(lineHeight = LINE_HEIGHTS[(i + 1) % LINE_HEIGHTS.size])
    }

    companion object {
        const val MIN_ZOOM = 70
        const val MAX_ZOOM = 200
        const val ZOOM_STEP = 10
        val LINE_HEIGHTS = listOf(1.5f, 1.8f, 2.1f)
    }
}

/** 在线翻译服务。网页地址会被发给它，所以每次都要用户点确认 */
enum class TranslateProvider(val label: String, val host: String) {
    GOOGLE("Google 翻译", "translate.google.com"),
    YANDEX("Yandex 翻译", "translate.yandex.com"),
}

enum class TranslateTarget(val label: String, val google: String, val yandex: String) {
    ZH_CN("简体中文", "zh-CN", "zh"),
    ZH_TW("繁體中文", "zh-TW", "zh"),
    EN("English", "en", "en"),
    JA("日本語", "ja", "ja"),
    KO("한국어", "ko", "ko"),
}

/**
 * v1.7 常用功能自己的小设置（阅读模式样式、翻译服务）。
 * 单独一个 SharedPreferences 文件：不碰 CuteRepository 的 DataStore，也不在启动时读（第一次用到才读）。
 */
class FeaturePrefs(context: Context) {
    private val sp: SharedPreferences by lazy { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    var readerStyle by mutableStateOf(ReaderStyle())
        private set
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        readerStyle = ReaderStyle(
            zoom = sp.getInt("reader_zoom", 100).coerceIn(ReaderStyle.MIN_ZOOM, ReaderStyle.MAX_ZOOM),
            lineHeight = sp.getFloat("reader_line", 1.8f).coerceIn(1.2f, 2.6f),
            theme = enumOr(sp.getString("reader_theme", null), ReaderTheme.AUTO),
            font = enumOr(sp.getString("reader_font", null), ReaderFont.SANS),
        )
    }

    fun updateReaderStyle(s: ReaderStyle) {
        readerStyle = s
        sp.edit().putInt("reader_zoom", s.zoom).putFloat("reader_line", s.lineHeight)
            .putString("reader_theme", s.theme.name).putString("reader_font", s.font.name).apply()
    }

    var translateProvider: TranslateProvider
        get() = enumOr(sp.getString("translate_provider", null), TranslateProvider.GOOGLE)
        set(v) { sp.edit().putString("translate_provider", v.name).apply() }

    var translateTarget: TranslateTarget
        get() = enumOr(sp.getString("translate_target", null), TranslateTarget.ZH_CN)
        set(v) { sp.edit().putString("translate_target", v.name).apply() }

    companion object {
        const val FILE = "umu_features"
        inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
    }
}
