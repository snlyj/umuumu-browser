package com.yuka.cutebrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yuka.cutebrowser.data.Mascot
import com.yuka.cutebrowser.ui.theme.CutePalette
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import com.yuka.cutebrowser.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * 首页吉祥物：全部是 Compose Canvas 自己画的矢量图（没有位图、没有第三方素材）。
 * 待机动画只有一个无限循环的 0→1 相位，读取发生在绘制阶段（不会触发重组）；点一下会蹦一下。
 * 「减少动画」打开或系统关了动画时完全静止，点按也不蹦。
 */
@Composable
fun MascotView(kind: Mascot, modifier: Modifier = Modifier) {
    if (kind == Mascot.OFF) return
    val reduce = LocalReduceMotion.current
    val hop = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val tapMod = if (reduce) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures {
            scope.launch {
                hop.snapTo(-14f)
                hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 300f))
            }
        }
    }
    val inner = modifier
        .semantics { contentDescription = "吉祥物${kind.label}" }
        .then(tapMod)
        .graphicsLayer { translationY = hop.value * density }
    when (kind) {
        Mascot.BUNNY -> BunnyMascot(inner)
        Mascot.CAT -> CatMascot(inner)
        Mascot.RIBBON -> RibbonMascot(inner)
        Mascot.OFF -> Unit
    }
}

/** 0→1 循环的待机相位；减少动画时恒为 0 */
@Composable
private fun idlePhase(periodMs: Int): State<Float> {
    if (LocalReduceMotion.current) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "idle").animateFloat(
        0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "phase",
    )
}

/** 眨眼：一个周期里只有最后一小段闭眼，返回眼睛的纵向缩放（1 = 睁开） */
internal fun blinkScale(phase: Float): Float {
    val a = 0.90f
    val b = 0.97f
    if (phase < a || phase > b) return 1f
    val t = (phase - a) / (b - a)           // 0..1
    return 1f - 0.9f * (1f - kotlin.math.abs(2f * t - 1f)) // 先闭后睁
}

private val ink = Color(0xFF5A4A5E)
private val blushColor = Color(0xFFFFB3C7)

private fun fur(p: CutePalette, cream: Boolean = false): Color =
    if (p.isDark) Color(0xFFF4F0FF) else if (cream) Color(0xFFFFF4E4) else Color.White

// ---------------------------------------------------------------- 猫猫

private val earL: Path by lazy {
    Path().apply { moveTo(0.22f, 0.52f); lineTo(0.26f, 0.15f); lineTo(0.47f, 0.38f); close() }
}
private val earR: Path by lazy {
    Path().apply { moveTo(0.78f, 0.52f); lineTo(0.74f, 0.15f); lineTo(0.53f, 0.38f); close() }
}
private val earInL: Path by lazy {
    Path().apply { moveTo(0.27f, 0.45f); lineTo(0.29f, 0.24f); lineTo(0.41f, 0.38f); close() }
}
private val earInR: Path by lazy {
    Path().apply { moveTo(0.73f, 0.45f); lineTo(0.71f, 0.24f); lineTo(0.59f, 0.38f); close() }
}
private val catMouth: Path by lazy {
    Path().apply {
        moveTo(0.44f, 0.705f); quadraticTo(0.47f, 0.74f, 0.5f, 0.69f)
        quadraticTo(0.53f, 0.74f, 0.56f, 0.705f)
    }
}

@Composable
fun CatMascot(modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    val phase = idlePhase(4200)
    val white = fur(p, cream = true)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ph = phase.value
        drawCircle(p.lavender, radius = w * 0.46f, center = Offset(w / 2, h * 0.58f))
        // 尾巴：绕根部左右轻摆（一个周期摆两下）
        val tail = sin(ph * 2f * PI.toFloat() * 2f) * 9f
        rotate(degrees = 28f + tail, pivot = Offset(w * 0.74f, h * 0.84f)) {
            drawRoundRect(white, Offset(w * 0.70f, h * 0.52f), Size(w * 0.09f, h * 0.34f), CornerRadius(w * 0.045f))
            drawCircle(p.accent, w * 0.045f, Offset(w * 0.745f, h * 0.55f))
        }
        withTransform({ scale(w, h, Offset.Zero) }) {
            drawPath(earL, white); drawPath(earR, white)
            drawPath(earInL, Color(0xFFFFC2D1)); drawPath(earInR, Color(0xFFFFC2D1))
        }
        drawOval(white, Offset(w * 0.17f, h * 0.36f), Size(w * 0.66f, h * 0.52f))
        // 眼睛（会眨）
        val bs = blinkScale(ph)
        for (cx in listOf(0.37f, 0.63f)) {
            val ry = h * 0.042f * bs
            drawOval(ink, Offset(w * cx - w * 0.03f, h * 0.60f - ry), Size(w * 0.06f, ry * 2f))
            if (bs > 0.6f) drawCircle(Color.White, w * 0.011f, Offset(w * cx + w * 0.008f, h * 0.585f))
        }
        drawOval(blushColor.copy(alpha = 0.8f), Offset(w * 0.22f, h * 0.66f), Size(w * 0.12f, h * 0.06f))
        drawOval(blushColor.copy(alpha = 0.8f), Offset(w * 0.66f, h * 0.66f), Size(w * 0.12f, h * 0.06f))
        drawCircle(p.accent, w * 0.022f, Offset(w * 0.5f, h * 0.665f))
        withTransform({ scale(w, h, Offset.Zero) }) {
            drawPath(catMouth, ink, style = Stroke(width = 0.012f, cap = StrokeCap.Round))
        }
        // 胡须
        val wc = ink.copy(alpha = 0.45f)
        val sw = w * 0.008f
        for (dy in listOf(-0.02f, 0.015f)) {
            drawLine(wc, Offset(w * 0.20f, h * (0.665f + dy)), Offset(w * 0.04f, h * (0.64f + dy * 2f)), sw, StrokeCap.Round)
            drawLine(wc, Offset(w * 0.80f, h * (0.665f + dy)), Offset(w * 0.96f, h * (0.64f + dy * 2f)), sw, StrokeCap.Round)
        }
        // 小领结
        val by = h * 0.895f
        drawCircle(p.accent, w * 0.05f, Offset(w * 0.44f, by))
        drawCircle(p.accent, w * 0.05f, Offset(w * 0.56f, by))
        drawCircle(lerp(p.accent, Color.Black, 0.2f), w * 0.028f, Offset(w * 0.5f, by))
    }
}

// ---------------------------------------------------------------- 蝴蝶结小精灵

private val loopL: Path by lazy {
    Path().apply {
        moveTo(0.5f, 0.50f)
        cubicTo(0.30f, 0.14f, 0.03f, 0.18f, 0.05f, 0.48f)
        cubicTo(0.07f, 0.78f, 0.32f, 0.84f, 0.5f, 0.50f)
        close()
    }
}
private val loopR: Path by lazy {
    Path().apply {
        moveTo(0.5f, 0.50f)
        cubicTo(0.70f, 0.14f, 0.97f, 0.18f, 0.95f, 0.48f)
        cubicTo(0.93f, 0.78f, 0.68f, 0.84f, 0.5f, 0.50f)
        close()
    }
}
private val tailL: Path by lazy {
    Path().apply { moveTo(0.45f, 0.58f); lineTo(0.27f, 0.95f); lineTo(0.40f, 0.88f); lineTo(0.52f, 0.60f); close() }
}
private val tailR: Path by lazy {
    Path().apply { moveTo(0.55f, 0.58f); lineTo(0.73f, 0.95f); lineTo(0.60f, 0.88f); lineTo(0.48f, 0.60f); close() }
}

@Composable
fun RibbonMascot(modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    val phase = idlePhase(3600)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ph = phase.value
        val bob = sin(ph * 2f * PI.toFloat()) * h * 0.025f
        val tilt = sin(ph * 2f * PI.toFloat() + 1.2f) * 3f
        drawCircle(p.pink, radius = w * 0.46f, center = Offset(w / 2, h * 0.56f))
        val body = p.accent
        val dark = lerp(p.accent, Color.Black, 0.16f)
        val light = lerp(p.accent, Color.White, 0.55f)
        withTransform({
            translate(0f, bob)
            rotate(tilt, Offset(w / 2, h * 0.55f))
            scale(w, h, Offset.Zero)
        }) {
            drawPath(tailL, dark); drawPath(tailR, dark)
            drawPath(loopL, body); drawPath(loopR, body)
        }
        // 蝴蝶结里面的高光 + 结 + 脸
        withTransform({
            translate(0f, bob)
            rotate(tilt, Offset(w / 2, h * 0.55f))
        }) {
            drawCircle(light.copy(alpha = 0.7f), w * 0.05f, Offset(w * 0.22f, h * 0.36f))
            drawCircle(light.copy(alpha = 0.7f), w * 0.05f, Offset(w * 0.78f, h * 0.36f))
            // 结 + 脸
            val c = Offset(w * 0.5f, h * 0.52f)
            drawCircle(dark, w * 0.16f, c)
            drawCircle(body, w * 0.145f, c)
            val bs = blinkScale(ph)
            for (dx in listOf(-0.055f, 0.055f)) {
                val ry = h * 0.026f * bs
                drawOval(ink, Offset(c.x + w * dx - w * 0.018f, c.y - h * 0.01f - ry), Size(w * 0.036f, ry * 2f))
            }
            drawOval(Color.White.copy(alpha = 0.55f), Offset(c.x - w * 0.115f, c.y + h * 0.02f), Size(w * 0.06f, h * 0.035f))
            drawOval(Color.White.copy(alpha = 0.55f), Offset(c.x + w * 0.055f, c.y + h * 0.02f), Size(w * 0.06f, h * 0.035f))
            drawArc(ink, 20f, 140f, false, Offset(c.x - w * 0.025f, c.y + h * 0.012f), Size(w * 0.05f, h * 0.04f), style = Stroke(w * 0.01f, cap = StrokeCap.Round))
        }
    }
}
