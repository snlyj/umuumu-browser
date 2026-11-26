package com.yuka.cutebrowser.features

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.yuka.cutebrowser.ui.theme.CutePalette
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** Readability 抽出来、清洗过的正文 */
@Immutable
data class ReaderArticle(
    val title: String,
    val byline: String,
    val site: String,
    val lang: String,
    val rtl: Boolean,
    val length: Int,
    /** 白名单清洗过的正文 HTML（只有段落/标题/列表/图片/链接/表格之类） */
    val html: String,
)

/** 阅读页的配色（从 App 主题或阅读主题来） */
@Immutable
data class ReaderColors(val bg: Color, val text: Color, val sub: Color, val accent: Color, val soft: Color, val chip: Color)

object ReaderHtml {
    /** 正文 HTML 的上限：再大就是网页在捣乱，或者根本不是文章 */
    const val MAX_HTML_CHARS = 3_000_000

    /** evaluateJavascript 的返回值：JS 字符串的 JSON 表示（"\"{...}\""）或 "null" */
    fun parseResult(raw: String?): ReaderArticle? = runCatching {
        if (raw == null || raw == "null" || raw.length > MAX_HTML_CHARS * 2) return null
        val inner = JSONTokener(raw).nextValue() as? String ?: return null
        val o = JSONObject(inner)
        val html = o.optString("html")
        if (html.isBlank() || html.length > MAX_HTML_CHARS) return null
        ReaderArticle(
            title = o.optString("title").trim(),
            byline = o.optString("byline").trim(),
            site = o.optString("site").trim(),
            lang = o.optString("lang").trim().takeIf { LANG.matches(it) }.orEmpty(),
            rtl = o.optString("dir") == "rtl",
            length = o.optInt("length", 0).coerceAtLeast(0),
            html = html,
        )
    }.getOrNull()

    private val LANG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,3}")

    fun colors(theme: ReaderTheme, p: CutePalette): ReaderColors = when (theme) {
        ReaderTheme.AUTO -> ReaderColors(p.background, p.text, p.subText, p.accentInk, p.pink.copy(alpha = 0.55f), p.pink)
        ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF7EEDD), Color(0xFF5B4636), Color(0xFF7D6650), Color(0xFFA34A64), Color(0xFFEDE0C6), Color(0xFFEFDCC8))
        ReaderTheme.NIGHT -> ReaderColors(Color(0xFF1E1B2C), Color(0xFFE9E2F7), Color(0xFFADA5C9), Color(0xFFCBB8FF), Color(0xFF2C2842), Color(0xFF3A3358))
    }

    /** 大约几分钟读完：中日韩按每分钟 400 字，其他按 1000 个字符（约 200 个英文词） */
    fun minutes(a: ReaderArticle): Int {
        val cjk = a.lang.startsWith("zh") || a.lang.startsWith("ja") || a.lang.startsWith("ko") ||
            a.title.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val perMin = if (cjk) 400 else 1000
        return maxOf(1, (a.length + perMin / 2) / perMin)
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun css(c: Color): String {
        val argb = c.toArgb()
        val a = (argb ushr 24) and 0xFF
        return if (a == 0xFF) String.format(Locale.US, "#%06X", argb and 0xFFFFFF)
        else String.format(Locale.US, "rgba(%d,%d,%d,%.2f)", (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, a / 255f)
    }

    /**
     * 整页 HTML。显示它的 WebView 关着 JS；CSP 再兜一层：只允许内联样式和 https/data 图片，
     * 不允许脚本、表单、字体、媒体、子框架。
     */
    fun build(a: ReaderArticle, style: ReaderStyle, colors: ReaderColors, host: String): String {
        val c = colors
        val meta = buildList {
            if (a.byline.isNotBlank()) add(escape(a.byline.take(80)))
            add("约 ${minutes(a)} 分钟读完")
        }.joinToString("<span class=\"dot\">·</span>")
        val site = escape(a.site.ifBlank { host }.take(60))
        val lh = String.format(Locale.US, "%.2f", style.lineHeight)
        return """<!DOCTYPE html>
<html lang="${escape(a.lang.ifBlank { "zh-CN" })}" dir="${if (a.rtl) "rtl" else "ltr"}"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
<meta name="referrer" content="strict-origin">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html{-webkit-text-size-adjust:100%}
body{margin:0;background:${css(c.bg)};color:${css(c.text)};font-family:${style.font.css};line-height:$lh;overflow-wrap:anywhere;word-break:normal}
.wrap{max-width:680px;margin:0 auto;padding:16px 22px 56px}
.site{display:inline-block;background:${css(c.chip)};color:${css(c.accent)};border-radius:999px;padding:3px 12px;font-size:.78em;font-weight:600}
h1.title{font-size:1.55em;line-height:1.35;margin:.55em 0 .35em;letter-spacing:.01em}
.meta{color:${css(c.sub)};font-size:.84em}
.meta .dot{margin:0 .45em}
.bow{text-align:center;color:${css(c.accent)};margin:1.1em 0 1.3em;letter-spacing:.3em;font-size:.9em}
article{font-size:1.06em}
article p{margin:0 0 1em}
article img{max-width:100%;height:auto;border-radius:16px;display:block;margin:1em auto}
article figure{margin:1.2em 0}
article figcaption{color:${css(c.sub)};font-size:.84em;text-align:center;margin-top:-.4em}
article a{color:${css(c.accent)};text-decoration:none;border-bottom:1px dashed ${css(c.accent)}}
article blockquote{margin:1em 0;padding:.6em 1em;background:${css(c.soft)};border-left:4px solid ${css(c.accent)};border-radius:0 14px 14px 0}
article pre{background:${css(c.soft)};padding:12px 14px;border-radius:14px;overflow-x:auto;font-size:.85em;line-height:1.5;white-space:pre}
article code{font-family:ui-monospace,monospace;font-size:.92em}
article table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;margin:1em 0}
article td,article th{border:1px solid ${css(c.soft)};padding:4px 8px}
article h1,article h2,article h3,article h4{line-height:1.4;margin:1.4em 0 .6em}
article h2:before{content:"\2740  ";color:${css(c.accent)}}
article hr{border:0;border-top:2px dotted ${css(c.soft)};margin:1.6em 0}
article ul,article ol{padding-inline-start:1.4em}
article li{margin:.3em 0}
.end{text-align:center;color:${css(c.sub)};font-size:.84em;margin-top:2.6em}
</style></head><body><div class="wrap">
<span class="site">$site</span>
<h1 class="title">${escape(a.title.ifBlank { host })}</h1>
<div class="meta">$meta</div>
<div class="bow">· ♡ ·</div>
<article>${a.html}</article>
<div class="end">🐰 读完啦～ 原文来自 ${escape(host)}</div>
</div></body></html>"""
    }
}
