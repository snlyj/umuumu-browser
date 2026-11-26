package com.yuka.cutebrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.data.ProgressStyle
import com.yuka.cutebrowser.ui.theme.LocalAppearance
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import com.yuka.cutebrowser.ui.theme.LocalFeedback
import com.yuka.cutebrowser.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/** 按下会"duang"一下的果冻按钮 */
@Composable
fun JellyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    background: Color = LocalCutePalette.current.pink,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalCutePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    PressFeedback(pressed)
    // 缩放/透明度只在绘制阶段读取，按下时不会重组；减少动画时直接到位，不回弹
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.8f else 1f,
        animationSpec = if (reduce) snap() else spring(dampingRatio = 0.32f, stiffness = 600f),
        label = "jelly",
    )
    // 外层至少 48dp（无障碍的最小点击区域），里面的"软糖"仍是 size 大小，外观不变
    val hit = maxOf(size, MinTouchTarget)
    Box(
        modifier = modifier
            .size(hit)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = scale.value
                    scaleY = s
                    scaleX = 1f - (1f - s) * 0.5f
                    alpha = if (enabled) 1f else 0.4f
                }
                .shadow(if (enabled) 5.dp else 0.dp, shape, ambientColor = p.accent, spotColor = p.accent)
                .clip(shape)
                .background(background),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** 按下的一瞬间给一点触感/声音（设置里默认关）。没开的时候这里什么都不做，也不创建协程。 */
@Composable
private fun PressFeedback(pressed: Boolean) {
    val fb = LocalFeedback.current
    if (fb != null && fb.active) {
        LaunchedEffect(pressed) { if (pressed) fb.tap() }
    }
}

/** 无障碍建议的最小点击区域 */
val MinTouchTarget = 48.dp

/** 通用的按压回弹 Modifier，用在卡片/贴纸上 */
@Composable
fun Modifier.bouncyClick(onClick: () -> Unit): Modifier = bouncy { clickableWith(it, Role.Button, onClick) }

/** 开关行：和 [bouncyClick] 一样的回弹，但给读屏软件报"开关 / 已开启"，而不是普通按钮 */
@Composable
fun Modifier.bouncySwitch(checked: Boolean, onToggle: () -> Unit): Modifier =
    bouncy { toggleableWith(it, checked, onToggle) }

@Composable
private fun Modifier.bouncy(attach: Modifier.(MutableInteractionSource) -> Modifier): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    PressFeedback(pressed)
    val scale = animateFloatAsState(
        if (pressed) 0.9f else 1f, if (reduce) snap() else spring(dampingRatio = 0.35f, stiffness = 500f), label = "bounce",
    )
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value } // 只在绘制阶段读取动画值
        .attach(interaction)
}

private fun Modifier.clickableWith(i: MutableInteractionSource, role: Role, onClick: () -> Unit) =
    clickable(interactionSource = i, indication = null, role = role, onClick = onClick)

private fun Modifier.toggleableWith(i: MutableInteractionSource, checked: Boolean, onToggle: () -> Unit) =
    toggleable(value = checked, interactionSource = i, indication = null, role = Role.Switch, onValueChange = { onToggle() })

/**
 * 可爱的加载进度条：兔兔（默认）/ 爱心 / 丝带，在设置里选。
 * progress/visible 用 lambda 传入，真正读取状态的地方只在这个小组件里，
 * 而且进度和动画相位只在 layout/draw 阶段读取——页面加载时整个界面不会因为进度变化而重组。
 * 「减少动画」时没有无限循环的动画，只剩进度本身。
 */
@Composable
fun BunnyProgressBar(
    progress: () -> Int,
    visible: () -> Boolean,
    modifier: Modifier = Modifier,
    style: ProgressStyle = LocalAppearance.current.progress,
) {
    val p = LocalCutePalette.current
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(visible(), modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val fraction = animateFloatAsState(
            (progress().coerceIn(0, 100)) / 100f, if (reduce) snap() else tween(250), label = "progress",
        )
        when (style) {
            ProgressStyle.BUNNY -> BunnyBar(fraction, p, reduce)
            ProgressStyle.HEARTS -> HeartsBar(fraction, p, reduce)
            ProgressStyle.RIBBON -> RibbonBar(fraction, p, reduce)
        }
    }
}

@Composable
private fun BunnyBar(fraction: State<Float>, p: com.yuka.cutebrowser.ui.theme.CutePalette, reduce: Boolean) {
    val hopY: State<Float> = if (reduce) remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    else rememberInfiniteTransition(label = "hop").animateFloat(
        initialValue = 0f, targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(280, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hopY",
    )
    val track = p.pink.copy(alpha = 0.6f)
    val fill = remember(p) { Brush.horizontalGradient(listOf(p.lavender, p.accent)) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 18.dp)
            .drawBehind {
                val h = 7.dp.toPx()
                val top = size.height - 3.dp.toPx() - h
                val r = CornerRadius(h / 2)
                drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
                val w = size.width * fraction.value
                if (w > 0f) drawRoundRect(fill, Offset(0f, top), Size(maxOf(w, h), h), r)
            },
    ) {
        val trackPx = constraints.maxWidth.toFloat()
        val bunnyPx = with(LocalDensity.current) { 18.dp.toPx() }
        Text(
            "🐰",
            fontSize = 15.sp,
            modifier = Modifier.graphicsLayer {
                translationX = (trackPx - bunnyPx) * fraction.value
                translationY = hopY.value.dp.toPx() - 1.dp.toPx()
            },
        )
    }
}

/** 单位大小(1x1)的爱心路径，画的时候平移+缩放；整个 App 共用一份 */
internal val UnitHeart: androidx.compose.ui.graphics.Path by lazy {
    androidx.compose.ui.graphics.Path().apply {
        moveTo(0.5f, 0.92f)
        cubicTo(0.12f, 0.62f, 0.0f, 0.40f, 0.0f, 0.27f)
        cubicTo(0.0f, 0.10f, 0.14f, 0.0f, 0.27f, 0.0f)
        cubicTo(0.40f, 0.0f, 0.47f, 0.08f, 0.5f, 0.17f)
        cubicTo(0.53f, 0.08f, 0.60f, 0.0f, 0.73f, 0.0f)
        cubicTo(0.86f, 0.0f, 1.0f, 0.10f, 1.0f, 0.27f)
        cubicTo(1.0f, 0.40f, 0.88f, 0.62f, 0.5f, 0.92f)
        close()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.heart(color: Color, cx: Float, cy: Float, sizePx: Float) {
    withTransform({
        translate(cx - sizePx / 2f, cy - sizePx / 2f)
        scale(sizePx, sizePx, Offset.Zero)
    }) { drawPath(UnitHeart, color) }
}

/** 一排小爱心：走过的变成粉红并轻轻跳动，没走到的是淡淡的底色 */
@Composable
private fun HeartsBar(fraction: State<Float>, p: com.yuka.cutebrowser.ui.theme.CutePalette, reduce: Boolean) {
    val phase: State<Float> = if (reduce) remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    else rememberInfiniteTransition(label = "heartbeat").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "beat",
    )
    val off = p.pink.copy(alpha = 0.75f)
    val fills = remember(p) { listOf(p.accent, p.lavender, p.accent, p.pink) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 18.dp)
            .drawBehind {
                val n = 9
                val d = 15.dp.toPx()
                val left = d / 2f
                val span = size.width - d
                val cy = size.height - 3.dp.toPx() - d / 2f
                val f = fraction.value
                val ph = phase.value
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val cx = left + span * t
                    if (t <= f + 0.001f) {
                        val s = 1f + 0.16f * kotlin.math.sin((ph - t * 0.6f) * 6.2831855f)
                        heart(if (i % 2 == 0) p.accent else p.accent.copy(alpha = 0.78f), cx, cy, d * s)
                    } else heart(off, cx, cy, d * 0.8f)
                }
            },
    )
}

/** 一条波浪丝带跟着进度往前飘，头上有个小蝴蝶结 */
@Composable
private fun RibbonBar(fraction: State<Float>, p: com.yuka.cutebrowser.ui.theme.CutePalette, reduce: Boolean) {
    val phase: State<Float> = if (reduce) remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    else rememberInfiniteTransition(label = "ribbon").animateFloat(
        0f, 6.2831855f, infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "wave",
    )
    val track = p.pink.copy(alpha = 0.6f)
    val brush = remember(p) { Brush.horizontalGradient(listOf(p.lavender, p.accent)) }
    val path = remember { androidx.compose.ui.graphics.Path() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 18.dp)
            .drawBehind {
                val mid = size.height - 8.dp.toPx()
                val amp = 3.dp.toPx()
                val sw = 5.dp.toPx()
                drawLine(track, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val w = size.width * fraction.value
                if (w > 1f) {
                    path.reset()
                    val step = 6.dp.toPx()
                    var x = 0f
                    val ph = phase.value
                    path.moveTo(0f, mid + amp * kotlin.math.sin(ph))
                    while (x < w) {
                        x = minOf(x + step, w)
                        // 越靠近头部摆得越大，像被风吹起来的丝带尾巴
                        path.lineTo(x, mid + amp * (0.4f + 0.6f * x / size.width) * kotlin.math.sin(x / 9.dp.toPx() - ph))
                    }
                    drawPath(path, brush, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    // 蝴蝶结
                    val hy = mid + amp * (0.4f + 0.6f * w / size.width) * kotlin.math.sin(w / 9.dp.toPx() - ph)
                    val r = 4.5.dp.toPx()
                    drawCircle(p.accent, r, Offset(w - r * 0.9f, hy - r * 0.6f))
                    drawCircle(p.accent, r, Offset(w + r * 0.9f, hy - r * 0.6f))
                    drawCircle(p.accentInk, r * 0.55f, Offset(w, hy - r * 0.6f))
                }
            },
    )
}

private class HeartParticle(
    val id: Long, val x: Float, val size: Dp, val delayMs: Long, val color: Color, val drift: Float, val rise: Float,
)

/** 收藏时飘出来的小爱心，trigger 每变一次就放一波。位置/透明度全部在 graphicsLayer（绘制阶段）里更新。 */
@Composable
fun FloatingHearts(trigger: Int, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    val hearts = remember { mutableStateListOf<HeartParticle>() }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduce) return@LaunchedEffect
        val colors = listOf(p.accent, Color(0xFFFFB3C7), Color(0xFFC9A8FF), Color(0xFFFF6F91), Color(0xFFFFC2D4))
        val base = System.nanoTime()
        repeat(12) { i ->
            hearts.add(
                HeartParticle(
                    id = base + i,
                    x = 0.2f + Random.nextFloat() * 0.6f,
                    size = (16 + Random.nextInt(18)).dp,
                    delayMs = Random.nextLong(0, 450),
                    color = colors.random(),
                    drift = 10f + Random.nextFloat() * 26f,
                    rise = 0.35f + Random.nextFloat() * 0.3f,
                ),
            )
        }
        delay(2300)
        hearts.clear()
    }
    if (hearts.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        hearts.forEach { heart -> key(heart.id) { HeartView(heart, w, h) } }
    }
}

@Composable
private fun HeartView(heart: HeartParticle, w: Float, h: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(heart.delayMs)
        anim.animateTo(1f, tween(1700, easing = FastOutSlowInEasing))
    }
    val density = LocalDensity.current.density
    Icon(
        CuteIcons.HeartFilled,
        contentDescription = null,
        tint = heart.color,
        modifier = Modifier
            .size(heart.size)
            .graphicsLayer {
                val t = anim.value
                if (t == 0f) { alpha = 0f; return@graphicsLayer }
                translationX = w * heart.x + sin(t * 7f) * heart.drift * density
                translationY = h * 0.72f - h * heart.rise * t
                alpha = if (t < 0.15f) t / 0.15f else (1f - t) / 0.85f
                val s = 0.5f + t * 0.8f
                scaleX = s; scaleY = s
                rotationZ = sin(t * 9f) * 18f
            },
    )
}

/**
 * 收藏时爱心图标自己"啵"一下：先缩小再弹回来。popKey 每变一次（收藏成功）就播一次，
 * 0 表示还没发生过，所以单纯切换标签不会触发。缩放只在绘制阶段读取。
 */
@Composable
fun Modifier.heartPop(popKey: Int): Modifier {
    val reduce = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(popKey) {
        if (popKey == 0 || reduce) return@LaunchedEffect
        scale.snapTo(0.4f)
        scale.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 380f))
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * 标签切换/新建/关闭时的柔和过渡：盖一块和背景同色的底，220ms 内渐隐。
 * 动画值只在绘制阶段读取；这块不接收触摸，不挡操作；减少动画时完全不画。
 */
@Composable
fun TabSwitchVeil(tabId: Any, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    val reduce = LocalReduceMotion.current
    val alpha = remember { Animatable(0f) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(tabId) {
        if (first) { first = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        alpha.snapTo(0.8f)
        alpha.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
    }
    Box(modifier.fillMaxSize().drawBehind { if (alpha.value > 0.001f) drawRect(p.background.copy(alpha = alpha.value)) })
}

/** 弹出面板顶部的小拖拽条（加了一颗小爱心） */
@Composable
fun CuteDragHandle() {
    val p = LocalCutePalette.current
    Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Box(
            Modifier
                .size(width = 44.dp, height = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(p.pink),
        )
    }
}
