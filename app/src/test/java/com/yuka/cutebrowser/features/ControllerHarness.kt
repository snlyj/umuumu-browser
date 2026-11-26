package com.yuka.cutebrowser.features

import androidx.activity.ComponentActivity
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.data.CuteRepository
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** 在 Robolectric 里建一个真的 BrowserController（WebView 是 Robolectric 的影子实现） */
class ControllerHarness {
    private val ac: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    val activity: ComponentActivity get() = ac.get()
    val controller = BrowserController(ac.get(), CuteRepository(ac.get()), {}, { false }, { _, _ -> })

    /** 让当前标签变成一个「已打开的网页」并且有 WebView */
    fun openPage(url: String = "https://example.com/article") {
        controller.open(url)
        controller.webViewFor(controller.current)
    }

    fun close() {
        controller.destroyAll()
        controller.releaseGlobals()
        runCatching { ac.pause().stop().destroy() }
    }
}
