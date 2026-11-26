package com.yuka.cutebrowser.smoke

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import java.io.File

/**
 * 黑盒驱动唔姆唔姆的小工具。全部通过无障碍树（Compose 的 contentDescription / Text）找控件，
 * 不依赖 App 内部类名，所以 R8 混淆后的 release 包也能跑。
 *
 * 软件模拟（TCG）的模拟器很慢，超时都放得很宽。
 */
object Ui {
    const val PKG = "com.yuka.cutebrowser"
    const val ADDRESS = "地址栏，输入网址或搜索内容"
    val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice by lazy { UiDevice.getInstance(instr) }
    private val args get() = InstrumentationRegistry.getArguments()

    /** 宿主上的测试站点；模拟器默认通过 10.0.2.2，smoke.sh 也会做 adb reverse 让 localhost 可用 */
    val host: String get() = args.getString("testHost") ?: "http://localhost:8000"
    val slow: Long get() = (args.getString("timeoutScale")?.toLongOrNull() ?: 1L)

    fun t(ms: Long) = ms * slow

    fun sh(cmd: String): String {
        val out = device.executeShellCommand(cmd)
        Log.i("UmuSmoke", "sh: $cmd -> ${out.take(300)}")
        return out
    }

    fun pid(): String = sh("pidof $PKG").trim()

    /** 冷启动：清数据、强停、拉起主界面，等地址栏出现 */
    fun coldStart(clear: Boolean = true, extra: String = "") {
        if (clear) sh("pm clear $PKG")
        sh("am force-stop $PKG")
        sh("logcat -c")
        device.pressHome()
        sh("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity $extra")
        waitDesc(ADDRESS, t(90_000))
    }

    fun openUrlIntent(url: String) {
        sh("am start -W -a android.intent.action.VIEW -d $url -n $PKG/.MainActivity")
    }

    fun find(sel: BySelector, timeout: Long = t(20_000)): UiObject2? = device.wait(Until.findObject(sel), timeout)

    fun waitDesc(desc: String, timeout: Long = t(30_000)): UiObject2 =
        find(By.desc(desc), timeout) ?: failWithDump("没找到 desc=$desc")

    fun waitText(text: String, timeout: Long = t(30_000)): UiObject2 =
        find(By.text(text), timeout) ?: failWithDump("没找到 text=$text")

    fun waitTextContains(text: String, timeout: Long = t(30_000)): UiObject2 =
        find(By.textContains(text), timeout) ?: failWithDump("没找到 textContains=$text")

    fun gone(sel: BySelector, timeout: Long = t(20_000)): Boolean = device.wait(Until.gone(sel), timeout) ?: false

    fun has(sel: BySelector): Boolean = device.hasObject(sel)

    /** 点控件；Compose 里 Text 本身不可点，往上找可点击的父节点 */
    fun click(o: UiObject2) {
        var n: UiObject2? = o
        while (n != null && !n.isClickable) n = n.parent
        (n ?: o).click()
        device.waitForIdle(t(3_000))
    }

    fun clickText(text: String, timeout: Long = t(30_000)) = click(waitText(text, timeout))
    fun clickDesc(desc: String, timeout: Long = t(30_000)) = click(waitDesc(desc, timeout))

    fun openMenu() = clickDesc("菜单")

    /** 在长列表里往下滚，直到出现 [sel] */
    fun scrollTo(sel: BySelector, maxSwipes: Int = 25): UiObject2 {
        repeat(maxSwipes) {
            find(sel, 1_500)?.let { return it }
            val w = device.displayWidth; val h = device.displayHeight
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 3, 30)
        }
        return find(sel, 3_000) ?: failWithDump("滚动后仍没找到 $sel")
    }

    fun setClipboard(text: String) {
        val ctx = instr.targetContext
        instr.runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("umu-smoke", text))
        }
    }

    /** App 进程是否还活着、有没有新的崩溃 */
    fun assertAlive(what: String) {
        SystemClock.sleep(500)
        val crash = sh("logcat -d -b crash")
        if (crash.contains(PKG) || crash.contains("FATAL EXCEPTION")) {
            saveLog(what)
            fail("$what：App 崩溃了\n" + crash.takeLast(4000))
        }
        if (pid().isEmpty()) {
            saveLog(what)
            fail("$what：App 进程不在了")
        }
    }

    fun saveLog(name: String) {
        runCatching {
            val dir = File("/sdcard/Download/umu-smoke").apply { mkdirs() }
            sh("logcat -d -f ${dir.absolutePath}/${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.log")
        }
    }

    fun screenshot(name: String) {
        runCatching {
            val dir = File("/sdcard/Download/umu-smoke").apply { mkdirs() }
            device.takeScreenshot(File(dir, "$name.png"))
        }
    }

    fun failWithDump(msg: String): Nothing {
        val name = "fail_" + SystemClock.uptimeMillis()
        screenshot(name)
        runCatching { device.dumpWindowHierarchy(File("/sdcard/Download/umu-smoke/$name.xml")) }
        saveLog(name)
        fail("$msg（截图/界面树已存到 /sdcard/Download/umu-smoke/$name.*）")
        throw IllegalStateException()
    }

    fun fling(dir: Direction, times: Int) {
        val w = device.displayWidth; val h = device.displayHeight
        repeat(times) {
            when (dir) {
                Direction.DOWN -> device.swipe(w / 2, h * 4 / 5, w / 2, h / 5, 4)   // 手指上划 = 内容往下滚
                Direction.UP -> device.swipe(w / 2, h / 4, w / 2, h * 9 / 10, 4)
                else -> {}
            }
        }
    }
}
