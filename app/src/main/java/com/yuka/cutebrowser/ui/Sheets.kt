package com.yuka.cutebrowser.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.CrashLog
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.DlStatus
import com.yuka.cutebrowser.browser.DownloadItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.PageEntry
import com.yuka.cutebrowser.data.SearchEngine
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import com.yuka.cutebrowser.ui.theme.paletteOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** 菜单/标签等可以下滑关掉；设置、安全、网站权限只能按返回或关闭按钮。 */
internal fun sheetAllowsSwipeDismiss(sheet: SheetType): Boolean = when (sheet) {
    SheetType.Settings, SheetType.Security, SheetType.SitePerms -> false
    else -> true
}

/** 系统返回：安全回到设置，网站权限回到安全，其它关掉面板。 */
internal fun sheetOnBack(sheet: SheetType): SheetType = when (sheet) {
    SheetType.Security -> SheetType.Settings
    SheetType.SitePerms -> SheetType.Security
    else -> SheetType.None
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuteSheets(
    sheet: SheetType,
    onChange: (SheetType) -> Unit,
    controller: BrowserController,
    repo: CuteRepository,
    bookmarks: List<PageEntry>,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    protection: AppProtection = NoProtection,
) {
    if (sheet == SheetType.None) return
    val p = LocalCutePalette.current
    val swipeDismiss = sheetAllowsSwipeDismiss(sheet)
    // confirmValueChange 必须 remember，不然每次重组都换 lambda，SheetState 会被重建
    val confirm = remember(swipeDismiss) {
        { value: SheetValue -> value != SheetValue.Hidden || swipeDismiss }
    }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = sheet != SheetType.Menu,
        confirmValueChange = confirm,
    )
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { onChange(SheetType.None) }

    ModalBottomSheet(
        onDismissRequest = { onChange(sheetOnBack(sheet)) },
        sheetState = state,
        containerColor = p.surface,
        contentColor = p.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { CuteDragHandle() },
    ) {
        // 必须写在 Dialog 内容里，才会挂到面板自己的返回 dispatcher。
        // 放在外面只会接到 Activity，真机上返回键先被面板吃掉（再被我们 veto 掉），等于按了没反应。
        BackHandler { onChange(sheetOnBack(sheet)) }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding() // 面板里有输入框（自定义 DoH 地址、搜时区…）：键盘弹出时内容跟着往上顶，不被挡住
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            when (sheet) {
                SheetType.Menu -> MenuSheet(
                    isBookmarked = isBookmarked,
                    onNewTab = { controller.newTab(); close() },
                    incognito = controller.current.incognito,
                    onIncognito = { controller.toggleIncognito(); close() },
                    onBookmark = { onToggleBookmark(); close() },
                    onBookmarks = { onChange(SheetType.Bookmarks) },
                    onHistory = { onChange(SheetType.History) },
                    onDownloads = { onChange(SheetType.Downloads) },
                    onShare = { controller.shareCurrent(); close() },
                    onSettings = { onChange(SheetType.Settings) },
                    tools = featureTools(controller, close),
                )
                SheetType.Tabs -> TabsSheet(controller, close)
                SheetType.Bookmarks -> EntryListSheet(
                    title = "我的收藏", emoji = "💗",
                    empty = "还没有收藏哦，点点地址栏的小爱心试试吧 💗",
                    items = bookmarks,
                    showTime = false,
                    onOpen = { controller.open(it.url); close() },
                    onRemove = { e -> scope.launch { repo.removeBookmark(e.url) } },
                    removeIcon = CuteIcons.HeartFilled,
                )
                SheetType.History -> {
                    val history by repo.history.collectAsState(initial = emptyList())
                    EntryListSheet(
                        title = "历史记录", emoji = "🕰️",
                        empty = "这里空空的～去逛逛吧 🌷",
                        items = history,
                        showTime = true,
                        onOpen = { controller.open(it.url); close() },
                        onRemove = { e -> scope.launch { repo.removeHistory(e) } },
                        removeIcon = CuteIcons.Close,
                        onClearAll = {
                            scope.launch { repo.clearHistory() }
                            controller.clearWebHistory()
                            controller.toast("历史记录清空啦 ✨")
                        },
                    )
                }
                SheetType.Downloads -> DownloadsSheet(controller)
                SheetType.Settings -> SettingsSheet(controller, repo, onClose = close) { onChange(SheetType.Security) }
                SheetType.Security -> SecuritySheet(controller, repo, protection, onClose = close) { onChange(SheetType.SitePerms) }
                SheetType.SitePerms -> SitePermsSheet(repo, controller, onClose = close)
                SheetType.None -> Unit
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, emoji: String, onClose: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val p = LocalCutePalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
        if (onClose != null) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(p.pink)
                    .semantics { contentDescription = "关闭" }
                    .bouncyClick(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CuteIcons.Close, contentDescription = null, tint = p.text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MenuSheet(
    isBookmarked: Boolean,
    onNewTab: () -> Unit,
    incognito: Boolean,
    onIncognito: () -> Unit,
    onBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    tools: List<ToolTile> = emptyList(),
) {
    val p = LocalCutePalette.current
    SheetHeader("唔姆菜单", "🎀")
    Spacer(Modifier.height(8.dp))
    data class MenuTile(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val action: () -> Unit, val checked: Boolean? = null)
    val tiles = listOf(
        MenuTile("新标签页", CuteIcons.Plus, onNewTab),
        MenuTile(if (incognito) "结束隐身" else "隐身模式", CuteIcons.Mask, onIncognito, checked = incognito),
        MenuTile(if (isBookmarked) "取消收藏" else "收藏本页", if (isBookmarked) CuteIcons.HeartFilled else CuteIcons.Heart, onBookmark),
        MenuTile("我的收藏", CuteIcons.StarFilled, onBookmarks),
        MenuTile("历史记录", CuteIcons.Clock, onHistory),
        MenuTile("下载", CuteIcons.Download, onDownloads),
        MenuTile("分享", CuteIcons.Share, onShare),
        MenuTile("设置", CuteIcons.Flower, onSettings),
    ) + tools.map { MenuTile(it.label, it.icon, it.action, it.checked) }
    val bgs = listOf(p.pink, p.lavender, p.mint)
    tiles.chunked(4).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEachIndexed { c, tile ->
                val on = tile.checked == true
                val clickMod = if (tile.checked != null) Modifier.bouncySwitch(tile.checked, tile.action) else Modifier.bouncyClick(tile.action)
                Column(
                    Modifier
                        .weight(1f)
                        .then(clickMod)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (on) p.accent else bgs[(r * 4 + c) % 3]),
                        contentAlignment = Alignment.Center,
                    ) { Icon(tile.icon, null, tint = if (on) p.onAccent else p.accent, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text(tile.label, color = p.text, fontSize = 12.sp, maxLines = 1)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TabsSheet(controller: BrowserController, close: () -> Unit) {
    val p = LocalCutePalette.current
    SheetHeader("标签页 (${controller.tabs.size})", "🗂️") {
        JellyButton(
            onClick = { controller.newTab(incognito = true); close() },
            size = 40.dp, background = p.lavender,
        ) { Icon(CuteIcons.Mask, "新建隐身标签", tint = p.text, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(8.dp))
        JellyButton(
            onClick = { controller.newTab(); close() },
            size = 40.dp, background = p.accent,
        ) { Icon(CuteIcons.Plus, "新建标签页", tint = p.onAccent, modifier = Modifier.size(22.dp)) }
    }
    if (controller.tabs.any { it.incognito }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(p.lavender)
                .bouncyClick { controller.exitIncognito(); close() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(CuteIcons.Mask, null, tint = p.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("结束隐身", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text("会关掉全部隐身标签，并清掉这次隐身留下的数据", color = p.subText, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp))
        Spacer(Modifier.height(8.dp))
    }
    LazyColumn(
        Modifier.heightIn(max = 460.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(controller.tabs, key = { _, t -> t.id }) { index, t ->
            val selected = index == controller.currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (t.incognito) Color(0xFF3A2A5C) else if (selected) p.pink else p.background)
                    .border(2.dp, if (selected) p.accent else p.pink.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .bouncyClick { controller.switchTo(index); close() }
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t.incognito) "🎭" else if (t.isHome) "🏡" else "🌐", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.displayTitle, color = if (t.incognito) Color(0xFFF1E8FF) else p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!t.isHome && t.url.isNotBlank()) {
                        Text(UrlUtils.host(t.url), color = p.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                JellyButton(onClick = { controller.closeTab(t) }, size = 34.dp, background = p.surface) {
                    Icon(CuteIcons.Close, "关闭", tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EntryListSheet(
    title: String,
    emoji: String,
    empty: String,
    items: List<PageEntry>,
    showTime: Boolean,
    onOpen: (PageEntry) -> Unit,
    onRemove: (PageEntry) -> Unit,
    removeIcon: ImageVector,
    onClearAll: (() -> Unit)? = null,
) {
    val p = LocalCutePalette.current
    SheetHeader(title, emoji) {
        if (onClearAll != null && items.isNotEmpty()) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.pink)
                    .bouncyClick(onClearAll)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CuteIcons.Trash, null, tint = p.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("全部清空", color = p.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BunnyMascot(Modifier.size(90.dp))
                Spacer(Modifier.height(12.dp))
                Text(empty, color = p.subText, fontSize = 14.sp)
            }
        }
        return
    }
    val fmt = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
    LazyColumn(
        Modifier.heightIn(max = 520.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 序号也放进 key：万一有两条 url 和时间都一样的记录（旧数据/手改），LazyColumn 遇到重复 key 会直接崩溃
        itemsIndexed(items, key = { i, it -> "${it.url}#${it.time}#$i" }) { _, e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.background)
                    .bouncyClick { onOpen(e) }
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (showTime) "${fmt.format(Date(e.time))} · ${UrlUtils.host(e.url)}" else UrlUtils.host(e.url),
                        color = p.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                JellyButton(onClick = { onRemove(e) }, size = 34.dp, background = p.pink) {
                    Icon(removeIcon, "删除", tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsSheet(controller: BrowserController, repo: CuteRepository, onClose: () -> Unit, onSecurity: () -> Unit) {
    val p = LocalCutePalette.current
    val scope = rememberCoroutineScope()
    val theme by repo.theme.collectAsState(initial = CuteThemeId.STRAWBERRY)
    val engine by repo.engine.collectAsState(initial = SearchEngine.BING)
    val adBlock by repo.adBlock.collectAsState(initial = true)
    val webDark by repo.webDark.collectAsState(initial = true)
    val ctx = LocalContext.current
    val versionName = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SheetHeader("设置", "🌼", onClose = onClose)

        Label("主题皮肤")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CuteThemeId.entries.forEach { id ->
                val tp = paletteOf(id)
                val selected = id == theme
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(tp.background)
                        .border(if (selected) 3.dp else 1.5.dp, if (selected) p.accent else tp.pink, RoundedCornerShape(24.dp))
                        .bouncyClick { scope.launch { repo.setTheme(id) } }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(id.emoji, fontSize = 26.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(tp.pink, tp.lavender, tp.mint, tp.accent).forEach {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(id.label, color = tp.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Label("默认搜索引擎")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchEngine.entries.forEach { e ->
                val selected = e == engine
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) p.accent else p.pink.copy(alpha = 0.6f))
                        .bouncyClick { scope.launch { repo.setEngine(e) } }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (if (selected) "✓ " else "") + e.label,
                        color = if (selected) p.onAccent else p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Label("速度和外观")
        ToggleRow("🧹", "拦截广告和追踪", "EasyList + EasyPrivacy + AdAway，约 ${AdBlocker.loadedCount / 1000} 千条规则，网页更快更省流量", adBlock) {
            scope.launch { repo.setAdBlock(!adBlock) }
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("🌙", "网页跟随主题变暗", "在「星空梦境」下把网页也变成深色（网站自带深色的用网站自己的）", webDark) {
            scope.launch { repo.setWebDark(!webDark) }
        }

        Spacer(Modifier.height(18.dp))
        Label("安全")
        SettingAction("安全与隐私（HTTPS、Cookie、权限、应用锁…）", "🛡️", p.mint, onSecurity)

        Spacer(Modifier.height(18.dp))
        Label("清理一下")
        SettingAction("清除历史记录", "🍃", p.lavender) {
            scope.launch { repo.clearHistory() }
            controller.clearWebHistory()
            controller.toast("历史记录清空啦 ✨")
        }
        Spacer(Modifier.height(10.dp))
        SettingAction("清除缓存和 Cookie", "✨", p.mint) { controller.clearCache() }

        Spacer(Modifier.height(20.dp))
        Text(
            "唔姆唔姆 v$versionName · 用爱心制作 💕",
            color = p.subText, fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val ok = CrashLog.copyToClipboard(ctx)
                        Toast.makeText(
                            ctx,
                            if (ok) "已复制上次的崩溃日志，可以发给兔兔帮你看" else "还没有崩溃记录哦",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ),
        )
        Text(
            "过滤规则：EasyList、EasyPrivacy（The EasyList authors，CC BY-SA 3.0 / GPLv3，easylist.to，已去掉注释和通用元素隐藏规则）· AdAway hosts（CC BY 3.0）",
            color = p.subText.copy(alpha = 0.8f), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun Label(text: String) {
    val p = LocalCutePalette.current
    Text(text, color = p.subText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
}

@Composable
internal fun SettingAction(label: String, emoji: String, bg: Color, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .bouncyClick(onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ToggleRow(emoji: String, title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.pink.copy(alpha = 0.6f))
            .bouncySwitch(checked, onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = p.subText, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) p.accent else p.subText.copy(alpha = 0.4f))
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

private fun sizeText(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

private fun mimeEmoji(mime: String?, title: String): String {
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

/** 下载管理：进行中的会每 0.7 秒刷新一次进度，没有进行中的就 2.5 秒刷新一次（只在面板打开时） */
@Composable
private fun DownloadsSheet(controller: BrowserController) {
    val p = LocalCutePalette.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (isActive) {
            val list = controller.downloads.list()
            items = list
            delay(if (list.any { it.active }) 700 else 2500)
        }
    }
    SheetHeader("下载管理", "📥") {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(p.lavender)
                .bouncyClick { controller.openSystemDownloads() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("系统下载", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
    val list = items
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
    LazyColumn(
        Modifier.heightIn(max = 520.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.key }) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.background)
                    .bouncyClick {
                        if (d.status == DlStatus.DONE) {
                            if (!controller.downloads.open(d)) controller.toast("没有能打开它的 App 呢")
                        } else if (d.status == DlStatus.FAILED) controller.toast("这个下载失败了，可以删掉重新下哦")
                        else controller.toast("还在下载中，等一下下～")
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(16.dp)).background(p.pink),
                    contentAlignment = Alignment.Center,
                ) { Text(mimeEmoji(d.mime, d.title), fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (d.status) {
                        DlStatus.DONE -> "已完成 · ${sizeText(d.total)}"
                        DlStatus.FAILED -> "下载失败 🥺"
                        DlStatus.PAUSED -> "暂停中（等网络）· ${sizeText(d.soFar)}"
                        DlStatus.PENDING -> "排队中…"
                        DlStatus.RUNNING -> if (d.total > 0) "${sizeText(d.soFar)} / ${sizeText(d.total)}" else "已下 ${sizeText(d.soFar)}"
                    }
                    Text(status, color = if (d.status == DlStatus.FAILED) p.accentInk else p.subText, fontSize = 12.sp, maxLines = 1)
                    if (d.active) {
                        val frac = if (d.total > 0) (d.soFar.toFloat() / d.total).coerceIn(0f, 1f) else 0.1f
                        val track = p.pink
                        val fill = p.accent
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .height(6.dp)
                                .drawBehind {
                                    val r = CornerRadius(size.height / 2)
                                    drawRoundRect(track, cornerRadius = r)
                                    drawRoundRect(fill, size = Size(maxOf(size.width * frac, size.height), size.height), cornerRadius = r)
                                },
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                JellyButton(
                    onClick = {
                        scope.launch {
                            controller.downloads.remove(d)
                            controller.toast(if (d.active) "已取消下载" else "已删除")
                            refresh++
                        }
                    },
                    size = 34.dp, background = p.pink,
                ) {
                    Icon(if (d.active) CuteIcons.Close else CuteIcons.Trash, if (d.active) "取消" else "删除", tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
