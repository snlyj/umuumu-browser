package com.yuka.cutebrowser

import android.app.Application
import android.os.Build
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.perf.StartupMarks
import com.yuka.cutebrowser.perf.WebViewWarmup
import com.yuka.cutebrowser.perf.section

class UmuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupMarks.mark("appCreate")
        CrashLog.install(this)
        // 两件最慢、又互不依赖的事情在最早的时刻就丢到后台线程，和 Activity 创建 / 主题 / 设置读取并行：
        // 1) 过滤规则（读磁盘缓存，约 12 万条）；2) 系统 WebView 的类和原生库
        // （单元测试里的 Robolectric 也会创建 Application：别在每个测试里都起一个编译十几万条规则的线程）
        if (Build.FINGERPRINT != "robolectric") section("UmuApp.startBackground") {
            AdBlocker.preload(this)
            WebViewWarmup.start(this)
        }
    }
}
