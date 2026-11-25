package com.yuka.cutebrowser

import com.yuka.cutebrowser.filter.FilterEngine
import com.yuka.cutebrowser.filter.FilterRequest
import com.yuka.cutebrowser.filter.ResType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class FilterEngineTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { rules.forEach { addLine(it) }; finish() }

    private fun req(url: String, page: String? = "news.example.org", type: Int = ResType.UNKNOWN): FilterRequest {
        val u = url.lowercase()
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':')
        return FilterRequest(u, host, page, type)
    }

    @Test
    fun hostAnchorAndSeparator() {
        val e = engine("||ads.example.com^", "||tracker.net^\$third-party", "/banner/*/img^")
        assertTrue(e.shouldBlock(req("https://ads.example.com/x.js")))
        assertTrue(e.shouldBlock(req("https://cdn.ads.example.com/x.js")))
        assertFalse(e.shouldBlock(req("https://badads.example.com/x.js")))
        assertTrue(e.shouldBlock(req("https://tracker.net/p.gif")))
        assertFalse(e.shouldBlock(req("https://tracker.net/p.gif", page = "www.tracker.net")))
        assertTrue(e.shouldBlock(req("https://x.com/banner/123/img?x=1")))
        assertTrue(e.shouldBlock(req("https://x.com/banner/123/img")))
        assertFalse(e.shouldBlock(req("https://x.com/banner/123/imgs")))
    }

    @Test
    fun exceptionsImportantAndDomains() {
        val e = engine(
            "||cdn.ads.com^", "@@||cdn.ads.com/allowed/",
            "||evil.com^\$important", "@@||evil.com^",
            "/adframe.\$domain=a.com|~b.a.com", "||img.only^\$image",
        )
        assertTrue(e.shouldBlock(req("https://cdn.ads.com/x.js")))
        assertFalse(e.shouldBlock(req("https://cdn.ads.com/allowed/x.js")))
        assertTrue(e.shouldBlock(req("https://evil.com/x")))
        assertTrue(e.shouldBlock(req("https://s.com/adframe.js", page = "www.a.com")))
        assertFalse(e.shouldBlock(req("https://s.com/adframe.js", page = "b.a.com")))
        assertFalse(e.shouldBlock(req("https://s.com/adframe.js", page = "c.com")))
        assertTrue(e.shouldBlock(req("https://img.only/a.png", type = ResType.IMAGE)))
        assertFalse(e.shouldBlock(req("https://img.only/a.js", type = ResType.SCRIPT)))
    }

    @Test
    fun unsupportedRulesAreSkipped() {
        val e = engine("||x.com^\$redirect=noopjs", "/^https?:\\/\\/regex/", "||popup.com^\$popup", "||csp.com^\$csp=script-src 'none'")
        assertFalse(e.shouldBlock(req("https://x.com/a.js")))
        assertFalse(e.shouldBlock(req("https://popup.com/a.js")))
        assertEquals(0, e.networkRuleCount)
    }

    @Test
    fun cosmeticAndDocumentException() {
        val e = engine("example.com,~shop.example.com##.ad-box", "example.com##div[id^=\"ad-\"]", "news.example.com#@#.ad-box",
            "example.com##.x:has-text(Ad)", "@@||trusted.org^\$document")
        val css = e.cosmeticCss("https://www.example.com/", "www.example.com")
        assertNotNull(css); assertTrue(css!!.contains(".ad-box{")); assertFalse(css.contains("has-text"))
        assertFalse(e.cosmeticCss("https://news.example.com/", "news.example.com")!!.contains(".ad-box"))
        assertFalse(e.cosmeticCss("https://shop.example.com/", "shop.example.com")!!.contains(".ad-box"))
        assertNull(e.cosmeticCss("https://other.com/", "other.com"))
        assertTrue(e.isPageAllowlisted("https://www.trusted.org/", "www.trusted.org"))
    }

    @Test
    fun fullEasyListAndEasyPrivacy_compileCacheAndSpeed() {
        val dir = File("src/main/assets/filters")
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val e = FilterEngine()
        File(dir, "easylist.txt").bufferedReader().use { e.addList(it) }
        File(dir, "easyprivacy.txt").bufferedReader().use { e.addList(it) }
        e.finish()
        val compileMs = (System.nanoTime() - t0) / 1e6
        repeat(3) { System.gc(); Thread.sleep(50) }
        val heapMb = (rt.totalMemory() - rt.freeMemory() - before) / 1048576.0
        println("FILTER_HEAP_MB=%.1f".format(heapMb))

        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { e.writeTo(it) }
        val t1 = System.nanoTime()
        val e2 = FilterEngine.readFrom(DataInputStream(ByteArrayInputStream(bos.toByteArray())))!!
        val loadMs = (System.nanoTime() - t1) / 1e6
        assertEquals(e.networkRuleCount, e2.networkRuleCount)

        assertTrue(e2.shouldBlock(req("https://securepubads.g.doubleclick.net/tag/js/gpt.js", type = ResType.SCRIPT)))
        assertTrue(e2.shouldBlock(req("https://www.google-analytics.com/analytics.js", type = ResType.SCRIPT)))
        assertFalse(e2.shouldBlock(req("https://www.example.org/static/app.js", page = "www.example.org", type = ResType.SCRIPT)))
        assertFalse(e2.shouldBlock(req("https://i0.hdslb.com/bfs/archive/cover.jpg", page = "m.bilibili.com", type = ResType.IMAGE)))

        val urls = listOf(
            "https://www.example.org/static/js/main.4f2a1b.js", "https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js",
            "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Mu4mxK.woff2", "https://i0.hdslb.com/bfs/face/abc.jpg@96w_96h.webp",
            "https://securepubads.g.doubleclick.net/pagead/ads?gdfp_req=1&correlator=123", "https://www.googletagmanager.com/gtm.js?id=GTM-XXXX",
            "https://api.example.org/v1/feed?page=2&size=20&sort=hot", "https://img.alicdn.com/imgextra/i1/O1CN01abc.png_400x400.jpg",
            "https://hm.baidu.com/hm.js?abcdef0123456789", "https://static.zhihu.com/heifetz/main.app.216a26f4.js",
        ).map { req(it, page = "www.example.org") }
        repeat(2000) { for (r in urls) e2.shouldBlock(r) } // 预热 JIT
        val n = 200_000
        val t2 = System.nanoTime()
        var blocked = 0
        for (i in 0 until n) if (e2.shouldBlock(urls[i % urls.size])) blocked++
        val perReqUs = (System.nanoTime() - t2) / 1e3 / n
        println("FILTER_STATS network=${e.networkRuleCount} cosmetic=${e.cosmeticRuleCount} compileMs=%.0f cacheBytes=${bos.size()} loadMs=%.0f perRequestUs=%.2f blocked=$blocked/$n".format(compileMs, loadMs, perReqUs))
        assertTrue("match too slow: $perReqUs µs", perReqUs < 20.0)
    }

    @Test
    fun hostAnchorDoesNotMatchLookalikes() {
        val e = engine("||example.com^", "||ads.net/track^", "||1.2.3.4^")
        assertTrue(e.shouldBlock(req("https://example.com/")))
        assertTrue(e.shouldBlock(req("https://example.com:8443/x")))
        assertTrue(e.shouldBlock(req("https://a.b.example.com/x")))
        assertFalse(e.shouldBlock(req("https://example.com.evil.net/x")))
        assertFalse(e.shouldBlock(req("https://notexample.com/x")))
        assertFalse(e.shouldBlock(req("https://evil.net/?u=example.com")))
        assertFalse(e.shouldBlock(req("https://evil.net/example.com/x")))
        assertTrue(e.shouldBlock(req("https://ads.net/track?x=1")))
        assertTrue(e.shouldBlock(req("https://cdn.ads.net/track")))
        assertFalse(e.shouldBlock(req("https://badads.net/track")))
        assertFalse(e.shouldBlock(req("https://ads.net/tracker")))
        assertTrue(e.shouldBlock(req("http://1.2.3.4/x.js")))
        assertFalse(e.shouldBlock(req("http://11.2.3.4/x.js")))
    }

    @Test
    fun anchorsWildcardsAndQuery() {
        val e = engine("|https://a.com/x|", "||b.com/*/ads/*.js", "&ad_type=", "/ads/*?id=", "swf|")
        assertTrue(e.shouldBlock(req("https://a.com/x")))
        assertFalse(e.shouldBlock(req("https://a.com/x/y")))
        assertFalse(e.shouldBlock(req("http://a.com/x"))) // 起始锚点是 https://
        assertFalse(e.shouldBlock(req("https://z.com/?u=https://a.com/x")))
        assertTrue(e.shouldBlock(req("https://b.com/v1/ads/banner.js")))
        assertTrue(e.shouldBlock(req("https://cdn.b.com/v1/ads/banner.js")))
        assertFalse(e.shouldBlock(req("https://b.com/v1/ads/banner.css")))
        assertTrue(e.shouldBlock(req("https://c.com/p?x=1&ad_type=2")))
        assertFalse(e.shouldBlock(req("https://c.com/p?ad_type=2"))) // 规则以 & 开头：必须真有一个 &
        assertTrue(e.shouldBlock(req("https://d.com/ads/foo/bar?id=7")))
        assertTrue(e.shouldBlock(req("https://d.com/media/x.swf")))
        assertFalse(e.shouldBlock(req("https://d.com/media/x.swf?x")))
    }

    @Test
    fun partyAndDomainOptions() {
        val e = engine(
            "||t.com^\$third-party", "||u.com^\$first-party", "||v.com^\$~third-party",
            "||w.com^\$domain=a.com", "@@||w.com^\$domain=sub.a.com", "||x.co.uk^\$3p",
        )
        assertTrue(e.shouldBlock(req("https://t.com/p", page = "z.org")))
        assertFalse(e.shouldBlock(req("https://t.com/p", page = "t.com")))
        assertFalse(e.shouldBlock(req("https://u.com/p", page = "z.org")))
        assertTrue(e.shouldBlock(req("https://u.com/p", page = "m.u.com")))
        assertTrue(e.shouldBlock(req("https://v.com/p", page = "v.com")))
        assertFalse(e.shouldBlock(req("https://v.com/p", page = "z.org")))
        assertTrue(e.shouldBlock(req("https://w.com/p", page = "a.com")))
        assertTrue(e.shouldBlock(req("https://w.com/p", page = "x.a.com")))
        assertFalse(e.shouldBlock(req("https://w.com/p", page = "sub.a.com")))
        assertFalse(e.shouldBlock(req("https://w.com/p", page = "nota.com")))
        // 二级后缀：a.x.co.uk 和 x.co.uk 是同一个网站，b.co.uk 不是
        assertFalse(e.shouldBlock(req("https://x.co.uk/p", page = "shop.x.co.uk")))
        assertTrue(e.shouldBlock(req("https://x.co.uk/p", page = "b.co.uk")))
        // 没有页面（独立请求）：当作第三方
        assertTrue(e.shouldBlock(req("https://t.com/p", page = null)))
    }

    @Test
    fun hostingSuffixesAreDifferentSites() {
        // 以前 foo.web.app / bar.web.app 都被当成 "web.app" 这一个网站
        val e = engine("||t.com^\$third-party")
        assertTrue(com.yuka.cutebrowser.filter.Domains.base("foo.web.app") != com.yuka.cutebrowser.filter.Domains.base("bar.web.app"))
        assertEquals("a.co.th", com.yuka.cutebrowser.filter.Domains.base("www.a.co.th"))
        assertEquals("example.com", com.yuka.cutebrowser.filter.Domains.base("www.example.com"))
        assertNotNull(e)
    }

    @Test
    fun typeOptionsWithUnknownTypeAreLenient() {
        val e = engine("||img.only^\$image,~third-party", "||cdn.net^\$script,stylesheet", "||all.net^\$all")
        assertTrue(e.shouldBlock(req("https://img.only/a.png", page = "img.only", type = ResType.IMAGE)))
        assertFalse(e.shouldBlock(req("https://img.only/a.png", page = "z.org", type = ResType.IMAGE)))
        assertTrue(e.shouldBlock(req("https://cdn.net/a.css", type = ResType.STYLESHEET)))
        assertFalse(e.shouldBlock(req("https://cdn.net/a.png", type = ResType.IMAGE)))
        assertTrue(e.shouldBlock(req("https://all.net/x", type = ResType.XHR)))
    }

    @Test
    fun degenerateRulesAndUrlsDoNotThrow() {
        val e = engine("*", "||", "|", "^", "**^**", "\$third-party", "@@", "##", "a##", "||x^\$", "||x^\$domain=", "/ads/*/*/*/*/*/*/*/*/z")
        for (u in listOf("", "https://", "https://a", "https://a.b/", "x", "https://a.b/?" + "a".repeat(10_000), "https://a.b/" + "/ads".repeat(5000))) {
            val host = u.substringAfter("://", "").substringBefore('/')
            e.shouldBlock(FilterRequest(u, host, "p.com", ResType.UNKNOWN))
            e.isPageAllowlisted(u, host)
            e.cosmeticCss(u, host)
        }
        assertFalse(e.shouldBlock(req("https://anything.com/x")))
    }
}
