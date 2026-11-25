package com.yuka.cutebrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 600f),
        label = "jelly",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleY = scale
                scaleX = 1f - (1f - scale) * 0.5f
                alpha = if (enabled) 1f else 0.4f
            }
            .shadow(if (enabled) 5.dp else 0.dp, shape, ambientColor = p.accent, spotColor = p.accent)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 通用的按压回弹 Modifier，用在卡片/贴纸上 */
@Composable
fun Modifier.bouncyClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "bounce",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 有小兔子在上面蹦跶的加载进度条。
 * progress/visible 用 lambda 传入，真正读取状态的地方只在这个小组件里，
 * 而且进度和兔子位置只在 layout/draw 阶段读取——页面加载时整个界面不会因为进度变化而重组。
 */
@Composable
fun BunnyProgressBar(progress: () -> Int, visible: () -> Boolean, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    AnimatedVisibility(visible(), modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val fraction = animateFloatAsState((progress().coerceIn(0, 100)) / 100f, tween(250), label = "progress")
        val hop = rememberInfiniteTransition(label = "hop").animateFloat(
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
                    translationY = hop.value.dp.toPx() - 1.dp.toPx()
                },
            )
        }
    }
}

private class HeartParticle(
    val id: Long, val x: Float, val size: Dp, val delayMs: Long, val color: Color, val drift: Float, val rise: Float,
)

/** 收藏时飘出来的小爱心，trigger 每变一次就放一波。位置/透明度全部在 graphicsLayer（绘制阶段）里更新。 */
@Composable
fun FloatingHearts(trigger: Int, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    val hearts = remember { mutableStateListOf<HeartParticle>() }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
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
