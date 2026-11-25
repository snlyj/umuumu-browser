package com.yuka.cutebrowser.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.dns.DohManager
import com.yuka.cutebrowser.dns.DohPrefs
import com.yuka.cutebrowser.dns.DohProvider
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「安全与隐私」里的 App 内加密 DNS（DoH）设置。全部状态都在 dns 包里，这里只是界面 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DohSettingsSection() {
    val p = LocalCutePalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember { DohManager.prefs(ctx) }.collectAsState(initial = DohPrefs())
    val status by DohManager.status.collectAsState()
    val supported = remember { DohManager.supported }
    val upd: ((DohPrefs) -> DohPrefs) -> Unit = { f -> scope.launch { DohManager.update(ctx, f) } }

    if (!supported) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.lavender.copy(alpha = 0.6f)).padding(16.dp)) {
            Text("🧁 App 内加密 DNS：不支持 😿", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "手机上的 Android System WebView 太旧，不能让网页走 App 自己的加密 DNS。" +
                    "去应用商店更新「Android System WebView」后就能用；在那之前可以用上面的系统私人 DNS。",
                color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    ToggleRow(
        "🧁", "App 内加密 DNS（DoH）",
        "网页的域名查询用 HTTPS 加密发给你选的服务商，不经过运营商。首次连接会慢几十毫秒（默认关）",
        prefs.enabled,
    ) { upd { it.copy(enabled = !it.enabled) } }
    Spacer(Modifier.height(10.dp))

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.pink.copy(alpha = 0.6f)).padding(16.dp)) {
        Text("🌸 服务商", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DohProvider.BUILT_IN.forEach { pr ->
                Chip(pr.label, prefs.providerId == pr.id) { upd { it.copy(providerId = pr.id) } }
            }
            Chip("自定义", prefs.providerId == DohProvider.CUSTOM_ID) { upd { it.copy(providerId = DohProvider.CUSTOM_ID) } }
        }
        val sel = DohProvider.BUILT_IN.firstOrNull { it.id == prefs.providerId }
        if (sel != null && sel.note.isNotEmpty()) {
            Text("${sel.label}：${sel.note}", color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (prefs.providerId == DohProvider.CUSTOM_ID) {
            var text by remember(prefs.customUrl) { mutableStateOf(prefs.customUrl) }
            val valid = DohProvider.custom(text) != null
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (text.isEmpty()) Text("https://example.com/dns-query", color = p.subText.copy(alpha = 0.6f), fontSize = 13.sp)
                    BasicTextField(
                        value = text, onValueChange = { text = it.trim() }, singleLine = true,
                        textStyle = TextStyle(color = p.text, fontSize = 13.sp),
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                SmallPill("保存", if (valid) p.accent else p.subText.copy(alpha = 0.4f), p.onAccent) {
                    if (valid) upd { it.copy(customUrl = text) }
                }
            }
            if (text.isNotEmpty() && !valid) Text("要以 https:// 开头哦", color = p.accentInk, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("自定义服务器的域名会先用系统 DNS 查一次（填 IP 地址的网址就不用）", color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // 测速
        var testing by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill(if (testing) "测速中…" else "测一测 ⏱️", p.accent, p.onAccent) {
                val pr = prefs.provider()
                if (testing) return@SmallPill
                if (pr == null) { result = "先填好自定义地址哦"; return@SmallPill }
                testing = true; result = null
                scope.launch {
                    try {
                        result = speedTestLine(pr)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        result = "😿 ${pr.label} 出错：${t.message ?: t.javaClass.simpleName}"
                    } finally {
                        testing = false
                    }
                }
            }
            SmallPill("全部测一测", p.lavender, p.text) {
                if (testing) return@SmallPill
                testing = true; result = "测速中…"
                scope.launch {
                    try {
                        val lines = ArrayList<String>()
                        for (pr in DohProvider.BUILT_IN) {
                            lines += try {
                                speedTestLine(pr, compact = true)
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                "${pr.label}：出错（${t.message ?: t.javaClass.simpleName}）"
                            }
                            result = lines.joinToString("\n")
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        result = (result ?: "") + "\n😿 测速中断：${t.message ?: t.javaClass.simpleName}"
                    } finally {
                        testing = false
                    }
                }
            }
        }
        result?.let { Text(it, color = p.text, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
    Spacer(Modifier.height(10.dp))
    ToggleRow(
        "🧱", "严格模式",
        "加密 DNS 连不上时，不退回系统 DNS（网页会打不开，但域名绝不明文发出去）",
        prefs.strict,
    ) { upd { it.copy(strict = !it.strict) } }

    // 状态 + 说明
    var stats by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status.active) {
        while (status.active) { stats = DohManager.statsText(); delay(1000) }
        stats = null
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.mint.copy(alpha = 0.7f)).padding(16.dp)) {
        val line = when {
            status.error != null -> "⚠️ ${status.error}"
            status.active -> "🟢 正在用 ${status.providerLabel} 加密查询"
            prefs.enabled -> "⏳ 正在启动…"
            else -> "⚪ 没有开启"
        }
        Text(line, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        stats?.let { Text(it, color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
        Text(
            "开启后，网页请求先经过 App 内只给自己用的本地代理，由它用加密 DNS 查地址；网页内容（https）照样端到端加密，代理看不到。\n" +
                "· 下载文件由系统下载器完成，仍然走系统 DNS\n" +
                "· 系统里设置的代理会被忽略（VPN 不受影响）\n" +
                "· 如果上面的系统私人 DNS 已经开着，一般不需要再开这个",
            color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.surface)
            .bouncyClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) p.onAccent else p.text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SmallPill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).bouncyClick(onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** 单个服务商测速，失败也返回文案（DohManager.test 已吞掉异常；这里再兜一层） */
private suspend fun speedTestLine(pr: DohProvider, compact: Boolean = false): String {
    val r = DohManager.test(pr)
    return if (r.ok) {
        if (compact) "${pr.label}：${r.warmMs} ms（首次 ${r.coldMs} ms）"
        else "✅ ${pr.label}：首次连接 ${r.coldMs} ms，之后每次查询 ${r.warmMs} ms\n${r.detail}"
    } else {
        "${pr.label}：连不上 😿（${r.detail}）"
    }
}
