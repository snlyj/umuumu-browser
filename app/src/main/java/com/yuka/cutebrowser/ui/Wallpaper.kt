package com.yuka.cutebrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.yuka.cutebrowser.data.Wallpaper
import com.yuka.cutebrowser.data.WallpaperStore
import com.yuka.cutebrowser.ui.theme.CutePalette
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** 自定义壁纸上盖的遮罩、以及文字底板的不透明度（测试里按最坏情况算过对比度） */
const val TEXT_PLATE_ALPHA = 0.86f

/**
 * 首页壁纸。内置的都是 Canvas 画的（不占 APK 体积、不用解码图片）：只在大小或配色变化时重画，
 * 滚动首页内容不会让它重画。自定义的是一张已经缩小过的 JPEG，异步读取，读完前先显示纯色。
 */
@Composable
fun HomeWallpaper(kind: Wallpaper, stamp: Long, scrim: Float, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    when (kind) {
        Wallpaper.NONE -> Unit
        Wallpaper.CUSTOM -> {
            val ctx = LocalContext.current.applicationContext
            val bmp by produceState<ImageBitmap?>(null, stamp) {
                value = withContext(Dispatchers.IO) { WallpaperStore.load(ctx, stamp) }
            }
            val b = bmp
            if (b != null) {
                Box(modifier.clipToBounds()) {
                    Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    // 遮罩：用主题底色盖在照片上，上浅下深一点，文字永远读得清
                    Box(Modifier.fillMaxSize().background(p.background.copy(alpha = scrim)))
                }
            }
        }
        else -> Canvas(modifier.clipToBounds()) { drawPreset(kind, p) }
    }
}

/** 画一张内置壁纸。所有颜色都从当前配色里取，所以六套主题（含夜间）都自动搭配 */
fun DrawScope.drawPreset(kind: Wallpaper, p: CutePalette) {
    val w = size.width
    val h = size.height
    when (kind) {
        Wallpaper.CANDY -> {
            drawRect(Brush.verticalGradient(listOf(p.background, p.pink.copy(alpha = 0.55f), p.lavender.copy(alpha = 0.6f), p.mint.copy(alpha = 0.55f))))
            // 几团柔光
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.22f), Color.Transparent), Offset(w * 0.85f, h * 0.12f), w * 0.6f), w * 0.6f, Offset(w * 0.85f, h * 0.12f))
            drawCircle(Brush.radialGradient(listOf(p.lavender.copy(alpha = 0.5f), Color.Transparent), Offset(w * 0.1f, h * 0.55f), w * 0.55f), w * 0.55f, Offset(w * 0.1f, h * 0.55f))
            drawCircle(Brush.radialGradient(listOf(p.mint.copy(alpha = 0.6f), Color.Transparent), Offset(w * 0.8f, h * 0.9f), w * 0.6f), w * 0.6f, Offset(w * 0.8f, h * 0.9f))
        }
        Wallpaper.DOTS -> {
            drawRect(p.background)
            val gap = dpx(46f)
            val r = dpx(6.5f)
            var row = 0
            var y = gap / 2
            while (y < h + gap) {
                var x = if (row % 2 == 0) gap / 2 else gap
                var col = 0
                while (x < w + gap) {
                    drawCircle(if ((row + col) % 2 == 0) p.pink else p.lavender, r, Offset(x, y))
                    x += gap; col++
                }
                y += gap * 0.8f; row++
            }
        }
        Wallpaper.HEARTS -> {
            drawRect(p.background)
            val gap = dpx(58f)
            val sz = dpx(17f)
            var row = 0
            var y = gap / 2
            while (y < h + gap) {
                var x = if (row % 2 == 0) gap / 2 else gap
                var col = 0
                while (x < w + gap) {
                    val c = when ((row + col) % 3) { 0 -> p.pink; 1 -> p.lavender; else -> p.mint }
                    val s = if ((row * 3 + col) % 4 == 0) sz * 1.25f else sz
                    withTransform({ translate(x - s / 2, y - s / 2); scale(s, s, Offset.Zero) }) { drawPath(UnitHeart, c) }
                    x += gap; col++
                }
                y += gap * 0.85f; row++
            }
        }
        Wallpaper.STARS -> {
            drawRect(Brush.verticalGradient(listOf(p.background, p.lavender.copy(alpha = 0.45f))))
            // 固定种子：每次画出来位置都一样（不会随重组乱跳）
            var seed = 20240611L
            fun rnd(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % 10000L) / 10000f }
            repeat(34) { i ->
                val x = rnd() * w
                val y = rnd() * h
                val s = dpx(7f + rnd() * 12f)
                val c = when (i % 3) { 0 -> p.accent.copy(alpha = 0.3f); 1 -> p.lavender; else -> p.pink }
                if (i % 4 == 3) drawCircle(c, s * 0.18f, Offset(x, y))
                else withTransform({ translate(x, y); scale(s, s, Offset.Zero) }) { drawPath(UnitSparkle, c) }
            }
        }
        Wallpaper.GINGHAM -> {
            drawRect(p.background)
            val cell = dpx(30f)
            val band = p.pink.copy(alpha = 0.45f)
            var x = 0f
            var i = 0
            while (x < w) { if (i % 2 == 0) drawRect(band, Offset(x, 0f), Size(cell, h)); x += cell; i++ }
            var y = 0f
            i = 0
            while (y < h) { if (i % 2 == 0) drawRect(band, Offset(0f, y), Size(w, cell)); y += cell; i++ }
        }
        Wallpaper.WAVES -> {
            drawRect(Brush.verticalGradient(listOf(p.background, p.mint.copy(alpha = 0.4f))))
            val bands = listOf(p.lavender.copy(alpha = 0.6f) to 0.62f, p.pink.copy(alpha = 0.65f) to 0.76f, p.mint.copy(alpha = 0.7f) to 0.89f)
            bands.forEachIndexed { i, (c, base) ->
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                val step = dpx(12f)
                while (x <= w + step) {
                    path.lineTo(x, h * base + sin(x / w * 2f * PI.toFloat() * (1.2f + i * 0.4f) + i * 1.7f) * dpx(14f))
                    x += step
                }
                path.lineTo(w + step, h); path.close()
                drawPath(path, c)
            }
        }
        else -> drawRect(p.background)
    }
}

private fun DrawScope.dpx(v: Float) = v * density

/** 单位大小的四角星（闪光），和爱心一样平移+缩放着画 */
internal val UnitSparkle: Path by lazy {
    Path().apply {
        moveTo(0.5f, 0f)
        quadraticTo(0.56f, 0.44f, 1f, 0.5f)
        quadraticTo(0.56f, 0.56f, 0.5f, 1f)
        quadraticTo(0.44f, 0.56f, 0f, 0.5f)
        quadraticTo(0.44f, 0.44f, 0.5f, 0f)
        close()
    }
}
