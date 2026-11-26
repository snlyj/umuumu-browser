package com.yuka.cutebrowser.browser

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yuka.cutebrowser.perf.StartupMarks
import com.yuka.cutebrowser.perf.section
import android.webkit.WebResourceResponse
import com.yuka.cutebrowser.filter.FilterEngine
import com.yuka.cutebrowser.filter.FilterRequest
import com.yuka.cutebrowser.filter.ResType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 广告/追踪拦截入口。
 * - 启动后在 IO 线程加载过滤引擎（EasyList + EasyPrivacy + AdAway hosts）：
 *   先读磁盘上编译好的缓存（快），没有就从 assets 现场编译再写缓存。
 * - 引擎就绪前用内置的小域名表兜底，所以启动不用等。
 */
object AdBlocker {
    private const val TAG = "UmuFilter"

    /** 内置的小名单（国内统计/广告为主），在大名单加载完之前也能用 */
    private val builtinHosts: Set<String> = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "adservice.google.com", "2mdn.net", "adnxs.com", "taboola.com", "outbrain.com", "criteo.com",
        "criteo.net", "scorecardresearch.com", "quantserve.com", "hotjar.com", "amazon-adsystem.com",
        "moatads.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "serving-sys.com",
        "yieldmo.com", "casalemedia.com", "advertising.com", "adform.net", "smartadserver.com",
        "media.net", "mathtag.com", "bluekai.com", "krxd.net", "demdex.net", "everesttech.net",
        "ads.twitter.com", "ads.linkedin.com", "analytics.tiktok.com", "ads.yahoo.com",
        "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com", "eclick.baidu.com", "cbjs.baidu.com",
        "cnzz.com", "umeng.com", "51.la", "mmstat.com", "tanx.com", "growingio.com",
        "ad.toutiao.com", "pangolin-sdk-toutiao.com", "gdt.qq.com", "adsmind.gdtimg.com",
        "union.baidu.com", "ads.csdn.net",
    )

    /** 完整的规则表有十万条以上；低于这个数说明编译时有名单没读进来 */
    private const val MIN_COMPLETE_RULES = 50_000

    private val ASSETS = listOf("filters/easylist.txt", "filters/easyprivacy.txt")

    @Volatile var engine: FilterEngine? = null
        private set

    @Volatile var loadedCount: Int = builtinHosts.size
        private set

    /** 最近一次加载用了多少毫秒、是不是读的缓存（设置页显示，也方便看性能） */
    @Volatile var loadInfo: String = "加载中…"
        private set

    @Volatile private var started = false

    /**
     * 在后台线程开始加载过滤引擎（只会启动一次，可以重复调用）。放在 Application.onCreate 里最早调用：
     * 这样规则的读取和 Activity 创建 / 主题 / 设置读取并行，第一个网页开始加载时引擎大概率已经就绪。
     *
     * 线程优先级：不用 THREAD_PRIORITY_BACKGROUND（≥10 会被系统放进后台 cgroup，只能跑在小核上、
     * 前台忙的时候只给很少的 CPU，加载反而更慢），用略低于主线程的 +2，仍然可以用大核。
     */
    fun preload(context: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = context.applicationContext
        Thread({
            runCatching { Process.setThreadPriority(PRELOAD_PRIORITY) }
            runCatching { load(app, cacheKey(app)) }.onFailure { Log.w(TAG, "preload failed", it) }
        }, "umu-filter").start()
    }

    private const val PRELOAD_PRIORITY = 2

    /** 编译缓存的文件名里带版本 + 安装时间：App 更新（规则也可能变了）后会重新编译一次 */
    fun cacheKey(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "$vc-${pi.lastUpdateTime}"
    }.getOrDefault("0")

    /** 必须在后台线程调用。加了锁：Activity 重建时会再起一个加载线程，别让两个线程同时编译一遍 */
    @Synchronized
    fun load(context: Context, cacheKey: String) = section("AdBlocker.load") {
        if (engine != null) return@section
        val t0 = SystemClock.elapsedRealtime()
        val dir = File(context.filesDir, "filters").apply { mkdirs() }
        val cache = File(dir, "compiled-$cacheKey.bin")
        var e: FilterEngine? = null
        var fromCache = false
        if (cache.exists()) {
            // 网络规则读完就先发布（能拦请求了），元素隐藏规则随后再就位
            e = runCatching {
                DataInputStream(BufferedInputStream(cache.inputStream(), 1 shl 16)).use { ins ->
                    FilterEngine.readFrom(ins) { early ->
                        loadedCount = early.networkRuleCount
                        engine = early
                        StartupMarks.mark("filterNetworkReady")
                    }
                }
            }.onFailure { Log.w(TAG, "cache read failed", it) }.getOrNull()
            fromCache = e != null
        }
        if (e == null) {
            e = FilterEngine()
            for (name in ASSETS) runCatching { context.assets.open(name).bufferedReader().use { e.addList(it) } }
            val hosts = ArrayList<String>(8192).apply { addAll(builtinHosts) }
            runCatching {
                context.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                    lines.forEach { val l = it.trim(); if (l.isNotEmpty() && l[0] != '#') hosts.add(l) }
                }
            }
            e.addHosts(hosts)
            e.finish()
            // 旧版本缓存删掉，写新的（先写临时文件再改名，防止写一半）。
            // 规则明显不全（比如 assets 读失败）时不写缓存：否则这个残缺的引擎会被缓存到下次更新 App 为止
            val complete = e.networkRuleCount > MIN_COMPLETE_RULES
            dir.listFiles()?.forEach { if (it.name != cache.name || !complete) it.delete() }
            if (complete) runCatching {
                val tmp = File(dir, cache.name + ".tmp")
                DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { e.writeTo(it) }
                tmp.renameTo(cache)
            }.onFailure { Log.w(TAG, "cache write failed", it) }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        loadInfo = "${if (fromCache) "读缓存" else "编译"} ${ms}ms"
        Log.i(TAG, "filter engine ready: ${e.networkRuleCount} network / ${e.cosmeticRuleCount} cosmetic rules, $loadInfo")
        loadedCount = e.networkRuleCount + e.cosmeticRuleCount
        engine = e
        StartupMarks.mark("filterReady")
    }

    /** 只给单元测试用：丢掉已加载的引擎，强制重新加载 */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() { engine = null; started = false }

    /** 子资源请求要不要拦（任意线程）。url 原样传入即可 */
    fun shouldBlock(url: String, host: String, pageHost: String?, accept: String?): Boolean {
        val e = engine ?: return isBlockedHost(host)
        // shouldInterceptRequest 在 WebView 的后台线程里调用：这里抛异常会让整个 App 崩掉，所以任何意外都当"不拦"
        return try {
            val lower = url.lowercase()
            e.shouldBlock(FilterRequest(lower, host.lowercase(), pageHost, ResType.infer(lower, accept)))
        } catch (t: Throwable) {
            false
        }
    }

    /** 这个页面被 @@…$document 例外放行了吗 */
    fun pageAllowlisted(url: String, host: String): Boolean =
        runCatching { engine?.isPageAllowlisted(url.lowercase(), host.lowercase()) }.getOrNull() ?: false

    fun cosmeticCss(url: String, host: String): String? = runCatching { engine?.cosmeticCss(url.lowercase(), host.lowercase()) }.getOrNull()

    fun isBlockedHost(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in builtinHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
