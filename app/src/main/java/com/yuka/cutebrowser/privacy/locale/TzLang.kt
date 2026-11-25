package com.yuka.cutebrowser.privacy.locale

import android.os.LocaleList
import androidx.compose.runtime.Immutable
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * 伪造时区和语言（v1.5）。
 *
 * 全局设置在 SecurityPrefs（tzOn / tz / lang），单个网站的覆盖在 DataStore（[SiteTzLang]，按 eTLD+1）。
 * 取值约定（时区和语言都一样）：
 *  - ""   跟随系统（时区还可能被"严格防指纹 = UTC"、语言还可能被"开着防指纹 = zh-CN"接管，见 fp-js/fingerprint.js）
 *  - "-"  只用在站点覆盖里：这个网站明确用系统真实值
 *  - "*"  只用在时区：每个网站随机一个（同一网站同一次打开 App 内不变）
 *  - 其它  IANA 时区名 / BCP-47 语言标签
 */
@Immutable
data class SiteTzLang(val tz: String? = null, val lang: String? = null) {
    val isEmpty: Boolean get() = tz == null && lang == null
}

@Immutable
data class SpoofConfig(
    val on: Boolean = false,
    val tz: String = "",
    val lang: String = "",
    val sites: Map<String, SiteTzLang> = emptyMap(),
) {
    /** 有没有需要往网页里注入的内容 */
    val hasEffect: Boolean get() = on && (tz.isNotEmpty() || lang.isNotEmpty() || sites.isNotEmpty())

    /** 这个网站（eTLD+1）的语言设置：具体标签、"-"（用真实的）或 ""（没设，由防指纹默认决定） */
    fun langFor(site: String): String = if (!on) "" else sites[site]?.lang ?: lang
    fun tzFor(site: String): String = if (!on) "" else sites[site]?.tz ?: tz
}

object TzLang {
    const val REAL = "-"
    const val RANDOM = "*"

    class Item(val id: String, val label: String)

    /** 常用时区放最前面：id → 中文名 */
    val COMMON_ZONES: List<Item> = listOf(
        Item("Asia/Shanghai", "上海（中国）"), Item("Asia/Hong_Kong", "香港"), Item("Asia/Taipei", "台北"),
        Item("Asia/Tokyo", "东京（日本）"), Item("Asia/Seoul", "首尔（韩国）"), Item("Asia/Singapore", "新加坡"),
        Item("America/Los_Angeles", "西雅图 / Seattle / 洛杉矶（美国西部）"),
        Item("America/Denver", "丹佛（美国山地）"), Item("America/Chicago", "芝加哥（美国中部）"), Item("America/New_York", "纽约（美国东部）"),
        Item("Europe/London", "伦敦（英国）"), Item("Europe/Paris", "巴黎（法国）"), Item("Europe/Berlin", "柏林（德国）"),
        Item("Europe/Moscow", "莫斯科（俄罗斯）"), Item("Asia/Kolkata", "印度"), Item("Asia/Dubai", "迪拜"),
        Item("Australia/Sydney", "悉尼（澳大利亚）"), Item("Pacific/Auckland", "奥克兰（新西兰）"), Item("America/Sao_Paulo", "圣保罗（巴西）"),
        Item("UTC", "UTC（世界标准时间）"),
    )

    /** 「每个网站随机」用的候选：常见、互相差得比较多的时区 */
    val RANDOM_POOL: List<String> = listOf(
        "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul", "Asia/Singapore", "Asia/Kolkata", "Asia/Dubai", "Europe/Moscow", "Europe/Istanbul",
        "Europe/Berlin", "Europe/Paris", "Europe/London", "Europe/Madrid", "America/Sao_Paulo", "America/New_York", "America/Chicago",
        "America/Denver", "America/Los_Angeles", "America/Toronto", "America/Mexico_City", "Australia/Sydney", "Pacific/Auckland", "Africa/Johannesburg",
    )

    val LANGUAGES: List<Item> = listOf(
        Item("zh-CN", "简体中文（中国）"), Item("zh-TW", "繁體中文（台湾）"), Item("en-US", "English（美国）"), Item("en-GB", "English（英国）"),
        Item("ja-JP", "日本語（日本）"), Item("ko-KR", "한국어（韩国）"), Item("fr-FR", "Français（法国）"), Item("de-DE", "Deutsch（德国）"),
        Item("es-ES", "Español（西班牙）"), Item("ru-RU", "Русский（俄罗斯）"),
    )

    /** 一键搭配 */
    class Preset(val emoji: String, val label: String, val tz: String, val lang: String)
    val PRESETS: List<Preset> = listOf(
        Preset("🇺🇸", "美国西部：西雅图 + English", "America/Los_Angeles", "en-US"),
        Preset("🇯🇵", "日本：东京 + 日本語", "Asia/Tokyo", "ja-JP"),
        Preset("🇬🇧", "英国：伦敦 + English", "Europe/London", "en-GB"),
        Preset("🇰🇷", "韩国：首尔 + 한국어", "Asia/Seoul", "ko-KR"),
        Preset("🇩🇪", "德国：柏林 + Deutsch", "Europe/Berlin", "de-DE"),
    )

    private val zoneIds: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }
    private val region = Regex("^(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+")

    /** 全部可选时区（Area/City 形式 + UTC），不含常用那几个，按名字排序 */
    val allZones: List<String> by lazy {
        val common = COMMON_ZONES.mapTo(HashSet()) { it.id }
        zoneIds.filter { region.matches(it) && it !in common }.sorted()
    }

    fun isValidZone(id: String): Boolean = id == "UTC" || id in zoneIds

    /** 时区的当前 UTC 偏移，给列表显示用，如 "UTC+8" / "UTC-4" / "UTC+5:30" */
    fun offsetLabel(id: String, nowMs: Long = System.currentTimeMillis()): String {
        // 走 IANA tz 数据库（含夏令时），不用写死的偏移
        val instant = java.time.Instant.ofEpochMilli(nowMs)
        val zone = runCatching { java.time.ZoneId.of(id) }.getOrNull() ?: return "UTC"
        val sec = zone.rules.getOffset(instant).totalSeconds
        val sign = if (sec < 0) "-" else "+"
        val absSec = abs(sec)
        val h = absSec / 3600
        val m = (absSec % 3600) / 60
        return "UTC$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    fun zoneLabel(id: String): String = when (id) {
        "" -> "跟随系统"
        RANDOM -> "每个网站随机"
        REAL -> "用系统真实时区"
        else -> {
            val c = COMMON_ZONES.firstOrNull { it.id == id }
            if (c != null) "${c.label} ${offsetLabel(id)}" else "$id ${offsetLabel(id)}"
        }
    }

    fun langLabel(tag: String): String = when (tag) {
        "" -> "跟随系统"
        REAL -> "用系统真实语言"
        else -> LANGUAGES.firstOrNull { it.id == tag }?.label?.let { "$it · $tag" } ?: tag
    }

    /** 搜索：id、中文名、偏移都能搜（忽略大小写、下划线当空格） */
    fun searchZones(query: String): List<Item> {
        val raw = query.trim()
        val q = raw.lowercase().replace(' ', '_')
        val all = COMMON_ZONES + allZones.map { Item(it, "") }
        if (q.isEmpty()) return all
        return all.filter {
            it.id.lowercase().contains(q) ||
                it.label.lowercase().replace(' ', '_').contains(q) ||
                offsetLabel(it.id).lowercase().contains(q)
        }
    }

    private val tagRegex = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$")

    /** 把用户输入的语言标签整理成规范写法（ja_jp → ja-JP）；不合法返回 null */
    fun normalizeLang(input: String): String? {
        val t = input.trim().replace('_', '-')
        if (!tagRegex.matches(t)) return null
        val loc = Locale.forLanguageTag(t)
        if (loc.language.isEmpty()) return null
        return loc.toLanguageTag().takeIf { it != "und" }
    }

    /** 和 navigator.languages 一致的列表：[ja-JP, ja] / [en-US, en] / [fr] */
    fun languageList(tag: String): List<String> {
        val base = tag.substringBefore('-')
        return if (base == tag) listOf(tag) else listOf(tag, base)
    }

    /** Accept-Language 请求头（Chrome 的写法：ja-JP,ja;q=0.9） */
    fun acceptLanguage(tag: String): String {
        val l = languageList(tag)
        return l.withIndex().joinToString(",") { (i, t) ->
            if (i == 0) t else "$t;q=${"%.1f".format(Locale.ROOT, 1.0 - 0.1 * i)}"
        }
    }
}

/**
 * WebView（Chromium）的 Accept-Language 来自进程的默认 LocaleList（AwContents 创建时、挂到窗口时读取），
 * 所以要让请求头和 JS 一致，就得在创建 WebView 之前把默认 locale 换掉。只改进程默认值，界面文字是写死的中文，不受影响。
 */
object ProcessLocale {
    private var original: LocaleList? = null
    @Volatile var current: String? = null
        private set

    /** 系统真实的语言标签（没有伪造时），比如 zh-CN */
    fun systemTag(): String = (original ?: LocaleList.getDefault()).get(0).toLanguageTag()

    /** @return 有没有真的变化 */
    @Synchronized
    fun apply(tag: String?): Boolean {
        if (original == null) original = LocaleList.getDefault()
        if (tag == current) return false
        current = tag
        LocaleList.setDefault(if (tag == null) original!! else LocaleList(Locale.forLanguageTag(tag)))
        return true
    }
}

/** 一个网站此刻应该用什么语言 / 请求头：把设置、防指纹默认值（zh-CN）合起来算 */
object LangPolicy {
    /** 防指纹开着、没选语言时统一成 zh-CN；返回 null = 用系统真实语言 */
    fun effective(cfg: SpoofConfig, antiFp: Boolean, fpExempt: Boolean, site: String): String? {
        val v = cfg.langFor(site)
        return when {
            v == TzLang.REAL -> null
            v.isNotEmpty() -> v
            antiFp && !fpExempt -> "zh-CN"
            else -> null
        }
    }

    /** 进程级默认语言（WebView 原生请求头用）：全局设置 > 防指纹的 zh-CN > 系统 */
    fun processLang(cfg: SpoofConfig, antiFp: Boolean): String? =
        effective(cfg.copy(sites = emptyMap()), antiFp, false, "")
}
