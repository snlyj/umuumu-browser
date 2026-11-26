package com.yuka.cutebrowser.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** 被测 App 的包名（Gradle 插件通过 instrumentation 参数传进来） */
val targetAppId: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.yuka.cutebrowser"

private const val WAIT_MS = 8_000L

// 下面的选择器用的都是 App 里 Compose 语义的 contentDescription（见 ui/BrowserScreen.kt），不依赖文字内容

/** 冷启动到首页出现（底栏的"菜单"按钮可见 = 首帧内容已画出来） */
fun MacrobenchmarkScope.startToHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.desc("菜单")), WAIT_MS)
}

/** 地址栏：点一下（获得焦点、预热 DNS）→ 输入网址 → 回车 → 等页面起来。网页加载会走 WebViewClient 回调和广告拦截匹配。 */
fun MacrobenchmarkScope.openUrlFromAddressBar(url: String) {
    val bar = device.wait(Until.findObject(By.descContains("地址栏")), WAIT_MS) ?: return
    bar.click()
    device.waitForIdle()
    bar.text = url
    device.waitForIdle()
    device.pressEnter()
    // 等页面至少画出东西（不要求加载成功；没网时是 App 自己的错误页，同样走到相关代码）
    device.wait(Until.hasObject(By.desc("刷新").pkg(packageName)), WAIT_MS)
    device.waitForIdle(3_000)
}

/** 标签页：打开列表（会触发缩略图截取）→ 新建一个 → 再打开列表切回第一个 → 返回 */
fun MacrobenchmarkScope.tabSwitchJourney() {
    device.findObject(By.descStartsWith("标签页"))?.click()
    device.wait(Until.hasObject(By.desc("新建标签页")), WAIT_MS)
    device.findObject(By.desc("新建标签页"))?.click()   // 新建并回到首页
    device.waitForIdle()
    device.findObject(By.descStartsWith("标签页"))?.click()
    device.wait(Until.hasObject(By.desc("新建标签页")), WAIT_MS)
    // 列表里第一行（第一个标签）
    device.findObjects(By.clickable(true)).firstOrNull { it.visibleBounds.top > device.displayHeight / 3 }?.click()
    device.waitForIdle()
}

/** 菜单面板：打开 → 关掉（Compose 底部面板的首次组合是热点） */
fun MacrobenchmarkScope.menuJourney() {
    device.findObject(By.desc("菜单"))?.click()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}

/** 主页滚动（每日一句、贴纸网格） */
fun MacrobenchmarkScope.homeScrollJourney() {
    val w = device.displayWidth
    val h = device.displayHeight
    repeat(2) {
        device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
        device.waitForIdle()
        device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
        device.waitForIdle()
    }
}
