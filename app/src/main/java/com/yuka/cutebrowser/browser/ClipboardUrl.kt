package com.yuka.cutebrowser.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 从剪贴板里安全地取出一段文字 / 一个可打开的 http(s) 网址。
 * 任何形态的 ClipData、系统在后台读剪贴板时的 SecurityException、超长内容，都不能把 App 打崩。
 */
object ClipboardUrl {
    const val MAX_CHARS = 8 * 1024

    fun read(cm: ClipboardManager, context: Context? = null): String? =
        read({ if (!cm.hasPrimaryClip()) null else cm.primaryClip }, context)

    /** 方便测试：把「取 ClipData」做成函数，SecurityException 等都会被吞掉 */
    fun read(fetch: () -> ClipData?, context: Context? = null): String? =
        runCatching { textFromClip(fetch(), context) }.getOrNull()

    fun textFromClip(clip: ClipData?, context: Context? = null): String? = runCatching {
        if (clip == null || clip.itemCount <= 0) return@runCatching null
        val item = clip.getItemAt(0) ?: return@runCatching null
        textFromItem(item, context)
    }.getOrNull()

    fun textFromItem(item: ClipData.Item, context: Context? = null): String? = runCatching {
        val text = item.text?.toString()
        val uri = runCatching { item.uri?.toString() }.getOrNull()
        val html = runCatching { item.htmlText }.getOrNull()
        val coerced = if (context != null) runCatching { item.coerceToText(context)?.toString() }.getOrNull() else null
        val parts = listOf(text, uri, html, coerced).map { it?.trimToCap() }.filter { !it.isNullOrEmpty() }.map { it!! }
        parts.firstOrNull { extractOpenable(it) != null } ?: parts.firstOrNull()
    }.getOrNull()

    /** 一段剪贴板文字里若有恰好能打开的一个网页地址，返回打开用的 https?/http 网址 */
    fun extractOpenable(raw: String?): String? = runCatching {
        val text = raw?.trimToCap()?.trim().orEmpty()
        if (text.isEmpty()) return@runCatching null
        val url = UrlUtils.asDirectUrl(text)
            ?: text.lineSequence().firstOrNull()?.trim()?.let { UrlUtils.asDirectUrl(it) }
            ?: UrlUtils.extractSingleHttpUrl(text)?.let { UrlUtils.asDirectUrl(it) }
        url?.takeIf { UrlUtils.isWebUrl(it) }
    }.getOrNull()

    private fun String.trimToCap(): String = if (length <= MAX_CHARS) this else substring(0, MAX_CHARS)
}

/** 地址栏输入：粘贴带换行的链接时，不能把换行交给 singleLine 的 BasicTextField（部分 Compose 版本会闪退） */
object AddressInput {
    const val MAX_CHARS = ClipboardUrl.MAX_CHARS

    fun sanitize(v: TextFieldValue): TextFieldValue = runCatching {
        val cleaned = sanitizeText(v.text)
        val max = cleaned.length
        val start = v.selection.start.coerceIn(0, max)
        val end = v.selection.end.coerceIn(0, max)
        TextFieldValue(cleaned, TextRange(minOf(start, end), maxOf(start, end)))
    }.getOrElse { TextFieldValue("") }

    fun sanitizeText(raw: String): String {
        val out = StringBuilder(raw.length.coerceAtMost(MAX_CHARS))
        for (c in raw) {
            if (out.length >= MAX_CHARS) break
            when {
                c == '\n' || c == '\r' || c == '\t' -> out.append(' ')
                c.code in 0..31 || c.code == 127 -> { /* 丢掉其它控制字符 */ }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
