package com.yuka.cutebrowser.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.data.PageEntry
import com.yuka.cutebrowser.ui.theme.CutePalette
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import java.util.Calendar

@androidx.compose.runtime.Immutable
data class QuickSite(val name: String, val url: String, val emoji: String)

val QuickSites = listOf(
    QuickSite("哔哩哔哩", "https://m.bilibili.com", "📺"),
    QuickSite("小红书", "https://www.xiaohongshu.com", "📕"),
    QuickSite("微博", "https://m.weibo.cn", "🌟"),
    QuickSite("百度", "https://www.baidu.com", "🐾"),
    QuickSite("知乎", "https://www.zhihu.com", "💡"),
    QuickSite("YouTube", "https://m.youtube.com", "🎬"),
)

/** 每日一句：本地小句子，按日期轮换 */
val DailyQuotes = listOf(
    "今天也要做一个闪闪发光的小可爱呀 ✨",
    "把烦恼折成纸飞机，咻～飞走啦 ✈️",
    "慢慢来，好运正在排队来找你 🍀",
    "你笑起来的样子，比草莓蛋糕还甜 🍰",
    "累了就抱抱自己，你已经很棒啦 💖",
    "生活偶尔苦一点，就多加一块糖 🍬",
    "今日份的温柔，请查收 💌",
    "像小猫一样，晒晒太阳，伸个懒腰 🐱",
    "心里有花，走到哪里都是春天 🌷",
    "喜欢的东西，就大胆去喜欢吧 💗",
    "今天的云朵，是棉花糖味的 ☁️",
    "愿你被这个世界温柔以待 🌙",
    "认真吃饭，好好睡觉，就是超厉害的事 🍙",
    "小小的进步，也值得开心一整天 🎀",
    "星星不会因为没人看就不发光 ⭐",
    "去见想见的人，去做想做的事 🌈",
    "温柔的人，运气都不会太差 🌸",
    "给自己一个大大的赞吧 👍",
    "风会记得每一朵花的香 🌼",
    "今天也是被爱着的一天哦 💕",
    "喝杯奶茶，宇宙都变得可爱了 🍵",
)

fun todayQuote(): String {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    return DailyQuotes[day % DailyQuotes.size]
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好呀，小可爱 ☀️"
    in 11..13 -> "中午好～记得好好吃饭哦 🍱"
    in 14..17 -> "下午好呀，来杯奶茶吧 🍵"
    in 18..22 -> "晚上好～今天辛苦啦 🌙"
    else -> "夜深啦，早点休息哦 💤"
}

@Composable
fun HomePage(
    bookmarks: List<PageEntry>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalCutePalette.current
    // 平板/横屏：内容最宽 640dp 并居中，不会拉成一条很宽的长条
    Box(
        modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 问候 + 小兔子
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(remember { greeting() }, color = p.subText, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("唔姆唔姆", color = p.accentInk, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(6.dp))
                    Icon(CuteIcons.Bow, null, tint = p.accent, modifier = Modifier.size(28.dp))
                }
                Text("想去哪里逛逛呢～", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            BunnyMascot(Modifier.size(104.dp))
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle("常用网站", "🎀")
        Spacer(Modifier.height(10.dp))
        val bgs = listOf(p.pink, p.lavender, p.mint)
        QuickSites.chunked(3).forEachIndexed { row, sites ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                sites.forEachIndexed { col, site ->
                    val i = row * 3 + col
                    Sticker(
                        site = site,
                        background = bgs[i % bgs.size],
                        tilt = listOf(-3f, 2f, -1.5f, 2.5f, -2f, 1.5f)[i % 6],
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(site.url) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.height(8.dp))
        QuoteCard(remember { todayQuote() }, p)

        if (bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle("我的收藏", "💗")
            Spacer(Modifier.height(10.dp))
            bookmarks.take(5).forEach { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(p.surface)
                        .bouncyClick { onOpen(b.url) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(CuteIcons.HeartFilled, null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(b.title, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "· 用爱心制作 · 💕",
            color = p.subText, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
    }
}

@Composable
private fun SectionTitle(text: String, emoji: String) {
    val p = LocalCutePalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Sticker(site: QuickSite, background: Color, tilt: Float, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Column(
        modifier
            .rotate(tilt)
            .bouncyClick(onClick)
            .shadow(6.dp, RoundedCornerShape(24.dp), ambientColor = p.accent, spotColor = p.accent)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(p.surface),
            contentAlignment = Alignment.Center,
        ) { Text(site.emoji, fontSize = 24.sp) }
        Spacer(Modifier.height(8.dp))
        Text(site.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun QuoteCard(quote: String, p: CutePalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(26.dp), ambientColor = p.accent, spotColor = p.accent)
            .clip(RoundedCornerShape(26.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(p.pink, p.lavender)))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💌", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text("每日一句", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(quote, color = p.text, fontSize = 16.sp, lineHeight = 24.sp)
        }
        Icon(
            CuteIcons.StarFilled, null, tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
        )
        Icon(
            CuteIcons.Sparkle, null, tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomEnd).size(14.dp),
        )
    }
}

/** 用 Canvas 画的小兔子吉祥物，会轻轻摇耳朵 */
@Composable
fun BunnyMascot(modifier: Modifier = Modifier, masked: Boolean = false) {
    val p = LocalCutePalette.current
    val wiggle by rememberInfiniteTransition(label = "ears").animateFloat(
        -6f, 6f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "earAngle",
    )
    val white = if (p.isDark) Color(0xFFF4F0FF) else Color.White
    val earInner = Color(0xFFFFC2D1)
    val blush = Color(0xFFFFB3C7)
    val eye = Color(0xFF5A4A5E)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // 背后的小圆
        drawCircle(p.pink, radius = w * 0.46f, center = Offset(w / 2, h * 0.58f))
        // 耳朵
        val earW = w * 0.17f
        val earH = h * 0.42f
        for (side in 0..1) {
            val dir = if (side == 0) -1f else 1f
            val cx = if (side == 0) w * 0.36f else w * 0.64f
            rotate(degrees = dir * (10f + wiggle), pivot = Offset(cx, h * 0.48f)) {
                drawRoundRect(white, Offset(cx - earW / 2, h * 0.08f), Size(earW, earH), CornerRadius(earW / 2))
                drawRoundRect(
                    earInner, Offset(cx - earW / 4, h * 0.14f), Size(earW / 2, earH * 0.7f), CornerRadius(earW / 4),
                )
            }
        }
        // 脸
        drawOval(white, Offset(w * 0.2f, h * 0.4f), Size(w * 0.6f, h * 0.48f))
        // 隐身模式：戴一个小眼罩（侠盗兔兔）
        if (masked) {
            val mask = Color(0xFF3A2A5C)
            drawRoundRect(mask, Offset(w * 0.24f, h * 0.545f), Size(w * 0.52f, h * 0.11f), CornerRadius(h * 0.055f))
            drawRoundRect(mask, Offset(w * 0.73f, h * 0.555f), Size(w * 0.12f, h * 0.03f), CornerRadius(h * 0.015f))
            drawRoundRect(mask, Offset(w * 0.72f, h * 0.6f), Size(w * 0.1f, h * 0.03f), CornerRadius(h * 0.015f))
            drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.38f, h * 0.6f))
            drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.62f, h * 0.6f))
        }
        // 眼睛
        drawCircle(eye, radius = w * 0.035f, center = Offset(w * 0.38f, h * 0.6f))
        drawCircle(eye, radius = w * 0.035f, center = Offset(w * 0.62f, h * 0.6f))
        drawCircle(Color.White, radius = w * 0.012f, center = Offset(w * 0.39f, h * 0.59f))
        drawCircle(Color.White, radius = w * 0.012f, center = Offset(w * 0.63f, h * 0.59f))
        // 腮红
        drawOval(blush.copy(alpha = 0.8f), Offset(w * 0.25f, h * 0.65f), Size(w * 0.11f, h * 0.06f))
        drawOval(blush.copy(alpha = 0.8f), Offset(w * 0.64f, h * 0.65f), Size(w * 0.11f, h * 0.06f))
        // 鼻子嘴巴
        drawCircle(p.accent, radius = w * 0.022f, center = Offset(w * 0.5f, h * 0.66f))
        // 蝴蝶结
        val bx = w * 0.66f
        val by = h * 0.42f
        drawCircle(p.accent, radius = w * 0.06f, center = Offset(bx - w * 0.06f, by))
        drawCircle(p.accent, radius = w * 0.06f, center = Offset(bx + w * 0.06f, by))
        drawCircle(Color(0xFFFF6F91), radius = w * 0.03f, center = Offset(bx, by))
    }
}
