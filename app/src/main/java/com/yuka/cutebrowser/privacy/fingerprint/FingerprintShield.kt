package com.yuka.cutebrowser.privacy.fingerprint

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import com.yuka.cutebrowser.filter.Domains
import com.yuka.cutebrowser.privacy.locale.SpoofConfig
import com.yuka.cutebrowser.privacy.locale.TzLang
import com.yuka.cutebrowser.security.WebFeatures
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * 防指纹（Brave 式"每个网站随机但稳定"的小噪声 + 常见值统一）。
 *
 * 用 WebViewCompat.addDocumentStartJavaScript 在每个页面、每个 frame 的最开始注入 assets/fingerprint.min.js，
 * 配置（会话密钥、级别、例外网站）拼进脚本里。种子 = hash(会话密钥 + 顶层页面的 eTLD+1)：
 *  - 同一个网站在这次打开 App 期间看到的值不变（不会把网站搞坏）；
 *  - 不同网站看到不同的值，没法拿来跨站关联；
 *  - 重启 App 换新密钥；隐身会话有自己的密钥，结束隐身就丢掉。
 *
 * v1.5：同一份脚本还负责"伪造时区和语言"（[SpoofConfig]，见 privacy/locale/TzLang.kt）。它不依赖防指纹开关：
 * 防指纹关着、时区/语言伪造开着时，脚本照样注入（配置里 l=0，只有时区/语言那部分起作用）。
 *
 * 只在主线程调用。
 */
class FingerprintShield(private val appContext: Context) {
    // 这些会在加密 DNS 的工作线程里被读到（acceptLanguageFor → isExempt / spoof），主线程写入，所以要可见
    @Volatile var enabled = true
        private set
    @Volatile var strict = false
        private set
    /** 用户关掉防指纹的网站（eTLD+1） */
    @Volatile var exceptions: Set<String> = emptySet()
        private set
    /** 伪造时区/语言的设置（没开就是 [SpoofConfig] 默认值） */
    @Volatile var spoof: SpoofConfig = SpoofConfig()
        private set

    /** 要不要往网页里注入脚本：防指纹开着，或时区/语言伪造有内容 */
    val active: Boolean get() = enabled || spoof.hasEffect

    private val sessionKey = FpSeed.newKey()
    private var incognitoKey: String? = null

    private class Entry(val incognito: Boolean, var handler: ScriptHandler?)
    private val views = WeakHashMap<WebView, Entry>()

    private val template: String by lazy {
        runCatching { appContext.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    /** 后台线程提前把脚本读进内存（9 KB） */
    fun preload() { template.length }

    fun isExempt(host: String?): Boolean = FpSeed.site(host) in exceptions

    fun configJson(incognito: Boolean): String {
        val key = if (incognito) (incognitoKey ?: FpSeed.newKey().also { incognitoKey = it }) else sessionKey
        return JSONObject()
            .put("k", key)
            .put("l", if (!enabled) 0 else if (strict) 2 else 1)
            .put("x", JSONArray(exceptions.sorted()))
            .put("p", JSONArray(Domains.SECOND_LEVEL.sorted()))
            .apply {
                if (spoof.on) {
                    put("z", spoof.tz)
                    put("g", spoof.lang)
                    if (spoof.sites.isNotEmpty()) put("o", JSONObject().apply {
                        spoof.sites.toSortedMap().forEach { (site, v) ->
                            put(site, JSONObject().apply { v.tz?.let { put("z", it) }; v.lang?.let { put("g", it) } })
                        }
                    })
                    if (spoof.tz == TzLang.RANDOM || spoof.sites.values.any { it.tz == TzLang.RANDOM }) put("rz", JSONArray(TzLang.RANDOM_POOL))
                }
            }
            .toString()
    }

    /** 完整脚本；防指纹和时区/语言伪造都没开时返回 null */
    fun script(incognito: Boolean): String? {
        if (!active) return null
        val t = template
        val i = t.lastIndexOf(PLACEHOLDER)
        if (i < 0) return null
        return t.substring(0, i) + configJson(incognito) + t.substring(i + PLACEHOLDER.length)
    }

    /** 新建 WebView 时调用（在加载任何网页之前） */
    fun attach(wv: WebView, incognito: Boolean) {
        val e = Entry(incognito, null)
        views[wv] = e
        install(wv, e)
    }

    private fun install(wv: WebView, e: Entry) {
        e.handler?.let { h -> runCatching { h.remove() } }
        e.handler = null
        if (!WebFeatures.documentStartScript) return // 旧 WebView：在 onPageStarted 里用 [fallbackScript] 补注入
        val s = script(e.incognito) ?: return
        e.handler = runCatching { WebViewCompat.addDocumentStartJavaScript(wv, s, setOf("*")) }.getOrNull()
    }

    /**
     * 设置变化：给还活着的 WebView 换上新脚本（不用重建 WebView，下一次加载页面生效）。
     * 返回是否真的变了（调用方据此决定要不要刷新当前页）。
     */
    fun update(enabled: Boolean, strict: Boolean, exceptions: Set<String>, live: Collection<WebView>, spoof: SpoofConfig = this.spoof): Boolean {
        if (enabled == this.enabled && strict == this.strict && exceptions == this.exceptions && spoof == this.spoof) return false
        this.enabled = enabled
        this.strict = strict
        this.exceptions = exceptions
        this.spoof = spoof
        val alive = live.toHashSet()
        views.keys.retainAll(alive) // 已经销毁的 WebView 不再碰
        views.forEach { (wv, e) -> install(wv, e) }
        return true
    }

    /** 老 WebView 不支持文档开始脚本时，在 onPageStarted 用 evaluateJavascript 补（只有主框架，可能比网页脚本晚） */
    fun fallbackScript(incognito: Boolean): String? = if (WebFeatures.documentStartScript) null else script(incognito)

    /** 隐身会话结束：丢掉隐身密钥，下次隐身是全新的指纹 */
    fun endIncognito() { incognitoKey = null }

    companion object {
        const val ASSET = "fingerprint.min.js"
        const val PLACEHOLDER = "__UMU_FP_CONFIG__"
    }
}
