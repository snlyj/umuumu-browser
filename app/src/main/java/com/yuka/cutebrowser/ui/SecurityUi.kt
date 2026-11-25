package com.yuka.cutebrowser.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.PermissionPrompt
import com.yuka.cutebrowser.browser.SecurityWarning
import com.yuka.cutebrowser.browser.TabState
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.security.WebFeatures
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.launch

/** MainActivity 提供：应用锁相关（需要 FragmentActivity + 生物识别） */
interface AppProtection {
    val locked: Boolean
    fun canLock(): Boolean
    fun authenticate(title: String, onSuccess: () -> Unit)
    fun setAppLock(on: Boolean)
}

/** 截图测试 / 预览用 */
object NoProtection : AppProtection {
    override val locked = false
    override fun canLock() = false
    override fun authenticate(title: String, onSuccess: () -> Unit) = onSuccess()
    override fun setAppLock(on: Boolean) {}
}

// ---------------- 应用锁 ----------------

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val p = LocalCutePalette.current
    BackHandler { } // 上锁时返回键不能绕过
    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BunnyMascot(Modifier.size(140.dp))
            Spacer(Modifier.height(16.dp))
            Text("唔姆唔姆上锁啦 🔐", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("验证一下是你本人，兔兔才开门哦", color = p.subText, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            PillButton("🔓 解锁", p.accent, p.onAccent, onUnlock)
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bouncyClick(onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

// ---------------- HTTPS / 证书提示页 ----------------

@Composable
fun SecurityWarningPage(tab: TabState, w: SecurityWarning, controller: BrowserController) {
    val p = LocalCutePalette.current
    val host = UrlUtils.host(w.url)
    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = p.accent, spotColor = p.accent)
                .clip(RoundedCornerShape(30.dp))
                .background(p.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (w.kind == SecurityWarning.Kind.CERT_ERROR) "🙀" else "🔒", fontSize = 48.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (w.kind == SecurityWarning.Kind.CERT_ERROR) "这个网站的证书有问题" else "这个网站不支持安全连接",
                color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (w.kind == SecurityWarning.Kind.CERT_ERROR)
                    "$host 的安全证书无效，可能有人在中间偷看或者冒充它。兔兔帮你拦下来啦，建议不要继续。"
                else
                    "唔姆唔姆想用加密的 https 打开 $host，但是没成功。用 http 打开的话，你在这个网站上看到和输入的内容可能会被别人看到哦。",
                color = p.subText, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PillButton("🐰 返回安全的地方", p.accent, p.onAccent, { controller.leaveWarning(tab) }, Modifier.fillMaxWidth())
            if (w.httpFallback != null) {
                Spacer(Modifier.height(10.dp))
                PillButton("仍然用 http 打开这个网站", p.pink, p.text, { controller.continueInsecure(tab) }, Modifier.fillMaxWidth())
                Text(
                    if (tab.incognito) "只在这次隐身期间记住" else "以后打开 $host 不再提醒（可以在设置里撤销）",
                    color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ---------------- 权限对话框 ----------------

@Composable
fun PermissionDialog(prompt: PermissionPrompt) {
    val p = LocalCutePalette.current
    var remember by remember(prompt) { mutableStateOf(!prompt.incognito) }
    Dialog(onDismissRequest = { prompt.onResult(false, false) }) { // 点外面 = 这次拒绝
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(p.surface)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(prompt.perms.joinToString(" ") { it.emoji }, fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text(UrlUtils.host(prompt.origin).ifBlank { prompt.origin }, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "想使用你的" + prompt.perms.joinToString("、") { it.label },
                color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text("不认识的网站就点「不给」吧，兔兔会帮你守着 🛡️", color = p.subText, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (!prompt.incognito) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).bouncyClick { remember = !remember }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (remember) "☑️" else "⬜", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("记住我的选择", color = p.text, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("不给 🙅", p.pink, p.text, { prompt.onResult(false, remember) }, Modifier.weight(1f))
                PillButton("允许", p.accent, p.onAccent, { prompt.onResult(true, remember) }, Modifier.weight(1f))
            }
        }
    }
}

// ---------------- 地址栏小盾牌 ----------------

@Composable
fun ShieldChip(tab: TabState, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (tab.isSecure) p.mint else p.pink)
            .bouncyClick(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (tab.isSecure) "🛡️" else "⚠️", fontSize = 13.sp)
        val n = tab.blockedCount
        if (n > 0) {
            Spacer(Modifier.width(2.dp))
            Text(if (n > 999) "999+" else n.toString(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ShieldDialog(tab: TabState, controller: BrowserController, onDismiss: () -> Unit) {
    val p = LocalCutePalette.current
    val host = UrlUtils.host(tab.url)
    val siteOn = host !in controller.adBlockOff
    val fpOn = controller.sec.antiFp
    val fpSiteOn = fpOn && !controller.fingerprint.isExempt(host)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(p.surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🛡️", fontSize = 40.sp)
            Text(host, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            InfoLine(if (tab.isSecure) "🔒" else "⚠️", if (tab.isSecure) "加密连接（https）" else "未加密连接（http），别在这里输入密码哦")
            InfoLine("🧹", if (controller.adBlockEnabled && siteOn) "本页拦下了 ${tab.blockedCount} 个广告/追踪请求" else "这个网站没有开启拦截")
            if (fpOn) InfoLine("🧬", if (fpSiteOn) "防指纹已开启（${if (controller.sec.fpStrict) "严格" else "标准"}）" else "这个网站没有开启防指纹")
            if (tab.incognito) InfoLine("🎭", "隐身标签：不记历史，关掉后 Cookie 和缓存一起清除")
            Spacer(Modifier.height(14.dp))
            if (controller.adBlockEnabled && host.isNotBlank()) {
                ToggleRow("🐰", "在这个网站拦截广告", "网页显示不正常时可以关掉试试", siteOn) {
                    controller.setSiteAdBlock(host, !siteOn)
                    onDismiss()
                }
            }
            if (controller.sec.tzOn && host.isNotBlank()) {
                var showTz by remember { mutableStateOf(false) }
                Spacer(Modifier.height(10.dp))
                SiteTzLangRow(host, controller) { showTz = true }
                if (showTz) SiteTzLangDialog(host, controller, onDismiss = { showTz = false; onDismiss() })
            }
            if (fpOn && host.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                ToggleRow("🧬", "在这个网站防指纹", "验证码、网银或地图显示不正常时可以关掉试试", fpSiteOn) {
                    controller.setSiteFingerprint(host, !fpSiteOn)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun InfoLine(emoji: String, text: String) {
    val p = LocalCutePalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = p.text, fontSize = 14.sp)
    }
}

// ---------------- 隐身主页 ----------------

@Composable
fun IncognitoHome(isolated: Boolean, modifier: Modifier = Modifier) {
    val p = LocalCutePalette.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(30.dp))
        BunnyMascot(Modifier.size(150.dp), masked = true)
        Spacer(Modifier.height(14.dp))
        Text("悄悄模式 🎭", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("兔兔戴上眼罩啦，这里逛过的地方不会留下脚印～", color = p.subText, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(18.dp),
        ) {
            InfoLine("🚫", "不记录历史，不保存标签页")
            InfoLine("🍪", if (isolated) "Cookie、缓存、网站存储和普通标签完全分开，关掉最后一个隐身标签就全部删除" else "关掉后清除缓存（这台设备的 WebView 版本较旧，Cookie 和普通标签共用）")
            InfoLine("📵", "防截屏、防录屏，最近任务里也看不到内容")
            InfoLine("👀", "网站、网络运营商和公司/学校的网络管理员仍然可能看到你访问了什么")
        }
    }
}

// ---------------- 设置：安全与隐私 ----------------

@Composable
fun SecuritySheet(controller: BrowserController, repo: CuteRepository, protection: AppProtection, onSitePerms: () -> Unit) {
    val p = LocalCutePalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sec by repo.security.collectAsState(initial = controller.sec)
    val httpAllowed by repo.httpAllowed.collectAsState(initial = emptySet())
    val upd: ((SecurityPrefs) -> SecurityPrefs) -> Unit = { f -> scope.launch { repo.updateSecurity(f) } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SheetHeader("安全与隐私", "🛡️")

        Label("上网保护")
        ToggleRow("🔒", "只用 HTTPS", "自动把 http 换成加密的 https；打不开时会先问你" +
            if (httpAllowed.isNotEmpty()) "（已允许 ${httpAllowed.size} 个网站用 http）" else "", sec.httpsOnly) {
            upd { it.copy(httpsOnly = !it.httpsOnly) }
        }
        Gap()
        ToggleRow("🍪", "拦截第三方 Cookie", "别的网站没法借嵌在页面里的广告/按钮跟踪你", sec.blockThirdPartyCookies) {
            upd { it.copy(blockThirdPartyCookies = !it.blockThirdPartyCookies) }
        }
        Gap()
        ToggleRow("📡", "防 WebRTC 泄露 IP", "关闭网页里的 WebRTC（网页视频通话会用不了）", sec.webrtcProtect) {
            upd { it.copy(webrtcProtect = !it.webrtcProtect) }
        }
        Gap()
        ToggleRow("🎭", "通用浏览器标识", "不告诉网站手机型号和「这是 App 内置网页」", sec.genericUa) {
            upd { it.copy(genericUa = !it.genericUa) }
        }
        Gap()
        ToggleRow("🙅", "请勿追踪 / GPC", "告诉网站：不要追踪我、不要卖我的数据", sec.dntGpc) {
            upd { it.copy(dntGpc = !it.dntGpc) }
        }
        Gap()
        ToggleRow("🧬", "防指纹", "给画布、WebGL、音频加一点点每个网站都不同的噪声，统一屏幕和硬件信息，网站很难认出你", sec.antiFp) {
            upd { it.copy(antiFp = !it.antiFp) }
        }
        if (sec.antiFp) {
            Spacer(Modifier.height(8.dp))
            FpLevelPicker(sec.fpStrict) { strict -> upd { it.copy(fpStrict = strict) } }
        }
        Gap()
        ToggleRow("✍️", "网页自动填充", "让系统的自动填充服务读取网页表单（默认关）", sec.autofill) {
            upd { it.copy(autofill = !it.autofill) }
        }
        Gap()
        SettingAction("网站权限（位置、相机、麦克风…）", "📍", p.lavender, onSitePerms)
        if (httpAllowed.isNotEmpty()) {
            Gap()
            SettingAction("重新对所有网站使用 HTTPS", "🔁", p.mint) {
                scope.launch { httpAllowed.forEach { repo.setHttpAllowed(it, false) } }
                controller.toast("好啦，又全部用 https 啦 🔒")
            }
        }

        Spacer(Modifier.height(18.dp))
        Label("时区与语言")
        val tzSites by repo.tzLangSites.collectAsState(initial = emptyMap())
        TzLangSection(sec, upd, tzSites) { scope.launch { repo.clearSiteTzLang() }; controller.setTzLangSites(emptyMap()) }

        Spacer(Modifier.height(18.dp))
        Label("加密 DNS")
        val dns = remember { privateDnsStatus(ctx) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.pink.copy(alpha = 0.6f)).padding(16.dp),
        ) {
            Text("🧅 系统私人 DNS：$dns", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "也可以不用下面 App 内的 DoH，直接用系统的「私人 DNS」（DNS over TLS）：对手机上所有 App 都生效，不经过唔姆唔姆的本地代理。开着下面的加密 DNS 时，网页的域名查询走 App 内的 DoH。" +
                    "可以在系统设置里填 dns.google、1dot1dot1dot1.cloudflare-dns.com 或 dns.alidns.com。",
                color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            PillButton("去系统设置 ⚙️", p.accent, p.onAccent, { openPrivateDnsSettings(ctx) })
        }
        Gap()
        DohSettingsSection()

        Spacer(Modifier.height(18.dp))
        Label("App 保护")
        ToggleRow("🔐", "应用锁", "打开 App 或离开超过 15 秒后，要用指纹/面容/锁屏密码解锁", sec.appLock) {
            protection.setAppLock(!sec.appLock)
        }
        Gap()
        ToggleRow("📵", "普通标签也防截屏", "隐身标签总是防截屏；打开后普通标签也不能截屏/录屏", sec.secureScreen) {
            upd { it.copy(secureScreen = !it.secureScreen) }
        }
        Gap()
        ToggleRow("🧹", "退出时清除数据", "退出后清掉 Cookie、网站存储、缓存、历史和标签页（收藏保留）", sec.clearOnExit) {
            upd { it.copy(clearOnExit = !it.clearOnExit) }
        }

        Spacer(Modifier.height(18.dp))
        Label("一直开着的保护")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.mint.copy(alpha = 0.7f)).padding(16.dp)) {
            listOf(
                "🚦 Google 安全浏览（拦截钓鱼/恶意网站）",
                "🚫 混合内容：https 页面里不加载 http 资源",
                "🗂️ 网页不能读手机里的文件",
                "🪟 没有点击就弹出的窗口一律拦下",
                "📊 关闭网页的广告归因上报",
            ).forEach { Text(it, color = p.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            Text(
                "过滤规则：${AdBlocker.loadedCount} 条（${AdBlocker.loadInfo}）\nWebView：${WebFeatures.webViewVersion(ctx)}" +
                    "\n隐身隔离：" + if (controller.incognitoIsolated) "独立 profile ✅" else "不支持（WebView 太旧）",
                color = p.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Gap() = Spacer(Modifier.height(10.dp))

@Composable
fun SitePermsSheet(repo: CuteRepository, controller: BrowserController) {
    val p = LocalCutePalette.current
    val scope = rememberCoroutineScope()
    val perms by repo.sitePerms.collectAsState(initial = controller.sitePerms)
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        SheetHeader("网站权限", "📍") {
            if (perms.isNotEmpty()) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(p.pink)
                        .bouncyClick { scope.launch { repo.clearSitePerms() }; controller.sitePerms = emptyList() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("全部重置", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text("网站每次想用位置、相机、麦克风时都会先问你，默认不给。这里是你让兔兔记住的选择。",
            color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        if (perms.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                Text("还没有记住任何网站的权限 🌷", color = p.subText, fontSize = 14.sp)
            }
        }
        perms.forEach { e ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(22.dp)).background(p.background)
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e.perm.emoji, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(UrlUtils.host(e.origin).ifBlank { e.origin }, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${e.perm.label}：" + if (e.allow) "允许 ✅" else "不允许 🙅", color = p.subText, fontSize = 12.sp)
                }
                JellyButton(onClick = {
                    controller.sitePerms = controller.sitePerms.filterNot { it.origin == e.origin && it.perm == e.perm }
                    scope.launch { repo.setSitePerm(e.origin, e.perm, null) }
                }, size = 34.dp, background = p.pink) {
                    Icon(CuteIcons.Close, "忘记", tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun privateDnsStatus(ctx: android.content.Context): String {
    if (Build.VERSION.SDK_INT < 28) return "系统版本太旧（Android 9 起才支持）"
    return runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return "没有联网"
        when {
            lp.privateDnsServerName != null -> "已加密（${lp.privateDnsServerName}）✅"
            lp.isPrivateDnsActive -> "已加密（自动）✅"
            else -> "没有加密 ⚠️"
        }
    }.getOrDefault("未知")
}

private fun openPrivateDnsSettings(ctx: android.content.Context) {
    val intents = listOf(Intent("android.settings.PRIVATE_DNS_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** 防指纹级别：标准 / 严格 */
@Composable
private fun FpLevelPicker(strict: Boolean, onPick: (Boolean) -> Unit) {
    val p = LocalCutePalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.lavender.copy(alpha = 0.55f)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (!strict) "标准 ✓" else "标准", if (!strict) p.accent else p.surface, if (!strict) p.onAccent else p.text, { onPick(false) }, Modifier.weight(1f))
            PillButton(if (strict) "严格 ✓" else "严格", if (strict) p.accent else p.surface, if (strict) p.onAccent else p.text, { onPick(true) }, Modifier.weight(1f))
        }
        Text(
            if (!strict) "推荐：几乎不影响网站，速度不受影响。"
            else "另外把时区报成 UTC、屏幕尺寸固定、计时精度降到 1 毫秒。网页上的时间可能会差几个小时，有些网站可能不正常。",
            color = p.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
