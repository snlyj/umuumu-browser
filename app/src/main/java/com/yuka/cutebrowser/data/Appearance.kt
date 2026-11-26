package com.yuka.cutebrowser.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** 首页壁纸：内置的都是 Compose Canvas 画的（不占 APK 体积），CUSTOM 是用户从相册选的图 */
enum class Wallpaper(val label: String, val emoji: String) {
    NONE("纯色", "⬜"),
    CANDY("棉花糖", "🍬"),
    DOTS("波点", "⚪"),
    HEARTS("小爱心", "💗"),
    STARS("星星", "⭐"),
    GINGHAM("格子", "🧺"),
    WAVES("云朵波浪", "☁️"),
    CUSTOM("我的照片", "🖼️"),
}

enum class Mascot(val label: String, val emoji: String) {
    BUNNY("兔兔", "🐰"),
    CAT("猫猫", "🐱"),
    RIBBON("蝴蝶结", "🎀"),
    OFF("不显示", "🚫"),
}

/** 网页加载进度条的样式 */
enum class ProgressStyle(val label: String, val emoji: String) {
    BUNNY("兔兔", "🐰"),
    HEARTS("爱心", "💗"),
    RIBBON("丝带", "🎀"),
}

/**
 * 外观设置（v1.7）：跟主题分开存，整份编码成一个 JSON 放在 DataStore 的一个 key 里。
 * 默认值就是 1.6 的样子（兔兔、无壁纸、动画全开、没有震动和声音）。
 */
@Immutable
data class AppearancePrefs(
    val followSystem: Boolean = false,
    val wallpaper: Wallpaper = Wallpaper.NONE,
    /** 自定义壁纸文件的版本号（文件名里带它；换一张图就变，让界面重新加载） */
    val wallpaperStamp: Long = 0L,
    /** 自定义壁纸上盖的遮罩浓度（0.55–0.9）。保证首页文字在任意照片上都看得清 */
    val scrim: Float = DEFAULT_SCRIM,
    val mascot: Mascot = Mascot.BUNNY,
    val progress: ProgressStyle = ProgressStyle.BUNNY,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = false,
    val sound: Boolean = false,
) {
    companion object {
        const val MIN_SCRIM = 0.55f
        const val MAX_SCRIM = 0.9f
        const val DEFAULT_SCRIM = 0.7f
    }
}

internal fun encodeAppearance(a: AppearancePrefs): String = JSONObject()
    .put("follow", a.followSystem).put("wp", a.wallpaper.name).put("wps", a.wallpaperStamp)
    .put("scrim", a.scrim.toDouble()).put("mascot", a.mascot.name).put("prog", a.progress.name)
    .put("rm", a.reduceMotion).put("hap", a.haptics).put("snd", a.sound).toString()

/** 坏数据/缺字段一律回到默认值，不会崩 */
internal fun decodeAppearance(s: String?): AppearancePrefs {
    val d = AppearancePrefs()
    if (s.isNullOrBlank()) return d
    return runCatching {
        val o = JSONObject(s)
        val wp = Wallpaper.entries.firstOrNull { it.name == o.optString("wp") } ?: d.wallpaper
        val stamp = o.optLong("wps", 0L)
        AppearancePrefs(
            followSystem = o.optBoolean("follow", d.followSystem),
            // 记录说是自定义壁纸、却没有文件版本号 → 当作没有壁纸
            wallpaper = if (wp == Wallpaper.CUSTOM && stamp <= 0L) Wallpaper.NONE else wp,
            wallpaperStamp = stamp,
            scrim = o.optDouble("scrim", d.scrim.toDouble()).toFloat()
                .let { if (it.isNaN()) d.scrim else it.coerceIn(AppearancePrefs.MIN_SCRIM, AppearancePrefs.MAX_SCRIM) },
            mascot = Mascot.entries.firstOrNull { it.name == o.optString("mascot") } ?: d.mascot,
            progress = ProgressStyle.entries.firstOrNull { it.name == o.optString("prog") } ?: d.progress,
            reduceMotion = o.optBoolean("rm", d.reduceMotion),
            haptics = o.optBoolean("hap", d.haptics),
            sound = o.optBoolean("snd", d.sound),
        )
    }.getOrDefault(d)
}
