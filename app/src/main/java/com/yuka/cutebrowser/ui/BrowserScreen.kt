package com.yuka.cutebrowser.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuka.cutebrowser.browser.AddressInput
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.TabState
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SheetType { None, Menu, Tabs, Bookmarks, History, Downloads, Settings, Security, SitePerms }

@Composable
fun BrowserScreen(controller: BrowserController, repo: CuteRepository, protection: AppProtection = NoProtection) {
    val p = LocalCutePalette.current
    val scope = rememberCoroutineScope()
    val tab = controller.current
    val bookmarks by repo.bookmarks.collectAsState(initial = emptyList())
    // 必须同时以 tab 为 key：derivedStateOf 的 lambda 会捕获当时的 tab，切换标签后不换 key 会一直盯着旧标签，爱心状态就错了
    val isBookmarked by remember(bookmarks, tab) { derivedStateOf { !tab.isHome && bookmarks.any { it.url == tab.url } } }
    var sheet by rememberSaveable { mutableStateOf(SheetType.None) }
    var heartBurst by remember { mutableIntStateOf(0) }
    var shield by remember { mutableStateOf(false) }

    BackHandler(enabled = controller.canGoBack()) { controller.back() }

    // 键盘弹出时（输入网址、网页里打字）收起底部栏，把空间还给网页；derivedStateOf 保证只在"有/没有键盘"变化时才重组，不会跟着键盘动画每帧重组
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }

    val toggleBookmark: () -> Unit = {
        if (!tab.isHome && tab.url.isNotBlank()) {
            scope.launch {
                val added = repo.toggleBookmark(tab.url, tab.title)
                if (added) {
                    heartBurst++
                    controller.toast("收藏成功啦 💕")
                } else controller.toast("已取消收藏")
            }
        } else controller.toast("先打开一个网页再收藏吧～")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // 状态栏 + 导航栏 + 键盘 + 刘海/挖孔（横屏时在左右两侧）
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AddressBar(
                tab = tab,
                isBookmarked = isBookmarked,
                onSubmit = { controller.submit(it) },
                onToggleBookmark = toggleBookmark,
                onRefresh = { controller.reloadOrStop() },
                onShield = { shield = true },
                onFocus = { controller.warmSearchEngine() },
                onTyping = { controller.warmTyped(it) },
            )
            if (tab.isHome) CopiedLinkBanner(onOpen = { controller.open(it) })
            BunnyProgressBar(progress = { tab.progress }, visible = { tab.loading && !tab.isHome })
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (tab.isHome) {
                    if (tab.incognito) IncognitoHome(controller.incognitoIsolated)
                    else HomePage(bookmarks = bookmarks, onOpen = { controller.open(it) })
                } else {
                    key(tab.id, controller.webGeneration) {
                        AndroidView(
                            factory = {
                                controller.webViewFor(tab).also { v ->
                                    (v.parent as? ViewGroup)?.removeView(v)
                                    v.onResume()
                                }
                            },
                            // 离开屏幕（切标签/回主页）就暂停这个 WebView，后台页面不再耗 CPU
                            onRelease = { v -> if (tab.webView === v) v.onPause() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    tab.warning?.let { SecurityWarningPage(tab, it, controller) }
                }
            }
            AnimatedVisibility(visible = !imeVisible, enter = expandVertically(), exit = shrinkVertically()) {
                BottomBar(
                    tabCount = controller.tabs.size,
                    canBack = controller.canGoBack(),
                    canForward = controller.canGoForward(),
                    onBack = { controller.back() },
                    onForward = { controller.forward() },
                    onHome = { controller.goHome() },
                    // 先截一张当前网页的小图（异步，不卡界面），列表里显示
                    onTabs = { controller.captureThumbnail(); sheet = SheetType.Tabs },
                    onMenu = { sheet = SheetType.Menu },
                )
            }
        }

        FloatingHearts(heartBurst)

        CuteSheets(
            sheet = sheet,
            onChange = { sheet = it },
            controller = controller,
            repo = repo,
            bookmarks = bookmarks,
            isBookmarked = isBookmarked,
            onToggleBookmark = toggleBookmark,
            protection = protection,
        )
        controller.permissionPrompt?.let { PermissionDialog(it) }
        if (shield && !tab.isHome) ShieldDialog(tab, controller) { shield = false }
    }
}

@Composable
private fun AddressBar(
    tab: TabState,
    isBookmarked: Boolean,
    onSubmit: (String) -> Unit,
    onToggleBookmark: () -> Unit,
    onRefresh: () -> Unit,
    onShield: () -> Unit,
    onFocus: () -> Unit = {},
    onTyping: (String) -> Unit = {},
) {
    val p = LocalCutePalette.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var text by remember(tab.id) { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(tab.id, tab.url, tab.isHome, focused) {
        if (!focused) text = TextFieldValue(if (tab.isHome) "" else tab.url)
    }

    // 输入时稍等一下（停手 250ms）再预热：像网址就把它的域名先解析好；每次按键不会触发任何网络活动
    LaunchedEffect(text.text, focused) {
        if (focused && text.text.isNotBlank()) {
            delay(250)
            onTyping(text.text)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .shadow(6.dp, RoundedCornerShape(50), ambientColor = p.accent, spotColor = p.accent)
                .clip(RoundedCornerShape(50))
                .background(p.surface)
                .border(2.dp, if (focused) p.accent else p.pink, RoundedCornerShape(50))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!tab.isHome && !focused) {
                ShieldChip(tab, onShield)
                Spacer(Modifier.width(6.dp))
            } else {
                Icon(CuteIcons.Search, null, tint = p.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.text.isEmpty()) {
                    Text(if (tab.incognito) "悄悄地搜索 🎭" else "搜索或输入网址 ✨", color = p.subText, fontSize = 15.sp, maxLines = 1)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { incoming -> text = runCatching { AddressInput.sanitize(incoming) }.getOrDefault(text) },
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        onSubmit(text.text)
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "地址栏，输入网址或搜索内容" }
                        .onFocusChanged {
                            if (it.isFocused && !focused) {
                                text = text.copy(selection = TextRange(0, text.text.length))
                                onFocus()
                            }
                            focused = it.isFocused
                        },
                )
            }
            if (focused && text.text.isNotEmpty()) {
                JellyButton(onClick = { text = TextFieldValue("") }, size = 34.dp, background = p.pink) {
                    Icon(CuteIcons.Close, "清空", tint = p.accent, modifier = Modifier.size(16.dp))
                }
            } else if (!tab.isHome) {
                JellyButton(onClick = onToggleBookmark, size = 38.dp, background = p.surface) {
                    Icon(
                        if (isBookmarked) CuteIcons.HeartFilled else CuteIcons.Heart,
                        if (isBookmarked) "取消收藏" else "收藏",
                        tint = p.accent, modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (!tab.isHome) {
            Spacer(Modifier.width(10.dp))
            JellyButton(onClick = onRefresh, size = 46.dp, background = p.lavender) {
                Icon(
                    if (tab.loading) CuteIcons.Close else CuteIcons.Refresh,
                    if (tab.loading) "停止" else "刷新",
                    tint = p.text, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    tabCount: Int,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val p = LocalCutePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), ambientColor = p.accent, spotColor = p.accent)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(p.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JellyButton(onClick = onBack, enabled = canBack, background = p.pink) {
            Icon(CuteIcons.Back, "后退", tint = p.text, modifier = Modifier.size(22.dp))
        }
        JellyButton(onClick = onForward, enabled = canForward, background = p.pink) {
            Icon(CuteIcons.Forward, "前进", tint = p.text, modifier = Modifier.size(22.dp))
        }
        JellyButton(onClick = onHome, size = 54.dp, background = p.accent) {
            Icon(CuteIcons.Home, "主页", tint = p.onAccent, modifier = Modifier.size(26.dp))
        }
        JellyButton(onClick = onTabs, background = p.mint) {
            Box(
                Modifier
                    .size(24.dp)
                    .border(2.dp, p.text, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (tabCount > 99) "∞" else tabCount.toString(),
                    color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        JellyButton(onClick = onMenu, background = p.lavender) {
            Icon(CuteIcons.Bow, "菜单", tint = p.text, modifier = Modifier.size(24.dp))
        }
    }
}
