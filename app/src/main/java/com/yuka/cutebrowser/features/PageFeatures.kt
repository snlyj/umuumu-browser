package com.yuka.cutebrowser.features

import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Immutable
import androidx.lifecycle.lifecycleScope
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.security.WebHardening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import com.yuka.cutebrowser.security.WebFeatures
import java.util.WeakHashMap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.TabState

/** 页面内查找的状态（只对一个标签页有效；切换标签就关掉） */
@Stable
class FindState {
    var open by mutableStateOf(false)
        internal set
    var query by mutableStateOf("")
        internal set
    /** 当前高亮的是第几个（从 0 开始） */
    var active by mutableIntStateOf(0)
        internal set
    var total by mutableIntStateOf(0)
        internal set
    /** WebView 还在数 */
    var counting by mutableStateOf(false)
        internal set
    var tabId = -1L
        internal set

    /** 给查找栏显示的计数文字 */
    val countText: String
        get() = when {
            query.isEmpty() -> ""
            total == 0 && counting -> "…"
            total == 0 -> "没找到"
            else -> "${active + 1}/$total"
        }
}

/** 一次阅读模式：article == null 表示还在整理 */
@Immutable
data class ReaderSession(val tabId: Long, val url: String, val incognito: Boolean, val article: ReaderArticle? = null) {
    val host: String get() = UrlUtils.host(url)
}

/**
 * v1.7 常用功能（页面内查找、阅读模式、翻译、桌面版网站、添加到主屏幕）。
 * 放在单独的类里，BrowserController 只在几个地方调用这里的钩子，方便和别的分支合并。
 */
class PageFeatures(private val activity: ComponentActivity, private val controller: BrowserController) {
    val find = FindState()
    val prefs = FeaturePrefs(activity.applicationContext)
    /** 下载管理页（自己的下载记录 + 系统 DownloadManager），第一次打开时才建 */
    val downloadCenter by lazy { DownloadCenter(activity.applicationContext, controller.downloads) }

    /** BrowserController 发起 http(s) 下载后调用；隐身标签的下载不留网址记录 */
    fun onDownloadEnqueued(dmId: Long, url: String, title: String, mime: String?, userAgent: String?, incognito: Boolean) {
        if (incognito) return
        activity.lifecycleScope.launch { runCatching { downloadCenter.record(dmId, url, title, mime, userAgent) } }
    }

    /** 正在显示的阅读模式（同一时间只有当前标签的一个） */
    var reader by mutableStateOf<ReaderSession?>(null)
        private set

    /** 「翻译网页」确认弹窗（null = 没弹） */
    var translateAsk by mutableStateOf<TranslateAsk?>(null)
        private set

    @Volatile private var checkJs: String? = null
    @Volatile private var extractJs: String? = null

    // ---------------- 钩子（BrowserController 调用） ----------------

    /** 标签页刚拿到一个（新建或重建的）WebView */
    fun onWebViewCreated(tab: TabState, v: WebView) {
        if (tab.desktop) applyDesktop(tab, v)
        v.setFindListener { activeOrdinal, matches, done ->
            if (find.open && find.tabId == tab.id && tab.webView === v) {
                find.active = activeOrdinal.coerceAtLeast(0)
                find.total = matches
                find.counting = !done
            }
        }
    }

    fun onPageStarted(tab: TabState) {
        tab.readerable = false
        // 底下的网页跳走了：阅读模式里的文章已经不是这一页了
        if (reader?.tabId == tab.id) reader = null
        if (find.open && find.tabId == tab.id) { find.total = 0; find.active = 0 }
    }

    fun onPageFinished(tab: TabState, view: WebView) {
        scheduleReaderCheck(tab, view)
        if (tab.desktop && !WebFeatures.documentStartScript) view.evaluateJavascript(DesktopMode.viewportFallback, null)
        // 查找栏开着时换了页面：在新页面里重新找
        if (find.open && find.tabId == tab.id && find.query.isNotEmpty() && tab.webView === view) {
            find.counting = true
            view.findAllAsync(find.query)
        }
    }

    /** 切换了当前标签：查找只属于原来的标签 */
    fun onCurrentTabChanged(current: TabState) {
        if (find.open && find.tabId != current.id) closeFind()
        if (reader != null && reader?.tabId != current.id) reader = null
    }

    // ---------------- 页面内查找 ----------------

    fun openFind() {
        val t = controller.current
        if (t.isHome || t.webView == null) { controller.toast("先打开一个网页再查找吧～"); return }
        find.tabId = t.id
        find.total = 0
        find.active = 0
        find.open = true
        if (find.query.isNotEmpty()) search(find.query)
    }

    fun search(q: String) {
        find.query = q
        val v = findView() ?: return
        if (q.isEmpty()) {
            v.clearMatches()
            find.total = 0; find.active = 0; find.counting = false
            return
        }
        find.counting = true
        v.findAllAsync(q)
    }

    fun findNext(forward: Boolean) {
        val v = findView() ?: return
        if (find.query.isEmpty()) return
        if (find.total == 0 && !find.counting) { v.findAllAsync(find.query); return }
        v.findNext(forward)
    }

    fun closeFind() {
        findView()?.clearMatches()
        find.open = false
        find.counting = false
        find.total = 0
        find.active = 0
    }

    private fun findView(): WebView? = controller.tabs.firstOrNull { it.id == find.tabId }?.webView

    // ---------------- 桌面版网站（每个标签单独） ----------------

    /** 切到桌面版之前 WebView 原来的设置，切回手机版时还原 */
    private class MobileSettings(val ua: String, val metadata: UserAgentMetadata?)
    private val mobileSettings = WeakHashMap<WebView, MobileSettings>()
    private val viewportScripts = WeakHashMap<WebView, ScriptHandler>()

    fun toggleDesktop() {
        val t = controller.current
        if (t.isHome) { controller.toast("先打开一个网页再切换哦～"); return }
        t.desktop = !t.desktop
        t.webView?.let { applyDesktop(t, it) }
        controller.reloadForFeature(t)
        controller.toast(if (t.desktop) "已切换到桌面版网站 🖥️" else "已切回手机版网站 📱")
    }

    private fun applyDesktop(tab: TabState, v: WebView) {
        val s = v.settings
        if (tab.desktop) {
            if (!mobileSettings.containsKey(v)) {
                val md = if (WebFeatures.uaMetadata) runCatching { WebSettingsCompat.getUserAgentMetadata(s) }.getOrNull() else null
                mobileSettings[v] = MobileSettings(s.userAgentString, md)
            }
            val def = runCatching { WebSettings.getDefaultUserAgent(v.context) }.getOrNull() ?: s.userAgentString
            s.userAgentString = DesktopMode.userAgent(def)
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            mobileSettings[v]?.metadata?.let { md ->
                runCatching {
                    WebSettingsCompat.setUserAgentMetadata(
                        s,
                        UserAgentMetadata.Builder(md).setMobile(false).setPlatform("Linux").setPlatformVersion("")
                            .setModel("").setArchitecture("x86").setBitness(64).build(),
                    )
                }
            }
            if (WebFeatures.documentStartScript && !viewportScripts.containsKey(v)) {
                runCatching { WebViewCompat.addDocumentStartJavaScript(v, DesktopMode.viewportScript, setOf("*")) }
                    .getOrNull()?.let { viewportScripts[v] = it }
            }
        } else {
            val orig = mobileSettings.remove(v) ?: return
            s.userAgentString = orig.ua
            orig.metadata?.let { md -> runCatching { WebSettingsCompat.setUserAgentMetadata(s, md) } }
            viewportScripts.remove(v)?.let { runCatching { it.remove() } }
        }
    }

    // ---------------- 阅读模式 ----------------

    private fun asset(name: String): String = activity.assets.open(name).bufferedReader().use { it.readText() }

    /** 页面加载完稍等一下，再在网页里跑一个很小的检测脚本（只读 DOM），决定地址栏要不要显示「阅读模式」按钮 */
    private fun scheduleReaderCheck(tab: TabState, view: WebView) {
        val url = view.url ?: return
        if (!UrlUtils.isWebUrl(url)) return
        view.postDelayed({
            if (tab.webView !== view || view.url != url || tab.isHome) return@postDelayed
            val js = checkJs
            if (js != null) runCheck(tab, view, url, js)
            else activity.lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { runCatching { asset(CHECK_ASSET) }.getOrNull() } ?: return@launch
                checkJs = loaded
                if (tab.webView === view && view.url == url) runCheck(tab, view, url, loaded)
            }
        }, READER_CHECK_DELAY_MS)
    }

    private fun runCheck(tab: TabState, view: WebView, url: String, js: String) {
        runCatching {
            view.evaluateJavascript(js) { r -> if (tab.webView === view && view.url == url) tab.readerable = r == "true" }
        }
    }

    /** 打开阅读模式：在网页里跑 Readability（打包在 assets/reader/），把正文交给单独的阅读 WebView 显示 */
    fun openReader() {
        val t = controller.current
        val v = t.webView
        if (t.isHome || v == null || !UrlUtils.isWebUrl(t.url)) { controller.toast("先打开一篇文章再用阅读模式吧～"); return }
        if (find.open) closeFind()
        prefs.ensureLoaded()
        val session = ReaderSession(t.id, t.url, t.incognito)
        reader = session
        activity.lifecycleScope.launch {
            val js = extractJs ?: withContext(Dispatchers.IO) { runCatching { asset(EXTRACT_ASSET) }.getOrNull() }?.also { extractJs = it }
            if (js == null || reader !== session || t.webView !== v) { if (reader === session) reader = null; return@launch }
            runCatching {
                v.evaluateJavascript(js) { raw ->
                    activity.lifecycleScope.launch {
                        val art = withContext(Dispatchers.Default) { ReaderHtml.parseResult(raw) }
                        if (reader !== session) return@launch
                        if (art == null) {
                            reader = null
                            controller.toast("这个页面好像没有可以阅读的正文呢 🥺")
                        } else reader = session.copy(article = art)
                    }
                }
            }.onFailure { if (reader === session) reader = null }
        }
    }

    fun closeReader() { reader = null }

    /** 阅读模式里点了链接：退出阅读，在原来的标签里打开 */
    fun openFromReader(url: String) {
        reader = null
        controller.open(url)
    }

    fun setReaderStyle(s: ReaderStyle) = prefs.updateReaderStyle(s)

    /**
     * 显示文章用的 WebView：不开 JS、不开存储、不能弹窗/定位/文件，第三方 Cookie 关掉；
     * 隐身标签用同一个隐身 profile。子资源只放行 https/data 图片（广告拦截照常）；点链接交回 App。
     */
    fun createReaderView(session: ReaderSession): WebView {
        val v = controller.createBareWebView(session.incognito)
        WebHardening.lockDown(v, controller.sec)
        v.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            setSupportZoom(false)
            textZoom = prefs.readerStyle.zoom
        }
        runCatching { controller.cookieManagerForReader(session.incognito).setAcceptThirdPartyCookies(v, false) }
        v.setBackgroundColor(AColor.TRANSPARENT)
        val pageHost = Uri.parse(session.url).host?.lowercase()
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (request.isForMainFrame && request.hasGesture() && UrlUtils.isWebUrl(u)) openFromReader(u)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (scheme == "data") return null
                if (scheme != "https") return AdBlocker.emptyResponse()
                val host = u.host ?: return AdBlocker.emptyResponse()
                if (controller.adBlockEnabled && pageHost !in controller.adBlockOff &&
                    AdBlocker.shouldBlock(u.toString(), host, pageHost, request.requestHeaders["Accept"])
                ) return AdBlocker.emptyResponse()
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val ratio = pendingScroll.remove(view) ?: return
                view.postDelayed({
                    val h = (view.contentHeight * view.resources.displayMetrics.density).toInt()
                    view.scrollTo(0, (ratio * h).toInt())
                }, 60)
            }
        }
        v.setDownloadListener(null)
        return v
    }

    private val pendingScroll = WeakHashMap<WebView, Float>()

    /** 换行距/配色/字体时重新排版，尽量停在原来读到的位置 */
    fun renderReader(v: WebView, html: String, baseUrl: String, keepPosition: Boolean) {
        if (keepPosition) {
            val h = v.contentHeight * v.resources.displayMetrics.density
            if (h > 0) pendingScroll[v] = (v.scrollY / h).coerceIn(0f, 1f)
        }
        v.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    fun disposeReaderView(v: WebView) {
        pendingScroll.remove(v)
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        runCatching { v.stopLoading(); v.webViewClient = WebViewClient(); v.destroy() }
    }

    // ---------------- 翻译网页（每次都要确认，不会自动发送） ----------------

    fun askTranslate() {
        val t = controller.current
        if (t.isHome || !UrlUtils.isWebUrl(t.url)) { controller.toast("先打开一个网页再翻译吧～"); return }
        if (!Translate.isTranslatable(t.url)) { controller.toast("局域网或本机的网页没法用在线翻译哦 🥺"); return }
        translateAsk = TranslateAsk(t.id, t.url, t.incognito)
    }

    fun cancelTranslate() { translateAsk = null }

    /** 用户在弹窗里点了「发送并翻译」：在新标签（隐身标签就开隐身标签）里打开翻译服务 */
    fun confirmTranslate(provider: TranslateProvider, target: TranslateTarget) {
        val a = translateAsk ?: return
        translateAsk = null
        prefs.translateProvider = provider
        prefs.translateTarget = target
        controller.newTab(Translate.url(provider, target, a.url), incognito = a.incognito)
    }

    companion object {
        const val CHECK_ASSET = "reader/check.min.js"
        const val EXTRACT_ASSET = "reader/extract.min.js"
        const val READER_CHECK_DELAY_MS = 350L
    }
}
