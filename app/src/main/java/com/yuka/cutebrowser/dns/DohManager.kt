package com.yuka.cutebrowser.dns

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** 加密 DNS 的设置（单独一个 DataStore 文件，不动主设置） */
@Immutable
data class DohPrefs(
    val enabled: Boolean = false,
    val providerId: String = DohProvider.CLOUDFLARE.id,
    val customUrl: String = "",
    val strict: Boolean = false,
) {
    fun provider(): DohProvider? =
        if (providerId == DohProvider.CUSTOM_ID) DohProvider.custom(customUrl) else DohProvider.byId(providerId)
}

@Immutable
data class DohStatus(
    val active: Boolean = false,
    val port: Int = 0,
    val providerLabel: String = "",
    val error: String? = null,
)

@Immutable
data class DohTestResult(val ok: Boolean, val coldMs: Long, val warmMs: Long, val detail: String)

private val Context.dohStore: DataStore<Preferences> by preferencesDataStore(name = "doh_prefs")

/**
 * 内置加密 DNS 的总开关：管理本地代理 + DoH 解析器 + WebView 的代理设置。
 * 默认关闭；关闭时什么都不加载（不建线程、不碰 OkHttp），对启动速度零影响。
 */
object DohManager {
    private object Keys {
        val ENABLED = booleanPreferencesKey("doh_enabled")
        val PROVIDER = stringPreferencesKey("doh_provider")
        val CUSTOM = stringPreferencesKey("doh_custom_url")
        val STRICT = booleanPreferencesKey("doh_strict")
    }

    /** 当前 WebView 是否支持代理覆盖（需要较新的 Android System WebView） */
    val supported: Boolean get() = supportedOverride ?: detected
    private val detected: Boolean by lazy { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false) }
    /** 只给截图测试用（Robolectric 里没有 WebView） */
    @androidx.annotation.VisibleForTesting
    var supportedOverride: Boolean? = null

    private val _status = MutableStateFlow(DohStatus())
    val status: StateFlow<DohStatus> = _status

    /**
     * 明文 http 请求经过本地代理时，让它把 Accept-Language 换成这个网站该用的（host → 请求头，null = 不动）。
     * 只对 http 有效：https 走 CONNECT 隧道，代理看不到也改不了请求头。
     */
    @Volatile var acceptLanguageFor: ((String) -> String?)? = null

    /** 退回系统 DNS 时的提示（MainActivity 接到 toast 上） */
    @Volatile var notice: ((String) -> Unit)? = null
    @Volatile private var lastNoticeAt = 0L

    @Volatile var resolver: DohResolver? = null
        private set
    private var proxy: LocalProxy? = null
    private var overrideSet = false
    private val initial = CompletableDeferred<Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val direct = Executor { it.run() }

    fun prefs(ctx: Context): Flow<DohPrefs> = ctx.applicationContext.dohStore.data.map { p ->
        DohPrefs(
            enabled = p[Keys.ENABLED] ?: false,
            providerId = p[Keys.PROVIDER] ?: DohProvider.CLOUDFLARE.id,
            customUrl = p[Keys.CUSTOM] ?: "",
            strict = p[Keys.STRICT] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun update(ctx: Context, f: (DohPrefs) -> DohPrefs) {
        ctx.applicationContext.dohStore.edit { p ->
            val cur = DohPrefs(
                p[Keys.ENABLED] ?: false, p[Keys.PROVIDER] ?: DohProvider.CLOUDFLARE.id, p[Keys.CUSTOM] ?: "", p[Keys.STRICT] ?: false,
            )
            val n = f(cur)
            p[Keys.ENABLED] = n.enabled; p[Keys.PROVIDER] = n.providerId; p[Keys.CUSTOM] = n.customUrl; p[Keys.STRICT] = n.strict
        }
    }

    /** 在 Activity 的 lifecycleScope（主线程）里收集设置并应用。第一次应用完之后 [awaitInitial] 才返回 */
    suspend fun run(ctx: Context) {
        prefs(ctx).collect { p ->
            runCatching { apply(p) }.onFailure { e -> _status.value = DohStatus(error = "启动失败：${e.message}") }
            initial.complete(Unit)
        }
    }

    /**
     * 第一个网页加载之前调用：开着加密 DNS 时，要等代理设置生效，避免第一批请求走系统 DNS。
     * 关着时只是等一次设置读取（几毫秒，和其它设置并行）。
     */
    suspend fun awaitInitial(timeoutMs: Long = 1500) { withTimeoutOrNull(timeoutMs) { initial.await() } }

    private suspend fun apply(p: DohPrefs) {
        if (!p.enabled) { disable(); _status.value = DohStatus(); return }
        if (!supported) { disable(); _status.value = DohStatus(error = "你的 WebView 版本太旧，不支持"); return }
        val provider = p.provider() ?: run {
            disable(); _status.value = DohStatus(error = "自定义地址要以 https:// 开头"); return
        }
        val cur = resolver
        val r = if (cur != null && cur.provider.url == provider.url) cur.also { it.strict = p.strict } else {
            withContext(Dispatchers.IO) { DohResolver(provider, p.strict) }.also { nr -> // OkHttp 初始化不放主线程
                nr.listener = object : DohResolver.Listener {
                    override fun onFallback(host: String, error: Throwable) = fallbackNotice()
                }
                resolver = nr
                cur?.let { old -> main.postDelayed({ old.close() }, 10_000) } // 正在进行的查询先让它们做完
            }
        }
        val px = proxy ?: LocalProxy(r).also { proxy = it }
        px.resolver = r
        val port = withContext(Dispatchers.IO) { px.start() }
        if (!overrideSet || _status.value.port != port) {
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            withTimeoutOrNull(2000) {
                suspendCancellableCoroutine { c -> ProxyController.getInstance().setProxyOverride(cfg, direct) { if (c.isActive) c.resume(Unit) } }
            }
            overrideSet = true
        }
        r.prewarm(emptyList()) // 提前建好到 DoH 服务器的连接
        _status.value = DohStatus(active = true, port = port, providerLabel = provider.label)
    }

    private suspend fun disable() {
        if (overrideSet) {
            withTimeoutOrNull(2000) {
                suspendCancellableCoroutine { c -> ProxyController.getInstance().clearProxyOverride(direct) { if (c.isActive) c.resume(Unit) } }
            }
            overrideSet = false
        }
        proxy?.stop(); proxy = null
        resolver?.let { old -> main.postDelayed({ old.close() }, 10_000) }
        resolver = null
    }

    private fun fallbackNotice() {
        val now = System.currentTimeMillis()
        if (now - lastNoticeAt < 60_000) return
        lastNoticeAt = now
        main.post { notice?.invoke("加密 DNS 暂时连不上，先用系统 DNS 啦 🐰") }
    }

    /** BrowserController.prewarm 用：开着就走 DoH 预热（顺便填缓存），关着就用系统 DNS。在 IO 线程调用 */
    fun prewarm(hosts: List<String>) {
        val r = resolver
        if (r != null) r.prewarm(hosts) else hosts.forEach { runCatching { InetAddress.getAllByName(it) } }
    }

    fun statsText(): String? {
        val r = resolver ?: return null
        val px = proxy
        val n = r.lookups.get()
        val hit = if (n > 0) r.cacheHits.get() * 100 / (n * 2) else 0 // 每次查询 = A + AAAA 两个缓存键
        val q = r.dohQueries.get()
        val avg = if (q > 0) r.dohNanos.get() / q / 1_000_000 else 0
        return "已解析 $n 次 · 缓存命中 $hit% · DoH 平均 $avg ms · 隧道 ${px?.tunnels?.get() ?: 0}" +
            (if (r.fallbacks.get() > 0) " · 退回系统 DNS ${r.fallbacks.get()} 次" else "") +
            (if (r.failures.get() > 0) " · 严格模式拦下 ${r.failures.get()} 次" else "")
    }

    /** 设置页的"测一测"：新建一个连接，测首次（含 TLS 握手）和复用连接后的查询耗时 */
    suspend fun test(provider: DohProvider): DohTestResult = withContext(Dispatchers.IO) {
        val t = OkHttpDohTransport(provider)
        try {
            val t0 = System.nanoTime()
            val r1 = DnsMessage.parse(t.exchange(DnsMessage.buildQuery("www.bing.com", DnsMessage.TYPE_A)))
            val cold = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val r2 = DnsMessage.parse(t.exchange(DnsMessage.buildQuery("www.baidu.com", DnsMessage.TYPE_A)))
            val warm = (System.nanoTime() - t1) / 1_000_000
            val ip = (r1.addresses(DnsMessage.TYPE_A) + r2.addresses(DnsMessage.TYPE_A)).firstOrNull()?.hostAddress
            DohTestResult(ip != null, cold, warm, if (ip != null) "www.bing.com → $ip" else "没有查到地址（rcode ${r1.rcode}）")
        } catch (e: Exception) {
            DohTestResult(false, -1, -1, e.message ?: e.javaClass.simpleName)
        } finally {
            t.close()
        }
    }
}
