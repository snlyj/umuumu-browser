package com.yuka.cutebrowser.perf

import android.content.Context
import android.os.Process
import android.webkit.WebSettings
import com.yuka.cutebrowser.security.WebFeatures
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在后台线程提前把系统 WebView 的实现包（类 + 原生库）加载进来。
 * 以前这一步（约几十到一百多毫秒，取决于手机）发生在主线程第一次 `WebView(context)` 的时候，会卡住首页/第一个网页。
 *
 * 做法：`WebSettings.getDefaultUserAgent` 会触发 WebViewFactory 加载 provider 和 native 库，但不会启动浏览器进程
 * （那一步仍在第一次创建 WebView 时由主线程完成）。顺便把默认 UA 存下来，创建 WebView 时不必再算。
 * 任何异常都吞掉：没有 WebView 实现时，后面创建 WebView 的地方自己会报。
 */
object WebViewWarmup {
    private val started = AtomicBoolean(false)

    /** 系统默认 UA（后台线程算好后才有；没有就让调用方自己取） */
    @Volatile var defaultUserAgent: String? = null
        private set

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2) }
            section("WebViewWarmup") {
                runCatching { defaultUserAgent = WebSettings.getDefaultUserAgent(app) }
                // androidx.webkit 的功能探测（isFeatureSupported）第一次调用要连上 WebView 的支持库，也不便宜；
                // 这些都是线程安全的 lazy，提前算好，创建 WebView 时直接用
                runCatching {
                    WebFeatures.documentStartScript; WebFeatures.multiProfile; WebFeatures.uaMetadata
                    WebFeatures.webMessageListener; WebFeatures.safeBrowsing; WebFeatures.attribution
                }
            }
            StartupMarks.mark("webViewProviderLoaded")
        }, "umu-webview-warmup").start()
    }

    internal fun resetForTest() { started.set(false); defaultUserAgent = null }
}
