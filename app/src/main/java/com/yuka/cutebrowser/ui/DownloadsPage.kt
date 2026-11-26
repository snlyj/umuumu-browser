package com.yuka.cutebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.DlStatus
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.features.DownloadRow
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 下载管理（菜单 → 下载）：在线刷新进度；打开 / 分享 / 重试 / 删除 */
@Composable
fun DownloadsPage(controller: BrowserController) {
    val center = controller.features.downloadCenter
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<DownloadRow>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (isActive) {
            val list = runCatching { center.rows() }.getOrDefault(emptyList())
            rows = list
            // 有正在下载的就勤快点刷新（看进度），没有就慢一点；只在这个面板开着时轮询
            delay(if (list.any { it.active }) 700 else 2500)
        }
    }
    DownloadsContent(
        rows = rows,
        onOpen = { r ->
            when {
                r.done -> if (!center.open(r)) controller.toast("没有能打开它的 App 呢")
                r.failed -> controller.toast(if (r.canRetry) "下载失败了，点「重试」再来一次吧" else "这个下载失败了，可以删掉重新下哦")
                r.missing -> controller.toast(if (r.canRetry) "文件已经不在了，可以点「重试」重新下载" else "文件已经不在了")
                else -> controller.toast("还在下载中，等一下下～")
            }
        },
        onShare = { r -> if (!center.share(ctx, r)) controller.toast("这个文件分享不了呢 🥺") },
        onRetry = { r ->
            scope.launch {
                controller.toast(if (center.retry(r)) "重新开始下载啦 🎀" else "重试失败了 🥺")
                refresh++
            }
        },
        onDelete = { r ->
            scope.launch {
                center.delete(r)
                controller.toast(if (r.active) "已取消下载" else if (r.missing) "记录删掉啦" else "已删除")
                refresh++
            }
        },
        onClearMissing = { list ->
            scope.launch { center.clearMissing(list); refresh++ }
        },
        onSystemDownloads = { controller.openSystemDownloads() },
    )
}

/** 纯界面部分（截图测试直接用） */
@Composable
fun DownloadsContent(
    rows: List<DownloadRow>?,
    onOpen: (DownloadRow) -> Unit,
    onShare: (DownloadRow) -> Unit,
    onRetry: (DownloadRow) -> Unit,
    onDelete: (DownloadRow) -> Unit,
    onClearMissing: (List<DownloadRow>) -> Unit,
    onSystemDownloads: () -> Unit,
) {
    val p = LocalCutePalette.current
    SheetHeader("下载管理", "📥") {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(p.lavender)
                .bouncyClick(onSystemDownloads)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("系统下载", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
    val list = rows
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
            Text("翻找中… 🐰", color = p.subText, fontSize = 14.sp)
        }
        return
    }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BunnyMascot(Modifier.size(90.dp))
                Spacer(Modifier.height(12.dp))
                Text("还没有下载过东西哦～", color = p.subText, fontSize = 14.sp)
            }
        }
        return
    }
    val activeCount = list.count { it.active }
    val missing = list.filter { it.missing }
    if (activeCount > 0 || missing.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (activeCount > 0) {
                Text(
                    "正在下载 $activeCount 个 ⏬", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(p.mint).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (missing.isNotEmpty()) {
                Text(
                    "清掉失效记录", color = p.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(p.pink).bouncyClick { onClearMissing(missing) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.CHINA) }
    LazyColumn(
        Modifier.heightIn(max = 520.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.key }) { r -> DownloadRowView(r, fmt, onOpen, onShare, onRetry, onDelete) }
    }
}

@Composable
private fun DownloadRowView(
    r: DownloadRow,
    fmt: SimpleDateFormat,
    onOpen: (DownloadRow) -> Unit,
    onShare: (DownloadRow) -> Unit,
    onRetry: (DownloadRow) -> Unit,
    onDelete: (DownloadRow) -> Unit,
) {
    val p = LocalCutePalette.current
    val d = r.item
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.background)
            .bouncyClick { onOpen(r) }
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(16.dp)).background(if (r.missing) p.lavender else p.pink),
            contentAlignment = Alignment.Center,
        ) { Text(fileEmoji(r.mime, r.title), fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                d == null -> "文件已经不在了" + (r.record?.url?.let { " · ${UrlUtils.host(it)}" } ?: "")
                d.status == DlStatus.DONE -> "已完成 · ${bytesText(d.total)}" + (r.time.takeIf { it > 0 }?.let { " · ${fmt.format(Date(it))}" } ?: "")
                d.status == DlStatus.FAILED -> "下载失败 🥺"
                d.status == DlStatus.PAUSED -> "暂停中（等网络）· ${bytesText(d.soFar)}"
                d.status == DlStatus.PENDING -> "排队中…"
                else -> if (d.total > 0) "${bytesText(d.soFar)} / ${bytesText(d.total)} · ${(d.soFar * 100 / d.total).coerceIn(0, 100)}%" else "已下 ${bytesText(d.soFar)}"
            }
            Text(status, color = if (r.failed) p.accentInk else p.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d != null && d.active) {
                val frac = if (d.total > 0) (d.soFar.toFloat() / d.total).coerceIn(0f, 1f) else 0.1f
                val track = p.pink
                val fill = p.accent
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .drawBehind {
                            val rr = CornerRadius(size.height / 2)
                            drawRoundRect(track, cornerRadius = rr)
                            drawRoundRect(fill, size = Size(maxOf(size.width * frac, size.height), size.height), cornerRadius = rr)
                        },
                )
            }
        }
        if (r.canShare) {
            JellyButton(onClick = { onShare(r) }, size = 34.dp, background = p.mint) {
                Icon(CuteIcons.Share, "分享", tint = p.text, modifier = Modifier.size(16.dp))
            }
        }
        if (r.canRetry) {
            JellyButton(onClick = { onRetry(r) }, size = 34.dp, background = p.lavender) {
                Icon(FeatureIcons.Retry, "重试", tint = p.text, modifier = Modifier.size(16.dp))
            }
        }
        JellyButton(onClick = { onDelete(r) }, size = 34.dp, background = p.pink) {
            Icon(
                if (r.active) CuteIcons.Close else CuteIcons.Trash,
                if (r.active) "取消" else if (r.missing) "删除记录" else "删除",
                tint = p.accent, modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal fun bytesText(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

internal fun fileEmoji(mime: String?, title: String): String {
    val m = mime.orEmpty()
    val t = title.lowercase()
    return when {
        m.startsWith("image/") -> "🖼️"
        m.startsWith("video/") -> "🎬"
        m.startsWith("audio/") -> "🎵"
        m.contains("pdf") || t.endsWith(".pdf") -> "📕"
        m.contains("android.package") || t.endsWith(".apk") -> "🤖"
        m.contains("zip") || m.contains("compressed") || t.endsWith(".zip") || t.endsWith(".rar") || t.endsWith(".7z") -> "📦"
        m.startsWith("text/") -> "📝"
        else -> "📄"
    }
}
