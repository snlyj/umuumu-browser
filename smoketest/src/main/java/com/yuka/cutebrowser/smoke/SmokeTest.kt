package com.yuka.cutebrowser.smoke

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import com.yuka.cutebrowser.smoke.Ui.PKG
import com.yuka.cutebrowser.smoke.Ui.assertAlive
import com.yuka.cutebrowser.smoke.Ui.click
import com.yuka.cutebrowser.smoke.Ui.clickDesc
import com.yuka.cutebrowser.smoke.Ui.clickText
import com.yuka.cutebrowser.smoke.Ui.coldStart
import com.yuka.cutebrowser.smoke.Ui.device
import com.yuka.cutebrowser.smoke.Ui.find
import com.yuka.cutebrowser.smoke.Ui.has
import com.yuka.cutebrowser.smoke.Ui.host
import com.yuka.cutebrowser.smoke.Ui.openMenu
import com.yuka.cutebrowser.smoke.Ui.screenshot
import com.yuka.cutebrowser.smoke.Ui.scrollTo
import com.yuka.cutebrowser.smoke.Ui.sh
import com.yuka.cutebrowser.smoke.Ui.t
import com.yuka.cutebrowser.smoke.Ui.waitDesc
import com.yuka.cutebrowser.smoke.Ui.waitText
import com.yuka.cutebrowser.smoke.Ui.waitTextContains
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * 唔姆唔姆冒烟测试：每个用例都从清空数据的冷启动开始。
 * 用户在真机上报过的 bug 都在这里有对应用例（隐身开关、测速闪退、设置页被滑走、粘贴闪退）。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val name = TestName()

    @After fun after() {
        screenshot("end_" + name.methodName)
    }

    private fun tabCount(): Int {
        // 底栏「标签页」按钮里显示的数字
        val tabs = device.findObjects(By.text(java.util.regex.Pattern.compile("^\\d+$")))
        return tabs.lastOrNull()?.text?.toIntOrNull() ?: -1
    }

    private fun address() = waitDesc(Ui.ADDRESS)

    private fun typeAndGo(text: String) {
        val a = address()
        a.click()
        a.text = text
        device.pressEnter()
    }

    /** 等 WebView 里的网页文字出现（WebView 的内容在无障碍树里） */
    private fun waitWeb(text: String, timeout: Long = t(90_000)) = waitTextContains(text, timeout)

    private fun openSecuritySheet() {
        openMenu()
        clickText("设置")
        waitText("设置")
        click(scrollTo(By.textContains("安全与隐私（")))
        waitText("安全与隐私")
    }

    @Test fun t01_coldStartHome() {
        coldStart()
        waitTextContains("唔姆唔姆")
        waitDesc("菜单")
        assertEquals(1, tabCount())
        assertAlive("冷启动")
    }

    @Test fun t02_pasteUrlWithNewlineAndChinese() {
        coldStart()
        // 用户的原话：网址后面跟着换行和「登录…」
        val messy = "$host/page2.html\n登录 唔姆测试"
        // 1) 输入法提交带换行的文本（相当于输入法粘贴）——v1.6.3 在这里闪退
        val a = address()
        a.click()
        a.text = messy
        SystemClock.sleep(1500)
        assertAlive("输入带换行的文本")
        // 2) 硬件键盘 Ctrl+V 粘贴
        Ui.setClipboard(messy)
        a.click()
        device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        SystemClock.sleep(1500)
        assertAlive("Ctrl+V 粘贴带换行的文本")
        val now = address().text ?: ""
        assertFalse("地址栏里不该有换行: $now", now.contains('\n'))
        device.pressEnter()
        // 应该直接打开网址，而不是拿整段去搜索
        waitWeb("Page 2")
        assertTrue("应该打开网址而不是搜索", !has(By.textContains("google.com/search")))
        assertAlive("打开粘贴的网址")
    }

    @Test fun t03_clipboardBanner() {
        coldStart()
        Ui.setClipboard("看看这个 $host/page2.html 好可爱")
        // 剪贴板变化监听 + 回到前台时刷新
        device.pressHome()
        sh("am start -n $PKG/.MainActivity")
        clickText("打开复制的链接", t(60_000))
        waitWeb("Page 2")
        assertAlive("剪贴板横幅")
    }

    @Test fun t04_incognitoToggleNoPileUp() {
        coldStart()
        repeat(3) { round ->
            openMenu()
            clickText("隐身模式")
            waitTextContains("悄悄", t(30_000))
            assertEquals("第${round + 1}次进入隐身：只该有 1 普通 + 1 隐身", 2, tabCount())
            openMenu()
            clickText("结束隐身")
            Ui.gone(By.textContains("悄悄地搜索"), t(30_000))
            assertEquals("第${round + 1}次结束隐身：只剩 1 个普通标签", 1, tabCount())
        }
        assertAlive("隐身开关")
    }

    @Test fun t05_securitySheetSurvivesFlings() {
        coldStart()
        openSecuritySheet()
        // 用力往下甩到底、再往上甩回顶部、再继续甩：设置页不能自己关掉（v1.6.1 的 bug）
        repeat(3) {
            Ui.fling(Direction.DOWN, 6)
            Ui.fling(Direction.UP, 6)
        }
        SystemClock.sleep(1500)
        assertTrue("安全页被滑走了", has(By.text("安全与隐私")))
        // 只有 × 能关
        clickDesc("关闭")
        assertTrue(Ui.gone(By.text("安全与隐私"), t(15_000)))
        assertAlive("安全页甩动")
    }

    @Test fun t06_dohEnableAndSpeedTest() {
        coldStart()
        openSecuritySheet()
        val unsupported = find(By.textContains("App 内加密 DNS：不支持"), 3_000)
        if (unsupported != null) {
            // 旧 WebView：只能确认不会崩
            assertAlive("DoH 不支持提示")
            return
        }
        click(scrollTo(By.textContains("App 内加密 DNS（DoH）")))
        SystemClock.sleep(2000)
        click(scrollTo(By.text("测一测 ⏱️")))
        SystemClock.sleep(t(12_000))
        assertAlive("DoH 单个测速")
        click(scrollTo(By.text("全部测一测")))
        // 每个服务商最多 10 秒
        SystemClock.sleep(t(15_000))
        assertAlive("DoH 全部测速")
        // 测速结果（成功或出错都行，关键是不崩）
        assertTrue(has(By.textContains("ms")) || has(By.textContains("出错")) || has(By.textContains("测速中")))
        // 开着 DoH 也能打开本地网页
        clickDesc("关闭")
        typeAndGo("$host/")
        waitWeb("Umu test")
        assertAlive("DoH 下打开网页")
    }

    @Test fun t07_localPageViaHttpInterstitial() {
        coldStart()
        // 10.0.2.2 不是本机地址：会先试 https，失败后出提示页
        typeAndGo("http://10.0.2.2:8000/")
        clickText("仍然用 http 打开这个网站", t(90_000))
        waitWeb("Umu test")
        assertAlive("http 提示页 + 本地测试页")
    }

    private fun fpValue(key: String): String {
        val k = waitText(key, t(30_000))
        // 表格：<td>key</td><td>value</td>，值是下一个兄弟节点
        val row = k.parent
        val cells = row?.children ?: emptyList()
        val i = cells.indexOfFirst { it.text == key }
        return cells.getOrNull(i + 1)?.text ?: ""
    }

    @Test fun t08_fingerprintShieldInRealWebView() {
        coldStart()
        typeAndGo("$host/fp.html")
        waitWeb("指纹自测页")
        waitText("toStringNative", t(90_000))
        val hc = fpValue("hardwareConcurrency")
        val native = fpValue("toStringNative")
        val iframeHc = fpValue("iframeHwConc")
        screenshot("fp_localhost")
        // 默认开着防指纹（标准）：核数统一报 8（模拟器实际是 4 核）
        assertEquals("hardwareConcurrency 应该被伪装成 8", "8", hc)
        assertEquals("新 iframe 里也要伪装", "8", iframeHc)
        assertEquals("被改过的函数看起来仍是原生的", "true,true,true,true", native)
        assertAlive("防指纹")
    }

    @Test fun t09_tzLangSpoof() {
        coldStart()
        openSecuritySheet()
        click(scrollTo(By.text("伪造时区和语言")))
        click(scrollTo(By.textContains("美国西部：西雅图 + English")))
        SystemClock.sleep(1500)
        clickDesc("关闭")
        typeAndGo("$host/fp.html")
        waitText("timezone", t(90_000))
        val tz = fpValue("timezone")
        val lang = fpValue("language")
        screenshot("tz_fp")
        assertTrue("时区应为洛杉矶: $tz", tz.startsWith("America/Los_Angeles"))
        assertTrue("偏移应为 480/420: $tz", tz.endsWith("480") || tz.endsWith("420"))
        assertEquals("en-US", lang)
        assertAlive("时区语言伪装")
    }

    @Test fun t10_downloadFile() {
        coldStart()
        sh("rm -f /sdcard/Download/umu-test*.bin")
        typeAndGo("$host/")
        waitWeb("Umu test")
        click(waitTextContains("3. http download", t(30_000)))
        // 可能会弹「下载这个文件吗」之类的确认
        find(By.text("下载"), 5_000)?.let { click(it) }
        var ok = false
        val end = SystemClock.uptimeMillis() + t(60_000)
        while (SystemClock.uptimeMillis() < end) {
            if (sh("ls /sdcard/Download/").contains("umu-test")) { ok = true; break }
            SystemClock.sleep(2000)
        }
        assertAlive("下载")
        assertTrue("下载的文件没出现在 /sdcard/Download", ok)
    }

    @Test fun t11_errorPageOnDeadHost() {
        coldStart()
        typeAndGo("http://localhost:9/")
        waitText("网页打不开", t(90_000))
        waitTextContains("ERR_", t(10_000))
        assertAlive("错误页")
    }

    @Test fun t12_rotateBackgroundReturn() {
        coldStart()
        typeAndGo("$host/page2.html")
        waitWeb("Page 2")
        val before = tabCount()
        device.setOrientationLeft()
        SystemClock.sleep(t(4_000))
        waitWeb("Page 2")
        assertAlive("横屏")
        device.setOrientationNatural()
        SystemClock.sleep(t(4_000))
        device.pressHome()
        SystemClock.sleep(t(3_000))
        // 从最近任务回来（不能多开一个标签）
        device.pressRecentApps()
        SystemClock.sleep(t(3_000))
        sh("am start -n $PKG/.MainActivity")
        waitWeb("Page 2")
        assertEquals("回来后标签数不变", before, tabCount())
        device.unfreezeRotation()
        assertAlive("旋转/后台/返回")
    }
}
