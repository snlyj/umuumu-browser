package com.yuka.cutebrowser.features

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yuka.cutebrowser.MainActivity
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.ui.theme.StrawberryPalette
import java.security.MessageDigest

/**
 * 「添加到主屏幕」：桌面快捷方式，点它就用唔姆唔姆打开这个网页。
 * 图标在本机画（粉色底 + 网站名第一个字 + 小爱心），不去网上拿 favicon。
 */
object HomeShortcut {
    /** 图标上写的字：标题第一个汉字/字母，没有就用域名首字母 */
    fun label(title: String, url: String): String {
        val t = title.trim()
        val first = t.firstOrNull { Character.isLetterOrDigit(it) }
        if (first != null && Character.UnicodeScript.of(first.code) == Character.UnicodeScript.HAN) return first.toString()
        val host = UrlUtils.host(url)
        val h = host.firstOrNull { it.isLetterOrDigit() }
        return (h ?: first ?: '♡').uppercaseChar().toString()
    }

    fun id(url: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return "umu-" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 自适应图标用的位图：108dp 画布里只有中间 72dp 一定可见，所以内容画在中间 */
    fun iconBitmap(label: String, sizePx: Int = 216): Bitmap {
        val p = StrawberryPalette
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        c.drawColor(p.pink.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 中间一个奶白色圆
        paint.color = p.surface.toArgb()
        c.drawCircle(s / 2, s / 2, s * 0.30f, paint)
        // 字
        paint.color = p.accentInk.toArgb()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = s * 0.28f
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(label, s / 2, s / 2 - (fm.ascent + fm.descent) / 2, paint)
        // 右下角小爱心
        paint.color = p.accent.toArgb()
        val r = s * 0.065f
        val cx = s * 0.66f
        val cy = s * 0.66f
        val heart = Path().apply {
            moveTo(cx, cy + r * 1.1f)
            cubicTo(cx - r * 2.0f, cy - r * 0.2f, cx - r * 1.0f, cy - r * 1.6f, cx, cy - r * 0.6f)
            cubicTo(cx + r * 1.0f, cy - r * 1.6f, cx + r * 2.0f, cy - r * 0.2f, cx, cy + r * 1.1f)
            close()
        }
        c.drawPath(heart, paint)
        return bmp
    }

    fun build(context: Context, url: String, title: String): ShortcutInfoCompat {
        val name = title.trim().ifBlank { UrlUtils.host(url) }.take(24)
        // 显式指向 MainActivity；MainActivity.handleIntent 只接受 http(s)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(context, MainActivity::class.java)
        val bmp = iconBitmap(label(title, url))
        val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) IconCompat.createWithAdaptiveBitmap(bmp) else IconCompat.createWithBitmap(bmp)
        return ShortcutInfoCompat.Builder(context, id(url))
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    /** 返回 false = 这个桌面不支持固定快捷方式 */
    fun pin(context: Context, url: String, title: String): Boolean {
        if (!UrlUtils.isWebUrl(url)) return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, build(context, url, title), null) }.getOrDefault(false)
    }
}

