package com.yuka.cutebrowser.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yuka.cutebrowser.data.Backup
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页里的「备份与恢复」（v1.7）。书签 + 设置导出成 JSON（可加密）或书签 HTML，从文件导入时合并去重 */
private sealed interface BackupStep {
    data object Menu : BackupStep
    data object ExportOptions : BackupStep
    data class Password(val text: String) : BackupStep
    data class Preview(val content: Backup.Content, val fromHtml: Boolean) : BackupStep
    data object Busy : BackupStep
}

@Composable
internal fun BackupAction(repo: CuteRepository) {
    val p = LocalCutePalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var opts by remember { mutableStateOf(Backup.Options()) }
    var exportPassword by remember { mutableStateOf("") }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun stamp() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pw = exportPassword.toCharArray()
        exportPassword = ""
        if (uri == null) { step = null; pw.fill(' '); return@rememberLauncherForActivityResult }
        step = BackupStep.Busy
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val c = repo.backupSnapshot(withHistory = opts.history)
                    var text = Backup.toJson(c, opts)
                    if (pw.isNotEmpty()) text = Backup.encrypt(text, pw)
                    writeText(ctx, uri, text)
                }
            }.isSuccess
            pw.fill(' ')
            step = null
            toast(if (ok) "备份好啦 🎀" + (if (opts.history) "（含历史记录）" else "") else "备份失败了 🥺")
        }
    }
    val exportHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) { step = null; return@rememberLauncherForActivityResult }
        step = BackupStep.Busy
        scope.launch {
            val n = runCatching {
                withContext(Dispatchers.IO) {
                    val c = repo.backupSnapshot(withHistory = false)
                    writeText(ctx, uri, Backup.toNetscapeHtml(c.bookmarks))
                    c.bookmarks.size
                }
            }.getOrNull()
            step = null
            toast(if (n != null) "导出了 $n 个书签，其它浏览器的「导入书签」就能用 💗" else "导出失败了 🥺")
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { step = null; return@rememberLauncherForActivityResult }
        step = BackupStep.Busy
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { readText(ctx, uri) } }
            val text = r.getOrNull()
            step = when {
                text == null -> { toast("读不了这个文件 🥺"); null }
                Backup.isEncrypted(text) -> BackupStep.Password(text)
                Backup.looksLikeNetscape(text) -> {
                    val b = withContext(Dispatchers.Default) { Backup.fromNetscapeHtml(text) }
                    if (b.isEmpty()) { toast("这个文件里没有找到书签哦"); null } else BackupStep.Preview(Backup.Content(b, null, null), true)
                }
                else -> runCatching { BackupStep.Preview(Backup.fromJson(text), false) }
                    .getOrElse { e -> toast((e as? Backup.BadBackup)?.message ?: "不是有效的备份文件"); null }
            }
        }
    }

    SettingAction("备份与恢复（书签和设置）", "🗃️", p.lavender) { step = BackupStep.Menu }

    when (val s = step) {
        null -> Unit
        BackupStep.Busy -> CuteSheetDialog("⏳", "处理中…", onDismiss = {}) {
            Text("稍等一下下～", color = p.subText, fontSize = 14.sp)
        }
        BackupStep.Menu -> CuteSheetDialog("🗃️", "备份与恢复", onDismiss = { step = null }) {
            Text("备份只存在你自己选的位置，唔姆唔姆不会上传到任何地方。Cookie 从不备份。", color = p.subText, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            CutePill("📦 备份书签和设置", p.accent, p.onAccent, { step = BackupStep.ExportOptions }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CutePill("💗 导出书签给其它浏览器（.html）", p.pink, p.text, { exportHtml.launch("umu-bookmarks-${stamp()}.html") }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CutePill("📥 从文件恢复 / 导入书签", p.mint, p.text, {
                importFile.launch(arrayOf("application/json", "text/html", "text/plain", "application/octet-stream", "*/*"))
            }, Modifier.fillMaxWidth())
        }
        BackupStep.ExportOptions -> {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            CuteSheetDialog("📦", "要备份哪些？", onDismiss = { step = null }) {
                CuteCheckRow("书签", null, opts.bookmarks) { opts = opts.copy(bookmarks = !opts.bookmarks) }
                CuteCheckRow("设置", "主题、搜索引擎、安全与隐私开关", opts.settings) { opts = opts.copy(settings = !opts.settings) }
                if (opts.settings) CuteCheckRow("网站单独的设置", "关掉拦截/防指纹的网站、网站时区、记住的权限（会带上这些网站名）", opts.siteLists) {
                    opts = opts.copy(siteLists = !opts.siteLists)
                }
                CuteCheckRow("历史记录", "默认不备份：别人拿到文件就能看到你逛过哪里", opts.history) { opts = opts.copy(history = !opts.history) }
                Spacer(Modifier.height(10.dp))
                Text("设置密码（可选）：用 AES-256 加密，忘了密码就打不开了哦", color = p.subText, fontSize = 12.sp)
                PasswordField(pw, "密码（不填就不加密）") { pw = it }
                if (pw.isNotEmpty()) PasswordField(pw2, "再输一次") { pw2 = it }
                val mismatch = pw.isNotEmpty() && pw != pw2
                if (mismatch && pw2.isNotEmpty()) Text("两次输入的密码不一样", color = p.accentInk, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                CutePill("选择保存位置", p.accent, p.onAccent, {
                    exportPassword = pw
                    pw = ""; pw2 = ""
                    exportJson.launch("umu-backup-${stamp()}.json")
                }, Modifier.fillMaxWidth(), enabled = !mismatch && (opts.bookmarks || opts.settings || opts.history))
            }
        }
        is BackupStep.Password -> {
            var pw by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            CuteSheetDialog("🔐", "这个备份加密了", onDismiss = { step = null }) {
                PasswordField(pw, "备份密码") { pw = it; wrong = false }
                if (wrong) Text("密码不对哦，再试试？", color = p.accentInk, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                CutePill("解开", p.accent, p.onAccent, {
                    val chars = pw.toCharArray()
                    step = BackupStep.Busy
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.Default) { Backup.fromJson(Backup.decrypt(s.text, chars)) } }
                        chars.fill(' ')
                        step = r.fold(
                            onSuccess = { BackupStep.Preview(it, false) },
                            onFailure = { e ->
                                when (e) {
                                    is Backup.WrongPassword -> { wrong = true; s }
                                    else -> { toast((e as? Backup.BadBackup)?.message ?: "备份文件已损坏 🥺"); null }
                                }
                            },
                        )
                    }
                }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
            }
        }
        is BackupStep.Preview -> {
            val c = s.content
            var bm by remember(s) { mutableStateOf(c.bookmarks.isNotEmpty()) }
            var st by remember(s) { mutableStateOf(false) }
            var hi by remember(s) { mutableStateOf(false) }
            CuteSheetDialog("📥", "要导入哪些？", onDismiss = { step = null }) {
                if (c.bookmarks.isNotEmpty()) CuteCheckRow("书签（${c.bookmarks.size} 个）", "和现有书签合并，重复的跳过", bm) { bm = !bm }
                if (c.settings != null) CuteCheckRow("设置", "会覆盖现在的主题、搜索引擎和安全开关；网站名单合并", st) { st = !st }
                if (!c.history.isNullOrEmpty()) CuteCheckRow("历史记录（${c.history.size} 条）", "合并到现在的历史里", hi) { hi = !hi }
                if (c.bookmarks.isEmpty() && c.settings == null && c.history.isNullOrEmpty()) {
                    Text("这个备份里什么都没有呢", color = p.subText, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                CutePill("导入", p.accent, p.onAccent, {
                    step = BackupStep.Busy
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { repo.applyBackup(c, bm, st, hi) } }.getOrNull()
                        step = null
                        toast(
                            if (r == null) "导入失败了 🥺"
                            else buildString {
                                append("导入好啦 🎀")
                                if (bm) append(" 新增 ${r.first} 个书签")
                                if (hi) append("，${r.second} 条历史")
                                if (st) append("，设置已恢复")
                            },
                        )
                    }
                }, Modifier.fillMaxWidth(), enabled = bm || st || hi)
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "")) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** 圆角卡片样式的对话框（内容可滚动） */
@Composable
private fun CuteSheetDialog(emoji: String, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalCutePalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(30.dp)).background(p.surface)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(emoji, fontSize = 36.sp)
            Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private fun writeText(ctx: Context, uri: Uri, text: String) {
    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no stream")
    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

private fun readText(ctx: Context, uri: Uri): String {
    val inp = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")
    inp.use { s ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > Backup.MAX_FILE_BYTES) throw java.io.IOException("too large")
        }
        return buf.toString("UTF-8")
    }
}
