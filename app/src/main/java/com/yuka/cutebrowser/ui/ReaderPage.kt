package com.yuka.cutebrowser.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.features.ReaderColors
import com.yuka.cutebrowser.features.ReaderFont
import com.yuka.cutebrowser.features.ReaderHtml
import com.yuka.cutebrowser.features.ReaderSession
import com.yuka.cutebrowser.features.ReaderStyle
import com.yuka.cutebrowser.features.ReaderTheme
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/** 阅读模式：盖在网页上面。上面是可爱的工具条，下面是单独的（不开 JS 的）阅读 WebView */
@Composable
fun ReaderPage(session: ReaderSession, controller: BrowserController) {
    val f = controller.features
    val p = LocalCutePalette.current
    val style = f.prefs.readerStyle
    val colors = ReaderHtml.colors(style.theme, p)
    BackHandler { f.closeReader() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        ReaderToolbar(style, onStyle = { f.setReaderStyle(it) }, onClose = { f.closeReader() })
        val article = session.article
        if (article == null) {
            ReaderLoading()
            return@Column
        }
        // 只在排版相关的东西变了才重新生成 HTML（字号走 textZoom，不用重排）
        val html = remember(article, style.lineHeight, style.theme, style.font, colors) {
            ReaderHtml.build(article, style, colors, session.host)
        }
        key(session.tabId, session.url) {
            // 上次交给 WebView 的 HTML（普通对象，不是 Compose 状态：只在 update 里读写）
            val shown = remember { arrayOfNulls<String>(1) }
            AndroidView(
                factory = { f.createReaderView(session) },
                update = { v: WebView ->
                    if (v.settings.textZoom != style.zoom) v.settings.textZoom = style.zoom
                    if (html != shown[0]) {
                        f.renderReader(v, html, session.url, keepPosition = shown[0] != null)
                        shown[0] = html
                    }
                },
                onRelease = { v -> f.disposeReaderView(v) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun ReaderLoading() {
    val p = LocalCutePalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BunnyMascot(Modifier.size(96.dp))
            Spacer(Modifier.height(12.dp))
            Text("正在把文章整理得干干净净… 📖", color = p.subText, fontSize = 14.sp)
        }
    }
}

/** 阅读模式工具条：退出 · 字号 −/+ · 行距 · 配色（跟随主题/护眼/夜间）· 字体 */
@Composable
fun ReaderToolbar(style: ReaderStyle, onStyle: (ReaderStyle) -> Unit, onClose: () -> Unit) {
    val p = LocalCutePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp), ambientColor = p.accent, spotColor = p.accent)
            .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(p.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JellyButton(onClick = onClose, size = 38.dp, background = p.pink) {
                Icon(CuteIcons.Close, "退出阅读模式", tint = p.text, modifier = Modifier.size(18.dp))
            }
            Icon(FeatureIcons.Book, null, tint = p.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text("阅读模式", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            JellyButton(onClick = { onStyle(style.zoomOut()) }, enabled = style.zoom > ReaderStyle.MIN_ZOOM, size = 38.dp, background = p.lavender) {
                Text("A−", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "字小一点" })
            }
            Text("${style.zoom}%", color = p.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            JellyButton(onClick = { onStyle(style.zoomIn()) }, enabled = style.zoom < ReaderStyle.MAX_ZOOM, size = 38.dp, background = p.lavender) {
                Text("A+", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "字大一点" })
            }
            JellyButton(onClick = { onStyle(style.nextLineHeight()) }, size = 38.dp, background = p.mint) {
                Icon(FeatureIcons.LineHeight, "行距：${lineLabel(style.lineHeight)}", tint = p.text, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderTheme.entries.forEach { t ->
                val c = ReaderHtml.colors(t, p)
                ThemeChip(t.label, c, selected = style.theme == t) { onStyle(style.copy(theme = t)) }
            }
            Spacer(Modifier.weight(1f))
            val other = if (style.font == ReaderFont.SANS) ReaderFont.SERIF else ReaderFont.SANS
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.pink)
                    .bouncyClick { onStyle(style.copy(font = other)) }
                    .semantics { contentDescription = "字体：${style.font.label}，点一下换成${other.label}" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (style.font == ReaderFont.SANS) "黑" else "宋", color = p.accentInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(style.font.label, color = p.text, fontSize = 12.sp)
            }
        }
    }
}

private fun lineLabel(lh: Float): String = when {
    lh < 1.65f -> "紧凑"
    lh < 1.95f -> "舒适"
    else -> "宽松"
}

@Composable
private fun ThemeChip(label: String, c: ReaderColors, selected: Boolean, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.pink else p.background)
            .border(2.dp, if (selected) p.accent else p.pink, RoundedCornerShape(50))
            .bouncyClick(onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(c.bg).border(2.dp, c.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("a", color = c.text, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(6.dp))
        Text(label, color = p.text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** 地址栏里的「阅读模式」小按钮（网页像文章时才出现） */
@Composable
fun ReaderChip(onClick: () -> Unit) {
    val p = LocalCutePalette.current
    JellyButton(onClick = onClick, size = 38.dp, background = p.mint) {
        Icon(FeatureIcons.Book, "阅读模式", tint = p.text, modifier = Modifier.size(20.dp))
    }
}
