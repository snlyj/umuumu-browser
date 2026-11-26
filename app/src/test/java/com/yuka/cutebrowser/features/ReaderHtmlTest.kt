package com.yuka.cutebrowser.features

import com.yuka.cutebrowser.ui.theme.StarryPalette
import com.yuka.cutebrowser.ui.theme.StrawberryPalette
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderHtmlTest {
    /** 模拟 evaluateJavascript 的返回：JS 字符串再做一次 JSON 编码 */
    private fun jsResult(o: JSONObject): String = JSONObject.quote(o.toString())

    private val sample = JSONObject()
        .put("title", "兔子的一天 <script>alert(1)</script>")
        .put("byline", "小兔记者")
        .put("site", "萌宠日报")
        .put("lang", "zh-CN")
        .put("dir", "ltr")
        .put("length", 1200)
        .put("html", "<p>早上，兔子先吃了一根胡萝卜。</p><h2>中午</h2><p>中午在草地上晒太阳。</p><blockquote>兔子说：今天也要开开心心！</blockquote>")

    @Test
    fun parsesEvaluateJavascriptResult() {
        val a = ReaderHtml.parseResult(jsResult(sample))
        assertNotNull(a)
        assertEquals("小兔记者", a!!.byline)
        assertEquals("zh-CN", a.lang)
        assertFalse(a.rtl)
        assertEquals(3, ReaderHtml.minutes(a))
    }

    @Test
    fun rejectsNullGarbageAndBadLang() {
        assertNull(ReaderHtml.parseResult(null))
        assertNull(ReaderHtml.parseResult("null"))
        assertNull(ReaderHtml.parseResult("true"))
        assertNull(ReaderHtml.parseResult("\"not json\""))
        assertNull(ReaderHtml.parseResult(jsResult(JSONObject(sample.toString()).put("html", ""))))
        val bad = ReaderHtml.parseResult(jsResult(JSONObject(sample.toString()).put("lang", "zh\" onload=\"x")))!!
        assertEquals("", bad.lang)
    }

    @Test
    fun buildEscapesHeaderAndHasStrictCsp() {
        val a = ReaderHtml.parseResult(jsResult(sample))!!
        val html = ReaderHtml.build(a, ReaderStyle(), ReaderHtml.colors(ReaderTheme.AUTO, StrawberryPalette), "pets.example")
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("img-src https: data:"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("兔子的一天 &lt;script&gt;"))
        assertTrue(html.contains("约 3 分钟读完"))
        assertTrue(html.contains("line-height:1.80"))
        // 主题色来自调色板
        assertTrue(html.contains("#FFF8F3"))
    }

    @Test
    fun themesAndStyleSteps() {
        assertEquals(110, ReaderStyle().zoomIn().zoom)
        assertEquals(ReaderStyle.MAX_ZOOM, ReaderStyle(zoom = 200).zoomIn().zoom)
        assertEquals(ReaderStyle.MIN_ZOOM, ReaderStyle(zoom = 70).zoomOut().zoom)
        assertEquals(2.1f, ReaderStyle(lineHeight = 1.8f).nextLineHeight().lineHeight)
        assertEquals(1.5f, ReaderStyle(lineHeight = 2.1f).nextLineHeight().lineHeight)
        val night = ReaderHtml.colors(ReaderTheme.NIGHT, StrawberryPalette)
        val sepia = ReaderHtml.colors(ReaderTheme.SEPIA, StrawberryPalette)
        assertTrue(night.bg != sepia.bg)
    }

    /** 给 reader-js/chrome-test.mjs 用：把几种样式的阅读页写到 build 目录，用桌面 Chrome 截图看效果 */
    @Test
    fun writeSamplesForChromeHarness() {
        val dir = File("build/reader-samples").apply { mkdirs() }
        val body = (1..4).joinToString("") {
            "<p>兔子是一种很可爱的小动物，耳朵长长的，尾巴短短的。它们喜欢吃新鲜的蔬菜和干草，也喜欢在安全的地方蹦蹦跳跳。第 $it 段。</p>"
        } + "<h2>怎么照顾兔子</h2><ul><li>每天给足干草</li><li>准备磨牙的小木块</li><li>不要只喂胡萝卜哦</li></ul>" +
            "<blockquote>兔子开心的时候会原地跳起来转个圈，这叫「兔子舞」。</blockquote>" +
            "<p>想了解更多，可以看看<a href=\"https://example.com/rabbit\">兔子百科</a>。</p>"
        val a = ReaderHtml.parseResult(jsResult(JSONObject(sample.toString()).put("title", "兔子的一天：从早餐到晚安").put("html", body).put("length", 1600)))!!
        val variants = mapOf(
            "auto" to ReaderHtml.build(a, ReaderStyle(), ReaderHtml.colors(ReaderTheme.AUTO, StrawberryPalette), "pets.example"),
            "sepia" to ReaderHtml.build(a, ReaderStyle(theme = ReaderTheme.SEPIA, font = ReaderFont.SERIF), ReaderHtml.colors(ReaderTheme.SEPIA, StrawberryPalette), "pets.example"),
            "night" to ReaderHtml.build(a, ReaderStyle(theme = ReaderTheme.NIGHT, lineHeight = 2.1f), ReaderHtml.colors(ReaderTheme.NIGHT, StarryPalette), "pets.example"),
        )
        variants.forEach { (k, v) -> File(dir, "$k.html").writeText(v) }
        assertTrue(File(dir, "auto.html").length() > 1000)
    }
}
