package com.yuka.cutebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.privacy.locale.SiteTzLang
import com.yuka.cutebrowser.privacy.locale.TzLang
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/**
 * 「安全与隐私」里的「时区与语言」。
 * 时区/语言各有"跟随系统"，另有一键搭配；开关独立于「防指纹」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TzLangSection(sec: SecurityPrefs, upd: ((SecurityPrefs) -> SecurityPrefs) -> Unit, sites: Map<String, SiteTzLang>, onClearSites: () -> Unit) {
    val p = LocalCutePalette.current
    var pick by remember { mutableStateOf<String?>(null) } // "tz" / "lang"
    ToggleRow(
        "🌏", "伪造时区和语言",
        "让网页看到的时间、语言和 Accept-Language 请求头都变成你选的（不需要开防指纹）",
        sec.tzOn,
    ) { upd { it.copy(tzOn = !it.tzOn) } }
    if (!sec.tzOn) return
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.lavender.copy(alpha = 0.55f)).padding(14.dp)) {
        Text("🎀 一键搭配", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TzLang.PRESETS.forEach { pr ->
                val on = sec.tz == pr.tz && sec.lang == pr.lang
                SoftChip("${pr.emoji} ${pr.label}", on) { upd { it.copy(tz = pr.tz, lang = pr.lang) } }
            }
            SoftChip("🏠 都跟随系统", sec.tz.isEmpty() && sec.lang.isEmpty()) { upd { it.copy(tz = "", lang = "") } }
        }
    }
    Spacer(Modifier.height(10.dp))
    ValueRow("🕒", "时区", TzLang.zoneLabel(sec.tz)) { pick = "tz" }
    Spacer(Modifier.height(10.dp))
    ValueRow("💬", "语言", TzLang.langLabel(sec.lang)) { pick = "lang" }
    if (sites.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        SettingAction("清除所有网站的单独设置（${sites.size} 个）", "🧹", p.mint, onClearSites)
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.mint.copy(alpha = 0.6f)).padding(14.dp)) {
        Text("🛰️ 小提醒", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            "网站还能从你的 IP 地址猜出你在哪个国家/地区。想骗得彻底，请同时用 VPN 连到对应地区。" +
                "只有网页里的 Date、Intl 和 navigator.language，以及发出去的 Accept-Language 请求头会变；网页里 Web Worker 里的时间不会变。" +
                "单个网站也可以在地址栏的 🛡️ 里单独设置。",
            color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    when (pick) {
        "tz" -> TzPickerDialog(sec.tz, global = true, onPick = { v -> upd { it.copy(tz = v ?: "") } }) { pick = null }
        "lang" -> LangPickerDialog(sec.lang, global = true, onPick = { v -> upd { it.copy(lang = v ?: "") } }) { pick = null }
    }
}

@Composable
private fun ValueRow(emoji: String, title: String, value: String, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.pink.copy(alpha = 0.6f)).bouncyClick(onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(value, color = p.subText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = p.accentInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SoftChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.surface).bouncyClick(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) p.onAccent else p.text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SearchBox(value: String, hint: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val p = LocalCutePalette.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.background).padding(horizontal = 16.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(hint, color = p.subText.copy(alpha = 0.7f), fontSize = 14.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class PickItem(val value: String?, val title: String, val sub: String = "")

@Composable
private fun PickerFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalCutePalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(p.surface).padding(18.dp)) {
            Text(title, color = p.accentInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun PickList(items: List<PickItem>, selected: String?, onPick: (String?) -> Unit) {
    val p = LocalCutePalette.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.value ?: "∅inherit" }) { it ->
            val on = it.value == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (on) p.accent.copy(alpha = 0.22f) else p.pink.copy(alpha = 0.35f))
                    .clickable(remember { MutableInteractionSource() }, null) { onPick(it.value) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(it.title, color = p.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (it.sub.isNotEmpty()) Text(it.sub, color = p.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (on) Text("✓", color = p.accentInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 选时区。[global] = true：第一项是"跟随系统"；false（单个网站）：多一项"跟随全局设置"（返回 null）和"用系统真实时区"。
 * 返回值：null = 跟随全局；其它见 [TzLang]。
 */
@Composable
fun TzPickerDialog(current: String?, global: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    PickerFrame("选时区 🕒", onDismiss) {
        SearchBox(q, "搜索：东京、Tokyo、UTC+9…") { q = it }
        Spacer(Modifier.height(10.dp))
        // 约 600 个时区，每项都要算偏移文字：只在搜索词变化时重算，不要每次重组都算一遍
        val items = remember(q, global) {
            val zones = TzLang.searchZones(q).map { PickItem(it.id, if (it.label.isNotEmpty()) it.label else it.id, "${it.id} · ${TzLang.offsetLabel(it.id)}") }
            if (q.isBlank()) buildList {
                if (!global) add(PickItem(null, "跟随全局设置"))
                add(if (global) PickItem("", "跟随系统") else PickItem(TzLang.REAL, "用系统真实时区"))
                add(PickItem(TzLang.RANDOM, "每个网站随机", "同一个网站这次打开 App 期间不变，不同网站不一样"))
                addAll(zones)
            } else zones
        }
        PickList(items, current) { onPick(it); onDismiss() }
    }
}

/** 选语言：常用列表 + 自定义标签（如 pt-BR）。[global] 的含义同 [TzPickerDialog] */
@Composable
fun LangPickerDialog(current: String?, global: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val p = LocalCutePalette.current
    var custom by remember { mutableStateOf("") }
    val norm = TzLang.normalizeLang(custom)
    PickerFrame("选语言 💬", onDismiss) {
        val items = buildList {
            if (!global) add(PickItem(null, "跟随全局设置"))
            add(if (global) PickItem("", "跟随系统") else PickItem(TzLang.REAL, "用系统真实语言"))
            TzLang.LANGUAGES.forEach { add(PickItem(it.id, it.label, it.id)) }
        }
        PickList(items, current) { onPick(it); onDismiss() }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchBox(custom, "自定义标签，如 pt-BR、it-IT") { custom = it } }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (norm != null) p.accent else p.subText.copy(alpha = 0.4f))
                    .bouncyClick { if (norm != null) { onPick(norm); onDismiss() } }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("用它", color = p.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        if (custom.isNotBlank() && norm == null) Text("格式像 en-US、zh-TW、pt-BR 这样哦", color = p.accentInk, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        else if (norm != null && norm != custom.trim()) Text("会用：$norm", color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
}

/** 地址栏 🛡️ 里"这个网站的时区和语言" */
@Composable
fun SiteTzLangDialog(host: String, controller: BrowserController, onDismiss: () -> Unit) {
    val p = LocalCutePalette.current
    var v by remember { mutableStateOf(controller.siteTzLang(host)) }
    var pick by remember { mutableStateOf<String?>(null) }
    val g = controller.sec
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(p.surface).padding(22.dp)) {
            Text("🌏 ${com.yuka.cutebrowser.privacy.fingerprint.FpSeed.site(host)}", color = p.accentInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("只对这个网站（和它的子域名）生效，改完会重新加载页面", color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            ValueRow("🕒", "时区", v.tz?.let { TzLang.zoneLabel(it) } ?: "跟随全局设置（${TzLang.zoneLabel(g.tz)}）") { pick = "tz" }
            Spacer(Modifier.height(10.dp))
            ValueRow("💬", "语言", v.lang?.let { TzLang.langLabel(it) } ?: "跟随全局设置（${TzLang.langLabel(g.lang)}）") { pick = "lang" }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(p.pink).bouncyClick { controller.setSiteTzLang(host, null); onDismiss() }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("都跟随全局", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(p.accent).bouncyClick { controller.setSiteTzLang(host, v); onDismiss() }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("保存并刷新", color = p.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
    when (pick) {
        "tz" -> TzPickerDialog(v.tz, global = false, onPick = { v = v.copy(tz = it) }) { pick = null }
        "lang" -> LangPickerDialog(v.lang, global = false, onPick = { v = v.copy(lang = it) }) { pick = null }
    }
}

/** 盾牌菜单里的一行：显示这个网站现在用的时区/语言，点开单独设置 */
@Composable
fun SiteTzLangRow(host: String, controller: BrowserController, onOpen: () -> Unit) {
    val p = LocalCutePalette.current
    val s = controller.siteTzLang(host)
    val g = controller.sec
    val tz = s.tz ?: g.tz
    val lang = s.lang ?: g.lang
    fun short(x: String) = x.substringBefore("（").substringBefore(" UTC").substringBefore(" · ")
    val sub = (if (s.isEmpty) "跟随全局：" else "单独设置：") + short(TzLang.zoneLabel(tz)) + " · " + short(TzLang.langLabel(lang))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.pink.copy(alpha = 0.6f)).bouncyClick(onOpen).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🌏", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("这个网站的时区和语言", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = p.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = p.accentInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
