package com.yuka.cutebrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.data.AppearancePrefs
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.Mascot
import com.yuka.cutebrowser.data.ProgressStyle
import com.yuka.cutebrowser.data.Wallpaper
import com.yuka.cutebrowser.data.WallpaperStore
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import com.yuka.cutebrowser.ui.theme.ThemeOrder
import com.yuka.cutebrowser.ui.theme.paletteOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置里的「外观」整块：主题、跟随系统、首页壁纸、吉祥物、进度条样式、动画与反馈。
 * 独立成一个文件，设置页那边只要调用这一个函数。
 */
@Composable
fun AppearanceSection(repo: CuteRepository, onToast: (String) -> Unit = {}) {
    val p = LocalCutePalette.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current.applicationContext
    val theme by repo.theme.collectAsState(initial = CuteThemeId.STRAWBERRY)
    val ap by repo.appearance.collectAsState(initial = AppearancePrefs())
    fun edit(f: (AppearancePrefs) -> AppearancePrefs) { scope.launch { repo.updateAppearance(f) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val stamp = withContext(Dispatchers.IO) { WallpaperStore.import(ctx, uri) }
            if (stamp != null) repo.updateAppearance { it.copy(wallpaper = Wallpaper.CUSTOM, wallpaperStamp = stamp) }
            else onToast("这张图片读不出来，换一张试试吧")
        }
    }
    val pickPhoto = {
        runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { onToast("找不到相册，没法选照片") }
        Unit
    }

    // ---- 主题 ----
    Label("主题皮肤")
    ThemeOrder.chunked(4).forEach { rowThemes ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowThemes.forEach { id -> ThemeCard(id, id == theme, Modifier.weight(1f)) { scope.launch { repo.setTheme(id) } } }
            repeat(4 - rowThemes.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    ToggleRow("🌗", "跟随系统深色模式", "系统切到深色时自动换成夜间的「星空梦境」，浅色时用你选的主题", ap.followSystem) {
        edit { it.copy(followSystem = !it.followSystem) }
    }

    // ---- 首页壁纸 ----
    Spacer(Modifier.height(18.dp))
    Label("首页壁纸")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Wallpaper.entries.forEach { w ->
            WallpaperCard(w, ap, selected = ap.wallpaper == w) {
                if (w == Wallpaper.CUSTOM) {
                    if (ap.wallpaperStamp > 0L) edit { it.copy(wallpaper = Wallpaper.CUSTOM) } else pickPhoto()
                } else edit { it.copy(wallpaper = w) }
            }
        }
    }
    if (ap.wallpaperStamp > 0L) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("📷 换一张照片") { pickPhoto() }
            SmallPill("🗑️ 删除我的照片") {
                scope.launch {
                    withContext(Dispatchers.IO) { WallpaperStore.deleteOthers(ctx, null) }
                    repo.updateAppearance { it.copy(wallpaper = if (it.wallpaper == Wallpaper.CUSTOM) Wallpaper.NONE else it.wallpaper, wallpaperStamp = 0L) }
                }
            }
        }
    }
    if (ap.wallpaper == Wallpaper.CUSTOM) {
        Spacer(Modifier.height(8.dp))
        var scrim by remember(ap.scrim) { mutableFloatStateOf(ap.scrim) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("遮罩浓度", color = p.text, fontSize = 13.sp, modifier = Modifier.width(72.dp))
            Slider(
                value = scrim,
                onValueChange = { scrim = it },
                onValueChangeFinished = { edit { a -> a.copy(scrim = scrim) } },
                valueRange = AppearancePrefs.MIN_SCRIM..AppearancePrefs.MAX_SCRIM,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.pink),
                modifier = Modifier.weight(1f),
            )
        }
        Text("照片会被缩小后保存在这个 App 里（不会上传），文字下面会垫一层遮罩，保证看得清", color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
    }

    // ---- 吉祥物 ----
    Spacer(Modifier.height(18.dp))
    Label("首页吉祥物")
    ChipRow(Mascot.entries, ap.mascot, { "${it.emoji} ${it.label}" }) { m -> edit { it.copy(mascot = m) } }

    // ---- 进度条 ----
    Spacer(Modifier.height(18.dp))
    Label("加载进度条")
    ChipRow(ProgressStyle.entries, ap.progress, { "${it.emoji} ${it.label}" }) { s -> edit { it.copy(progress = s) } }
    Spacer(Modifier.height(6.dp))
    BunnyProgressBar(progress = { 62 }, visible = { true }, style = ap.progress)

    // ---- 动画与反馈 ----
    Spacer(Modifier.height(12.dp))
    Label("动画和反馈")
    ToggleRow("🐌", "减少动画", "关掉回弹、飘爱心、吉祥物和进度条的循环动画（系统里关了动画也会自动生效）", ap.reduceMotion) {
        edit { it.copy(reduceMotion = !it.reduceMotion) }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("📳", "点按震动", "按按钮时轻轻震一下（默认关）", ap.haptics) { edit { it.copy(haptics = !it.haptics) } }
    Spacer(Modifier.height(8.dp))
    ToggleRow("🔔", "点按音效", "按按钮时发出轻轻的「嗒」，音量跟随系统的触摸提示音（默认关）", ap.sound) { edit { it.copy(sound = !it.sound) } }
}

@Composable
private fun ThemeCard(id: CuteThemeId, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    val tp = paletteOf(id)
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(tp.background)
            .border(if (selected) 3.dp else 1.5.dp, if (selected) p.accent else tp.pink, RoundedCornerShape(22.dp))
            .bouncySwitch(selected, onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(id.emoji, fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(tp.pink, tp.lavender, tp.mint, tp.accent).forEach {
                Box(Modifier.size(9.dp).clip(CircleShape).background(it))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(id.label, color = tp.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WallpaperCard(w: Wallpaper, ap: AppearancePrefs, selected: Boolean, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Column(
        Modifier.width(76.dp).bouncySwitch(selected, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp, 100.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(p.background)
                .border(if (selected) 3.dp else 1.5.dp, if (selected) p.accent else p.pink, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (w) {
                Wallpaper.NONE -> Unit
                Wallpaper.CUSTOM -> {
                    val ctx = LocalContext.current.applicationContext
                    val thumb by produceState<ImageBitmap?>(null, ap.wallpaperStamp) {
                        value = if (ap.wallpaperStamp > 0L) withContext(Dispatchers.IO) { WallpaperStore.load(ctx, ap.wallpaperStamp, 240) } else null
                    }
                    val t = thumb
                    if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(CuteIcons.Plus, "选一张照片", tint = p.accent, modifier = Modifier.size(26.dp))
                }
                else -> Canvas(Modifier.fillMaxSize()) { drawPreset(w, p) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(w.label, color = p.text, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SmallPill(label: String, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(p.pink).bouncyClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** 一排单选小胶囊（和「默认搜索引擎」那一排同样的样子） */
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val p = LocalCutePalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val on = item == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) p.accent else p.pink.copy(alpha = 0.6f))
                    .bouncySwitch(on) { onSelect(item) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (if (on) "✓ " else "") + label(item),
                    color = if (on) p.onAccent else p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
        }
    }
}
