package com.yuka.cutebrowser.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yuka.cutebrowser.R
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.SearchEngine
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.privacy.fingerprint.FingerprintShield
import com.yuka.cutebrowser.privacy.fingerprint.FpSeed
import com.yuka.cutebrowser.privacy.locale.LangPolicy
import com.yuka.cutebrowser.privacy.locale.ProcessLocale
import com.yuka.cutebrowser.privacy.locale.SiteTzLang
import com.yuka.cutebrowser.privacy.locale.SpoofConfig
import com.yuka.cutebrowser.privacy.locale.TzLang
import com.yuka.cutebrowser.data.SitePerm
import com.yuka.cutebrowser.data.SitePermEntry
import com.yuka.cutebrowser.security.HttpsUpgrade
import com.yuka.cutebrowser.security.WebFeatures
import com.yuka.cutebrowser.security.WebHardening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.yuka.cutebrowser.dns.DohManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 一个标签页的状态（Compose 可观察） */
@Stable
class TabState(val id: Long, val incognito: Boolean = false) {
    /** WebView 按需创建；后台标签太多时会被回收（null），前进后退记录存进 [savedState] */
    var webView: WebView? = null
    /** 回收/换主题时保存的 WebView 状态（前进后退记录），下次显示时 restoreState */
    var savedState: Bundle? = null
    /** 上次退出时存到磁盘的状态还没读 */
    var hasDiskState = false
    /** WebView 还没创建时要打开的网址 */
    var pendingUrl: String? = null
    /** 为了补 Accept-Language 刚用 loadUrl 重发过的网址（防止重复处理） */
    var langFixedUrl: String? = null
    var lastUsed = 0L

    var url by mutableStateOf("")
    var title by mutableStateOf("新标签页")
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** true 时显示唔姆主页（Compose 绘制），而不是网页 */
    var isHome by mutableStateOf(true)

    // ---- 安全相关 ----
    /** 当前顶层页面的主机名（拦截回调在后台线程读，用来判断第三方请求） */
    @Volatile var pageHost: String? = null
    @Volatile var pageAllowlisted = false
    /** 这次导航是从哪个 http 地址自动升级来的（https 打不开时给"仍然继续"用） */
    @Volatile var upgradedFrom: String? = null
    /** 本页拦截了多少个请求（地址栏小盾牌显示） */
    var blockedCount by mutableIntStateOf(0)
    internal val blocked = AtomicInteger(0)
    internal val blockedPostPending = AtomicBoolean(false)
    /** 不为 null 时在网页上面盖一层可爱的安全提示 */
    var warning by mutableStateOf<SecurityWarning?>(null)
    internal var cosmeticFor: String? = null

    val displayTitle: String get() = if (isHome) (if (incognito) "隐身主页" else "唔姆主页") else title.ifBlank { url }
    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/** 安全提示页 */
@Immutable
data class SecurityWarning(val kind: Kind, val url: String, val httpFallback: String?) {
    enum class Kind { HTTPS_FAILED, CERT_ERROR }
}

/** 网站申请权限时弹的可爱对话框 */
@Immutable
data class PermissionPrompt(
    val origin: String,
    val perms: List<SitePerm>,
    val incognito: Boolean,
    val onResult: (allow: Boolean, remember: Boolean) -> Unit,
)

data class PendingDownload(val url: String, val userAgent: String?, val contentDisposition: String?, val mimeType: String?, val view: WebView?)

class BrowserController(
    private val activity: ComponentActivity,
    private val repo: CuteRepository,
    private val requestStoragePermission: () -> Unit,
    private val launchFileChooser: (Intent) -> Boolean,
    /** 申请 Android 运行时权限（相机/麦克风/位置），回调里告诉是否全部同意 */
    private val requestRuntimePermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
) {
    val tabs = mutableStateListOf<TabState>()
    var currentIndex by mutableIntStateOf(0)
        private set
    var engine: SearchEngine = SearchEngine.BING

    /** 拦截广告/追踪（设置里可开关，默认开）。WebView 的拦截回调在后台线程，所以用 @Volatile */
    @Volatile var adBlockEnabled = true
    /** 用户关掉拦截的网站 */
    @Volatile var adBlockOff: Set<String> = emptySet()
    /** 安全设置（见 [SecurityPrefs]） */
    @Volatile var sec = SecurityPrefs()
        private set
    /** 每个网站单独的时区/语言（必须在 init 之前初始化） */
    private var tzSites: Map<String, SiteTzLang> = emptyMap()
    /** 用户允许用 http 打开的网站（隐身模式下的选择只在本次有效） */
    @Volatile var httpAllowed: Set<String> = emptySet()
    private val incognitoHttpAllowed = HashSet<String>()
    var sitePerms: List<SitePermEntry> = emptyList()

    var permissionPrompt by mutableStateOf<PermissionPrompt?>(null)
        private set

    /** 本次隐身会话用的 WebView profile（null = 还没开始，或设备不支持多 profile） */
    private var incognitoProfile: String? = null
    val incognitoIsolated: Boolean get() = WebFeatures.multiProfile

    /** 网页是否变暗（星空梦境 + 设置开着）。变化时所有 WebView 保存状态后重建 */
    var webDark = false
        private set

    /** 每次重建 WebView 加一，界面用它当 key 重新挂载 */
    var webGeneration by mutableIntStateOf(0)
        private set

    val downloads = Downloads(activity.applicationContext, repo)
    /** 防指纹（脚本注入 + 每站种子），见 privacy/fingerprint */
    val fingerprint = FingerprintShield(activity.applicationContext)
    private val session = SessionStore(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    private var nextId = 1L
    private var pendingDownload: PendingDownload? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pendingBlobs = HashMap<String, Pair<PendingDownload, String?>>()
    private val blobBridge = BlobBridge { json, origin -> activity.runOnUiThread { onBlobMessage(json, origin) } }

    /** 空闲时预热好的一个 WebView，第一次打开网页时直接拿来用 */
    private var spare: WebView? = null

    val current: TabState get() = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]

    init {
        ProcessLocale.apply(LangPolicy.processLang(spoofConfig(), sec.antiFp)) // 必须在第一个 WebView 创建之前
        DohManager.acceptLanguageFor = { host -> acceptLanguageFor("http://$host/") }
        newTab()
        activity.lifecycleScope.launch(Dispatchers.IO) { fingerprint.preload() }
    }

    /** 安全设置变化：影响 WebView 创建参数的（UA / 注入脚本 / 自动填充）就重建 WebView；Cookie 设置直接改 */
    fun applySecurity(new: SecurityPrefs) {
        val old = sec
        sec = new
        val localeChanged = syncProcessLocale()
        if (old == new && !localeChanged) return
        if (old.blockThirdPartyCookies != new.blockThirdPartyCookies) {
            tabs.forEach { t -> t.webView?.let { cookieManagerFor(t).setAcceptThirdPartyCookies(it, !new.blockThirdPartyCookies) } }
        }
        if (old.genericUa != new.genericUa || old.webrtcProtect != new.webrtcProtect || old.dntGpc != new.dntGpc || old.autofill != new.autofill) {
            rebuildWebViews()
        } else if (localeChanged) {
            rebuildWebViews() // WebView 只在创建时读进程语言来生成 Accept-Language，所以要重建（前进后退记录会保留）
        }
        if (fingerprint.update(new.antiFp, new.fpStrict, fingerprint.exceptions, liveWebViews(), spoofConfig(new))) current.webView?.reload()
    }

    // ---------------- 时区 / 语言伪造 ----------------


    private fun spoofConfig(s: SecurityPrefs = sec) = SpoofConfig(s.tzOn, s.tz, s.lang, tzSites)

    /** 进程默认语言 = WebView 原生 Accept-Language 的来源；变了返回 true */
    private fun syncProcessLocale(): Boolean = ProcessLocale.apply(LangPolicy.processLang(spoofConfig(), sec.antiFp))

    /** 从 DataStore 读到的网站覆盖（启动时、以及盾牌菜单里改完之后） */
    fun setTzLangSites(m: Map<String, SiteTzLang>) {
        if (m == tzSites) return
        tzSites = m
        fingerprint.update(fingerprint.enabled, fingerprint.strict, fingerprint.exceptions, liveWebViews(), spoofConfig())
    }

    /** 盾牌菜单里改这个网站的时区/语言。null = 恢复跟随全局设置。改完用新请求头重新加载当前页 */
    fun setSiteTzLang(host: String, v: SiteTzLang?) {
        val site = FpSeed.site(host)
        if (site.isEmpty()) return
        setTzLangSites(if (v == null || v.isEmpty) tzSites - site else tzSites + (site to v))
        activity.lifecycleScope.launch { repo.setSiteTzLang(site, v) }
        reloadWithLang(current)
    }

    fun siteTzLang(host: String): SiteTzLang = tzSites[FpSeed.site(host)] ?: SiteTzLang()

    /** 这个网址的主页面请求该用什么 Accept-Language。和 WebView 原生发的一样就返回 null（不用额外处理） */
    fun acceptLanguageFor(url: String?): String? {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
        val site = FpSeed.site(host)
        val eff = LangPolicy.effective(spoofConfig(), sec.antiFp, fingerprint.isExempt(host), site)
        if (eff == ProcessLocale.current) return null
        return TzLang.acceptLanguage(eff ?: ProcessLocale.systemTag())
    }

    /** reload() 不会带自定义请求头，所以需要时改用 loadUrl */
    private fun reloadWithLang(tab: TabState) {
        val v = tab.webView ?: return
        val url = v.url ?: tab.url
        if (acceptLanguageFor(url) != null) v.loadUrl(url, extraHeaders(url)) else v.reload()
    }

    private fun liveWebViews(): List<WebView> = tabs.mapNotNull { it.webView } + listOfNotNull(spare)

    /** 用户关掉防指纹的网站（启动时从 DataStore 读出来） */
    fun setFpExceptions(sites: Set<String>) {
        fingerprint.update(fingerprint.enabled, fingerprint.strict, sites, liveWebViews())
    }

    /** 盾牌菜单里"在这个网站防指纹"开关：按 eTLD+1 记，改完刷新当前页 */
    fun setSiteFingerprint(host: String, enabled: Boolean) {
        val site = FpSeed.site(host)
        if (site.isEmpty()) return
        val set = if (enabled) fingerprint.exceptions - site else fingerprint.exceptions + site
        fingerprint.update(fingerprint.enabled, fingerprint.strict, set, liveWebViews())
        activity.lifecycleScope.launch { repo.setFpOff(site, !enabled) }
        reloadWithLang(current)
    }

    // ---------------- 会话保存 / 恢复 ----------------

    /** IO 线程调用 */
    fun loadSession(): SessionStore.Session? = session.load()

    /** 用上次的标签页替换启动时那个空白标签（不创建任何 WebView，显示时才创建并恢复） */
    fun restore(s: SessionStore.Session?) {
        if (s == null || s.tabs.isEmpty()) return
        val fresh = tabs.size == 1 && tabs[0].url.isBlank() && tabs[0].webView == null
        if (fresh) tabs.clear()
        s.tabs.forEach { st ->
            tabs.add(TabState(st.id).apply {
                url = st.url
                title = st.title.ifBlank { "新标签页" }
                isHome = st.isHome || st.url.isBlank()
                hasDiskState = st.url.isNotBlank()
            })
        }
        nextId = (tabs.maxOf { it.id }) + 1
        currentIndex = if (fresh) s.active.coerceIn(0, tabs.lastIndex) else tabs.lastIndex
        touch(current)
    }

    /** App 退到后台时调用：保存所有标签 + 各自的前进后退记录（写文件在 IO 线程） */
    fun saveSession() {
        if (sec.clearOnExit) return // 开了"退出时清除"：标签页不落盘
        val states = HashMap<Long, Bundle>()
        val keep = HashSet<Long>()
        val normal = tabs.filter { !it.incognito } // 隐身标签不写进磁盘
        normal.forEach { t ->
            val v = t.webView
            when {
                v != null -> snapshot(v)?.let { states[t.id] = it }
                t.savedState != null -> states[t.id] = t.savedState!!
                t.hasDiskState -> keep += t.id
            }
        }
        val cur = tabs.getOrNull(currentIndex)
        val snap = SessionStore.Session(
            normal.map { SessionStore.SavedTab(it.id, it.url, it.title, it.isHome) },
            normal.indexOf(cur).coerceAtLeast(0),
        )
        activity.lifecycleScope.launch(Dispatchers.IO) { session.write(snap, states, keep) }
    }

    private fun snapshot(v: WebView): Bundle? = runCatching {
        val b = Bundle()
        if (v.saveState(b) != null && !b.isEmpty) b else null
    }.getOrNull()

    // ---------------- WebView 生命周期 ----------------

    /** 首帧之后、主线程空闲时调用：提前加载 WebView 内核 + 预热常用网站的 DNS */
    fun prewarm() {
        if (spare == null && tabs.none { it.webView != null }) spare = createWebView(false)
        // 上次没删掉的隐身 profile（比如被系统杀掉了）在这里清理
        if (incognitoProfile == null && WebFeatures.multiProfile) {
            WebHardening.leftoverIncognitoProfiles().forEach { WebHardening.deleteProfile(it) }
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            DohManager.prewarm(PREWARM_HOSTS) // 开着加密 DNS 就走 DoH 预热，不然用系统 DNS
        }
    }

    /** 给标签页拿 WebView（没有就创建并恢复状态）。必须在主线程调用。 */
    fun webViewFor(tab: TabState): WebView {
        tab.webView?.let { return it }
        val v = (if (!tab.incognito) spare?.also { spare = null } else null) ?: createWebView(tab.incognito)
        v.tag = tab
        tab.webView = v
        configureClients(tab, v)

        val state = tab.savedState ?: if (tab.hasDiskState) session.readState(tab.id) else null
        tab.savedState = null
        tab.hasDiskState = false
        val restored = state != null && runCatching { v.restoreState(state) != null }.getOrDefault(false)
        val pending = tab.pendingUrl
        tab.pendingUrl = null
        when {
            pending != null -> v.loadUrl(pending, extraHeaders(pending))
            !restored && tab.url.isNotBlank() -> v.loadUrl(tab.url, extraHeaders(tab.url))
        }
        tab.canGoBack = v.canGoBack()
        tab.canGoForward = v.canGoForward()
        return v
    }

    fun newTab(url: String? = null, incognito: Boolean = false) {
        if (incognito && incognitoProfile == null && WebFeatures.multiProfile) {
            incognitoProfile = WebHardening.INCOGNITO_PREFIX + System.currentTimeMillis()
        }
        val tab = TabState(nextId++, incognito)
        tabs.add(tab)
        currentIndex = tabs.lastIndex
        touch(tab)
        if (!url.isNullOrBlank()) load(tab, url)
    }

    fun switchTo(index: Int) {
        if (index in tabs.indices) {
            currentIndex = index
            touch(tabs[index])
        }
    }

    private fun touch(tab: TabState) {
        tab.lastUsed = SystemClock.uptimeMillis()
        trimLiveWebViews(MAX_LIVE_WEBVIEWS)
    }

    /** 最多保留 [keep] 个存活的 WebView（当前标签优先，其次最近用过的），其余保存状态后回收 */
    private fun trimLiveWebViews(keep: Int) {
        val cur = tabs.getOrNull(currentIndex)
        val live = tabs.filter { it.webView != null }
        if (live.size <= keep) return
        live.filter { it !== cur }
            .sortedBy { it.lastUsed }
            .take(live.size - keep)
            .forEach { release(it) }
    }

    /** 回收一个标签的 WebView：先 saveState，之后显示时 restoreState，前进后退记录不丢 */
    private fun release(tab: TabState) {
        val v = tab.webView ?: return
        tab.savedState = snapshot(v)
        tab.webView = null
        tab.loading = false
        tab.progress = 100
        (v.parent as? ViewGroup)?.removeView(v)
        v.stopLoading()
        v.destroy()
    }

    /** 系统内存紧张：回收后台标签的 WebView 和预热的备用 WebView */
    fun onLowMemory() {
        spare?.destroy(); spare = null
        trimLiveWebViews(1)
    }

    /** 主题切换时：网页深色模式变了就重建所有 WebView（状态保存后恢复） */
    fun setWebDark(dark: Boolean) {
        if (dark == webDark) return
        webDark = dark
        rebuildWebViews()
    }

    /** 保存所有 WebView 的状态后销毁，界面重新挂载时按新设置重建 */
    private fun rebuildWebViews() {
        spare?.destroy(); spare = null
        val hadLive = tabs.any { it.webView != null }
        tabs.forEach { release(it) }
        if (hadLive) webGeneration++
    }

    fun closeTab(tab: TabState) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        tab.webView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.destroy()
        }
        tab.webView = null
        tab.savedState = null
        if (tab.incognito && tabs.none { it.incognito }) endIncognitoSession()
        if (tabs.isEmpty()) newTab()
        currentIndex = when {
            idx < currentIndex -> currentIndex - 1
            else -> currentIndex.coerceAtMost(tabs.lastIndex)
        }
        touch(current)
    }

    /** 关闭所有隐身标签（菜单里"结束隐身"） */
    fun closeAllIncognito() {
        tabs.filter { it.incognito }.forEach { closeTab(it) }
    }

    /** 最后一个隐身标签关掉了：删掉整个隐身 profile（Cookie、缓存、网站存储一起删） */
    private fun endIncognitoSession() {
        incognitoHttpAllowed.clear()
        fingerprint.endIncognito()
        val name = incognitoProfile ?: return
        incognitoProfile = null
        // WebView.destroy() 是异步收尾的，稍等一下再删；删不掉的话下次启动时再清
        main.postDelayed({
            if (!WebHardening.deleteProfile(name)) main.postDelayed({ WebHardening.deleteProfile(name) }, 5000)
        }, 1500)
    }

    /** 地址栏提交：网址或搜索词 */
    fun submit(input: String) {
        val url = UrlUtils.toUrl(input, engine)
        if (url.isNotEmpty()) load(current, url)
    }

    fun open(url: String) = load(current, url)

    private fun load(tab: TabState, raw: String) {
        // 最后一道关：javascript: / file: / content: 等不是"网页"，不让它进 WebView（外部 App 的 VIEW 意图、旧书签都会走到这里）
        if (!UrlUtils.isLoadable(raw)) { toast("这个地址打不开呢 🥺"); return }
        val up = upgradeFor(tab, raw)
        tab.upgradedFrom = if (up != null) raw else null
        val url = up ?: raw
        tab.warning = null
        tab.url = url
        tab.loading = true
        tab.progress = 5
        val v = tab.webView
        if (v != null) v.loadUrl(url, extraHeaders(url)) else tab.pendingUrl = url // 界面显示出来时再创建 WebView 并加载
        tab.isHome = false
    }

    fun goHome() { current.isHome = true; current.warning = null }

    // ---------------- HTTPS-only ----------------

    private fun upgradeFor(tab: TabState, url: String): String? {
        if (!sec.httpsOnly) return null
        val allowed = if (tab.incognito) httpAllowed + incognitoHttpAllowed else httpAllowed
        return HttpsUpgrade.upgrade(url, allowed)
    }

    /** 主动加载时带上 DNT / Sec-GPC（网页里点链接、子资源 WebView 不让我们加请求头，靠注入的 JS 信号） */
    private fun extraHeaders(url: String? = null): Map<String, String> {
        val al = if (url != null) acceptLanguageFor(url) else null
        return when {
            sec.dntGpc && al != null -> mapOf("DNT" to "1", "Sec-GPC" to "1", "Accept-Language" to al)
            sec.dntGpc -> mapOf("DNT" to "1", "Sec-GPC" to "1")
            al != null -> mapOf("Accept-Language" to al)
            else -> emptyMap()
        }
    }

    /** 安全提示页上点"仍然用 http 打开" */
    fun continueInsecure(tab: TabState) {
        val w = tab.warning ?: return
        val http = w.httpFallback ?: return
        val host = HttpsUpgrade.hostOf(http)
        if (tab.incognito) incognitoHttpAllowed += host
        else {
            httpAllowed = httpAllowed + host
            activity.lifecycleScope.launch { repo.setHttpAllowed(host, true) }
        }
        tab.warning = null
        tab.upgradedFrom = null
        tab.url = http
        tab.webView?.loadUrl(http, extraHeaders(http))
    }

    /** 安全提示页上点"返回" */
    fun leaveWarning(tab: TabState) {
        tab.warning = null
        val v = tab.webView
        if (v != null && v.canGoBack()) v.goBack() else tab.isHome = true
    }

    fun setSiteAdBlock(host: String, enabled: Boolean) {
        adBlockOff = if (enabled) adBlockOff - host else adBlockOff + host
        activity.lifecycleScope.launch { repo.setAdBlockOff(host, !enabled) }
        current.webView?.reload()
    }

    fun canGoBack(): Boolean {
        val t = current
        return if (t.isHome) t.url.isNotBlank() else true
    }

    fun canGoForward(): Boolean {
        val t = current
        return if (t.isHome) t.url.isNotBlank() else t.canGoForward
    }

    fun back() {
        val t = current
        val v = t.webView
        when {
            t.warning != null -> leaveWarning(t)
            t.isHome -> if (t.url.isNotBlank()) t.isHome = false
            v != null && v.canGoBack() -> v.goBack()
            else -> t.isHome = true
        }
    }

    fun forward() {
        val t = current
        val v = t.webView
        when {
            t.isHome && t.url.isNotBlank() -> t.isHome = false
            v != null && v.canGoForward() -> v.goForward()
        }
    }

    fun reloadOrStop() {
        val t = current
        if (t.isHome) return
        val v = t.webView ?: return
        if (t.loading) v.stopLoading() else v.reload()
    }

    fun clearCache() {
        // WebView 的磁盘缓存是整个 App 共用的，随便找一个 WebView 清就行
        val v = tabs.firstNotNullOfOrNull { it.webView } ?: spare ?: createWebView(false).also { spare = it }
        v.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        toast("缓存和 Cookie 清理好啦 ✨")
    }

    fun clearWebHistory() {
        tabs.forEach { it.webView?.clearHistory(); it.savedState = null }
    }

    /** 整个 App 退到后台时：保存会话，暂停所有网页的 JS/计时器 */
    fun onHostPause() {
        saveSession()
        tabs.forEach { it.webView?.onPause() }
        tabs.firstNotNullOfOrNull { it.webView }?.pauseTimers()
    }

    fun onHostResume() {
        tabs.firstNotNullOfOrNull { it.webView }?.resumeTimers()
        val cur = tabs.getOrNull(currentIndex)
        if (cur != null && !cur.isHome) cur.webView?.onResume()
    }

    fun destroyAll() {
        tabs.forEach { it.webView?.destroy(); it.webView = null }
        spare?.destroy(); spare = null
        incognitoProfile?.let { name -> incognitoProfile = null; main.post { WebHardening.deleteProfile(name) } }
    }

    /** "退出时清除数据"：Cookie、网站存储、缓存、历史、打开的标签（收藏保留） */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun clearAllDataOnExit(done: () -> Unit) {
        tabs.firstNotNullOfOrNull { it.webView }?.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        val ctx = activity.applicationContext
        val r = repo
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            // Activity 已经在销毁了，用全局作用域把剩下的写完
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                SessionStore(ctx).clear()
                r.clearHistory()
                done()
            }
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        val p = pendingDownload ?: return
        pendingDownload = null
        if (granted) startDownload(p) else toast("没有存储权限，下载不了呢 🥺")
    }

    fun onFileChosen(result: Array<Uri>?) {
        fileCallback?.onReceiveValue(result)
        fileCallback = null
    }

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(incognito: Boolean): WebView {
        // 网页深色：WebView 根据所在 Context 主题的 isLightTheme 决定 prefers-color-scheme；
        // 自带深色主题的网站用它们自己的，没有的再由"算法变暗"处理
        val ctx = if (webDark || incognito) ContextThemeWrapper(activity, R.style.Theme_CuteBrowser_WebDark) else activity
        val wv = WebView(ctx)
        if (incognito) {
            // 隐身：用独立的 profile（Cookie/缓存/存储都和普通标签分开，结束时整个删掉）。必须是 WebView 的第一个调用
            val profile = incognitoProfile
            val isolated = profile != null && WebHardening.useProfile(wv, profile)
            if (!isolated) wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        setupWebView(wv, incognito)
        return wv
    }

    private fun cookieManagerFor(tab: TabState?): CookieManager =
        if (tab?.incognito == true && incognitoProfile != null) WebHardening.cookieManager(incognitoProfile) else CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, incognito: Boolean) = wv.apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            if (!incognito) cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = true
        }
        WebHardening.lockDown(this, sec)
        fingerprint.attach(this, incognito)
        val dark = webDark || incognito
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
            }
        }
        if (dark) setBackgroundColor(if (incognito) 0xFF2B2140.toInt() else 0xFF24254D.toInt())
        // 唯一暴露给网页的接口：blob 下载桥（一次性 token + 能检查来源时检查来源）
        WebHardening.installBlobBridge(this, BlobBridge.NAME, { json, origin -> activity.runOnUiThread { onBlobMessage(json, origin) } }, blobBridge)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 当前可见的页面渲染进程优先级高；不可见时让系统可以回收
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        val cm = if (incognito && incognitoProfile != null) WebHardening.cookieManager(incognitoProfile) else CookieManager.getInstance()
        cm.setAcceptThirdPartyCookies(this, !sec.blockThirdPartyCookies)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            requestDownload(PendingDownload(url, userAgent, contentDisposition, mimeType, this))
        }
    }

    private fun postBlocked(tab: TabState) {
        if (tab.blockedPostPending.compareAndSet(false, true)) {
            main.post {
                tab.blockedPostPending.set(false)
                tab.blockedCount = tab.blocked.get()
            }
        }
    }

    /** 隐藏广告元素（只用带域名的 ## 规则，生成一段 CSS 插进页面） */
    private fun injectCosmetic(view: WebView, tab: TabState, url: String?) {
        if (url == null || !adBlockEnabled || tab.pageAllowlisted) return
        val host = Uri.parse(url).host ?: return
        if (host in adBlockOff) return
        if (tab.cosmeticFor == url) return
        tab.cosmeticFor = url
        val css = AdBlocker.cosmeticCss(url, host) ?: return
        val q = org.json.JSONObject.quote(css)
        view.evaluateJavascript(
            "(function(){if(document.getElementById('umu-hide'))return;var s=document.createElement('style');s.id='umu-hide';" +
                "s.textContent=$q;(document.head||document.documentElement).appendChild(s);})()",
            null,
        )
    }

    private fun configureClients(tab: TabState, wv: WebView) {
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" && request.isForMainFrame) {
                    val up = upgradeFor(tab, uri.toString())
                    if (up != null) {
                        tab.upgradedFrom = uri.toString()
                        view.loadUrl(up, extraHeaders(up))
                        return true
                    }
                }
                // 这个网站的 Accept-Language 和 WebView 原生发的不一样（网站单独设了语言 / 防指纹例外）：GET 导航改用 loadUrl 带上正确的请求头
                if (request.isForMainFrame && (scheme == "http" || scheme == "https") && request.method == "GET" && !request.isRedirect &&
                    tab.langFixedUrl != uri.toString()
                ) {
                    val al = acceptLanguageFor(uri.toString())
                    if (al != null) {
                        tab.langFixedUrl = uri.toString()
                        view.loadUrl(uri.toString(), extraHeaders(uri.toString()))
                        return true
                    }
                }
                if (request.isForMainFrame && (scheme == "http" || scheme == "https")) {
                    tab.warning = null
                    if (scheme == "https" && tab.upgradedFrom != null &&
                        HttpsUpgrade.hostOf(uri.toString()) != HttpsUpgrade.hostOf(tab.upgradedFrom!!)
                    ) tab.upgradedFrom = null
                }
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                // 其它 scheme（比如 App 唤起链接）：只在用户主动点击时尝试打开对应 App
                if (request.hasGesture()) openExternal(uri.toString(), view)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val host = uri.host ?: return null
                if (request.isForMainFrame) {
                    // 新的顶层页面：记下主机名，计数清零（后台线程）
                    tab.pageHost = host.lowercase()
                    tab.pageAllowlisted = adBlockEnabled && AdBlocker.pageAllowlisted(uri.toString(), host)
                    tab.blocked.set(0)
                    postBlocked(tab)
                    return null
                }
                if (!adBlockEnabled || tab.pageAllowlisted) return null
                val page = tab.pageHost
                if (page != null && page in adBlockOff) return null
                if (AdBlocker.shouldBlock(uri.toString(), host, page, request.requestHeaders["Accept"])) {
                    tab.blocked.incrementAndGet()
                    postBlocked(tab)
                    return AdBlocker.emptyResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // 兜底：前进/后退/恢复的 http 页面没经过 shouldOverrideUrlLoading，这里再升级一次
                if (url != null && url.startsWith("http://", ignoreCase = true)) {
                    val up = upgradeFor(tab, url)
                    if (up != null) {
                        view.stopLoading()
                        tab.upgradedFrom = url
                        view.loadUrl(up, extraHeaders(up))
                        return
                    }
                }
                tab.loading = true
                tab.cosmeticFor = null
                if (url != null) {
                    tab.url = url
                    Uri.parse(url).host?.let { tab.pageHost = it.lowercase() }
                    if (tab.warning != null && tab.warning?.url != url) tab.warning = null
                }
                // 旧 WebView 不支持"文档开始脚本"时，尽早补注入（可能比网页脚本晚一点）
                if (!WebFeatures.documentStartScript && (sec.dntGpc || sec.webrtcProtect)) {
                    view.evaluateJavascript(WebHardening.privacyScript(sec.dntGpc, sec.webrtcProtect), null)
                }
                fingerprint.fallbackScript(tab.incognito)?.let { view.evaluateJavascript(it, null) }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                injectCosmetic(view, tab, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.loading = false
                tab.langFixedUrl = null
                tab.progress = 100
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                val u = url ?: return
                tab.url = u
                injectCosmetic(view, tab, u)
                if (tab.incognito) return // 隐身：不记历史
                val title = view.title.orEmpty()
                if (tab.webView === view) activity.lifecycleScope.launch { repo.addHistory(u, title) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val failed = request.url.toString()
                val from = tab.upgradedFrom ?: return
                if (!failed.startsWith("https://", ignoreCase = true)) return
                if (HttpsUpgrade.hostOf(failed) != HttpsUpgrade.hostOf(from)) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && error.errorCode == ERROR_HOST_LOOKUP) return // 域名都解析不了，http 也一样打不开
                tab.warning = SecurityWarning(SecurityWarning.Kind.HTTPS_FAILED, failed, from)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // 证书有问题一律不继续
                val u = error.url ?: return
                if (u != tab.url && u != view.url) return // 子资源的证书错误：静默拦下
                val from = tab.upgradedFrom?.takeIf { HttpsUpgrade.hostOf(it) == HttpsUpgrade.hostOf(u) }
                tab.warning = SecurityWarning(if (from != null) SecurityWarning.Kind.HTTPS_FAILED else SecurityWarning.Kind.CERT_ERROR, u, from)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) tab.url = url
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                // 网页渲染进程崩了/被系统杀了：把这个 WebView 扔掉，下次显示时重建，不让整个 App 崩
                if (tab.webView === view) {
                    tab.webView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    tab.loading = false
                    if (tabs.getOrNull(currentIndex) === tab) webGeneration++
                } else view.destroy()
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (newProgress >= 100) tab.loading = false
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // 没有用户点击的弹窗一律拦下
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                newTab(incognito = tab.incognito)
                val nt = current
                nt.isHome = false
                nt.loading = true
                val v = webViewFor(nt)
                transport.webView = v
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                handleWebPermission(tab, request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (permissionPrompt?.origin == request.origin.toString()) permissionPrompt = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                askSitePermission(tab, origin, listOf(SitePerm.LOCATION)) { granted ->
                    callback.invoke(origin, SitePerm.LOCATION in granted, false) // 不让 WebView 自己记，我们自己管
                }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                if (permissionPrompt?.perms == listOf(SitePerm.LOCATION)) permissionPrompt = null
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val ok = runCatching { launchFileChooser(fileChooserParams.createIntent()) }.getOrDefault(false)
                if (!ok) {
                    fileCallback = null
                    filePathCallback.onReceiveValue(null)
                }
                return ok
            }
        }
    }

    // ---------------- 网站权限 ----------------

    private fun handleWebPermission(tab: TabState, request: PermissionRequest) {
        val map = LinkedHashMap<SitePerm, String>()
        request.resources.forEach { r ->
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> map[SitePerm.CAMERA] = r
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> map[SitePerm.MIC] = r
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> map[SitePerm.DRM] = r
                PermissionRequest.RESOURCE_MIDI_SYSEX -> map[SitePerm.MIDI] = r
            }
        }
        if (map.isEmpty()) { request.deny(); return }
        askSitePermission(tab, request.origin.toString(), map.keys.toList()) { granted ->
            val res = granted.mapNotNull { map[it] }.toTypedArray()
            if (res.isEmpty()) request.deny() else request.grant(res)
        }
    }

    /**
     * 统一的权限流程：记住过的直接用；没记住的弹可爱对话框问（默认拒绝）；
     * 同意后如果还缺 Android 系统权限，再向系统申请。
     */
    private fun askSitePermission(tab: TabState, origin: String, perms: List<SitePerm>, done: (Set<SitePerm>) -> Unit) {
        val remembered = if (tab.incognito) emptyList() else sitePerms.filter { it.origin == origin }
        val granted = HashSet<SitePerm>()
        val ask = ArrayList<SitePerm>()
        perms.forEach { p ->
            val r = remembered.firstOrNull { it.perm == p }
            when {
                r == null -> ask += p
                r.allow -> granted += p
            }
        }
        val finish: (Set<SitePerm>) -> Unit = { g -> withRuntime(g, done) }
        if (ask.isEmpty()) { finish(granted); return }
        if (permissionPrompt != null) { finish(granted); return } // 已经有一个对话框了：这次默认拒绝
        permissionPrompt = PermissionPrompt(origin, ask, tab.incognito) { allow, remember ->
            permissionPrompt = null
            if (allow) granted += ask
            if (remember && !tab.incognito) {
                ask.forEach { p ->
                    sitePerms = listOf(SitePermEntry(origin, p, allow)) + sitePerms.filterNot { it.origin == origin && it.perm == p }
                    activity.lifecycleScope.launch { repo.setSitePerm(origin, p, allow) }
                }
            }
            finish(granted)
        }
    }

    private fun withRuntime(granted: Set<SitePerm>, done: (Set<SitePerm>) -> Unit) {
        val need = ArrayList<String>()
        if (SitePerm.CAMERA in granted) need += Manifest.permission.CAMERA
        if (SitePerm.MIC in granted) need += Manifest.permission.RECORD_AUDIO
        if (SitePerm.LOCATION in granted) { need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION }
        val missing = need.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { done(granted); return }
        requestRuntimePermissions(missing.toTypedArray()) {
            // 系统权限没给的那一项就当拒绝（位置只要粗略或精确有一个就行）
            val ok = granted.filterTo(HashSet()) { p ->
                when (p) {
                    SitePerm.CAMERA -> has(Manifest.permission.CAMERA)
                    SitePerm.MIC -> has(Manifest.permission.RECORD_AUDIO)
                    SitePerm.LOCATION -> has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else -> true
                }
            }
            done(ok)
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    /** 网页点链接唤起别的 App。参考 Chrome 的做法把 intent: 里网页能控制的东西都清掉，免得网页借它做别的事 */
    private fun openExternal(url: String, view: WebView) {
        try {
            val intent = if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            // intent: 能带 launchFlags：清掉（尤其是 URI 读写授权，网页不该有这个权力），也不让它指向我们自己
            intent.flags = 0
            intent.clipData = null
            if (intent.`package` == activity.packageName) { toast("这个链接打不开呢 🥺"); return }
            when (intent.data?.scheme?.lowercase()) {
                "file", "content", "javascript", "android-app" -> { toast("这个链接打不开呢 🥺"); return }
            }
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // browser_fallback_url 只认 http(s)：`javascript:` 之类会在当前页面里执行（子框架里的链接就能打到顶层页面）
                val fallback = intent.getStringExtra("browser_fallback_url")
                val tab = view.tag as? TabState
                if (UrlUtils.isWebUrl(fallback) && tab != null) load(tab, fallback!!) // 走 load：HTTPS 升级照常
                else toast("没有找到能打开它的 App 哦")
            }
        } catch (e: Exception) {
            toast("这个链接打不开呢 🥺")
        }
    }

    // ---------------- 下载 ----------------

    private fun requestDownload(p: PendingDownload) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = p
            requestStoragePermission()
            return
        }
        startDownload(p)
    }

    private fun startDownload(p: PendingDownload) {
        when (Uri.parse(p.url).scheme?.lowercase()) {
            "http", "https" -> startHttpDownload(p)
            "data" -> saveDataUrl(p)
            "blob" -> startBlobDownload(p)
            else -> toast("暂时不支持这种下载哦 🥺")
        }
    }

    private fun startHttpDownload(p: PendingDownload) {
        try {
            val fileName = Downloads.fileName(p.url, p.contentDisposition, p.mimeType)
            val req = DownloadManager.Request(Uri.parse(p.url)).apply {
                p.mimeType?.let { setMimeType(it) }
                p.userAgent?.let { addRequestHeader("User-Agent", it) }
                cookieManagerFor(p.view?.tag as? TabState).getCookie(p.url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setDescription("唔姆唔姆正在努力下载中～")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("开始下载：$fileName 🎀")
        } catch (e: Exception) {
            toast("下载失败了：${e.message ?: "未知错误"}")
        }
    }

    private fun saveDataUrl(p: PendingDownload) {
        activity.lifecycleScope.launch {
            val decoded = kotlinx.coroutines.withContext(Dispatchers.Default) { Downloads.decodeDataUrl(p.url) }
            if (decoded == null) { toast("这个文件解析不了呢 🥺"); return@launch }
            val (mime, bytes) = decoded
            saveAndToast(Downloads.fileName(p.url, p.contentDisposition, p.mimeType ?: mime), p.mimeType ?: mime, bytes)
        }
    }

    /** blob: 只存在于网页内存里，让网页自己 fetch 出来转成 base64 再通过一次性 token 交给我们 */
    private fun startBlobDownload(p: PendingDownload) {
        val view = p.view ?: run { toast("下载失败了 🥺"); return }
        if (pendingBlobs.size >= 3) { toast("前面的文件还在准备中，稍等一下哦"); return }
        val token = UUID.randomUUID().toString()
        pendingBlobs[token] = p to originOf(view.url)
        toast("正在准备文件… 🎀")
        view.evaluateJavascript(BlobBridge.script(token, p.url, Downloads.MAX_BLOB_BYTES), null)
    }

    private fun originOf(url: String?): String? {
        val u = url?.let { Uri.parse(it) } ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        return if (u.port != -1) "$scheme://$host:${u.port}" else "$scheme://$host"
    }

    private fun onBlobMessage(json: String, origin: String?) {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        val token = o.optString("t")
        val entry = pendingBlobs[token] ?: return // 不是我们发起的，忽略
        // 能拿到来源时再核对一次：必须是发起下载的那个网站
        if (origin != null && entry.second != null && originOf(origin) != entry.second) return
        pendingBlobs.remove(token)
        val p = entry.first
        val dataUrl = o.optString("d").takeIf { it.isNotEmpty() }
        val mime = o.optString("m")
        val error = o.optString("e")
        if (dataUrl == null) {
            toast(if (error == "too_large") "文件太大啦（超过 50MB）🥺" else "下载失败了，网页可能已经把文件收回了 🥺")
            return
        }
        activity.lifecycleScope.launch {
            val decoded = kotlinx.coroutines.withContext(Dispatchers.Default) { Downloads.decodeDataUrl(dataUrl) }
            if (decoded == null) { toast("这个文件解析不了呢 🥺"); return@launch }
            val type = p.mimeType?.takeIf { it.isNotBlank() } ?: mime.takeIf { it.isNotBlank() } ?: decoded.first
            saveAndToast(Downloads.fileName(p.url, p.contentDisposition, type), type, decoded.second)
        }
    }

    private suspend fun saveAndToast(name: String, mime: String, bytes: ByteArray) {
        if (downloads.saveBytes(name, mime, bytes)) toast("已保存到「下载」：$name 🎀") else toast("保存失败了 🥺")
    }

    fun openSystemDownloads() {
        try {
            activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("找不到系统下载页面呢")
        }
    }

    fun shareCurrent() {
        val t = current
        if (t.isHome || t.url.isBlank()) { toast("先打开一个网页再分享吧～"); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, t.title)
            putExtra(Intent.EXTRA_TEXT, "${t.title}\n${t.url}")
        }
        activity.startActivity(Intent.createChooser(send, "分享给好朋友 💌"))
    }

    private companion object {
        const val MAX_LIVE_WEBVIEWS = 3
        val PREWARM_HOSTS = listOf(
            "m.bilibili.com", "www.xiaohongshu.com", "m.weibo.cn", "www.baidu.com", "www.zhihu.com", "m.youtube.com",
        )
    }
}
