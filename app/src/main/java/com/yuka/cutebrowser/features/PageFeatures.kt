package com.yuka.cutebrowser.features

import android.webkit.WebSettings
import android.webkit.WebView
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

/**
 * v1.7 常用功能（页面内查找、阅读模式、翻译、桌面版网站、添加到主屏幕）。
 * 放在单独的类里，BrowserController 只在几个地方调用这里的钩子，方便和别的分支合并。
 */
class PageFeatures(private val activity: ComponentActivity, private val controller: BrowserController) {
    val find = FindState()

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
        if (find.open && find.tabId == tab.id) { find.total = 0; find.active = 0 }
    }

    fun onPageFinished(tab: TabState, view: WebView) {
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
}
