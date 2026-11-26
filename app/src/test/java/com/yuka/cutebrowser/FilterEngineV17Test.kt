package com.yuka.cutebrowser

import com.yuka.cutebrowser.filter.FilterEngine
import com.yuka.cutebrowser.filter.FilterRequest
import com.yuka.cutebrowser.filter.ResType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** v1.7：$badfilter、$match-case、只限定类型的规则不套到猜不出类型的请求上 */
class FilterEngineV17Test {
    private fun engine(vararg rules: String) = FilterEngine().apply { rules.forEach { addLine(it) }; finish() }

    private fun req(raw: String, page: String? = "news.example.org", type: Int = ResType.UNKNOWN): FilterRequest {
        val u = raw.lowercase()
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':')
        return FilterRequest(u, host, page, type, raw)
    }

    private fun roundTrip(e: FilterEngine): FilterEngine {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { e.writeTo(it) }
        return FilterEngine.readFrom(DataInputStream(ByteArrayInputStream(bo.toByteArray())))!!
    }

    @Test fun badfilterRemovesPlainHostRule_inAnyOrder() {
        val a = engine("||ads.example.com^", "||ads.example.com^\$badfilter", "||other.net^")
        assertFalse(a.shouldBlock(req("https://ads.example.com/x.js")))
        assertTrue(a.shouldBlock(req("https://other.net/x.js")))
        val b = engine("||ads.example.com^\$badfilter", "||ads.example.com^")
        assertFalse(b.shouldBlock(req("https://ads.example.com/x.js")))
        assertEquals(1, b.networkRuleCount)
    }

    @Test fun badfilterHostsListEntryAndOptionOrder() {
        val e = FilterEngine().apply {
            addHosts(listOf("tracker.io", "keep.io"))
            addLine("||tracker.io^\$badfilter")
            addLine("||cdn.x.com^\$script,third-party,domain=a.com|b.com")
            addLine("||cdn.x.com^\$third-party,domain=b.com|a.com,script,badfilter")
            addLine("/pixel/*/track^\$image")
            addLine("/pixel/*/track^\$image,badfilter")
            finish()
        }
        assertFalse(e.shouldBlock(req("https://tracker.io/x")))
        assertTrue(e.shouldBlock(req("https://keep.io/x")))
        assertFalse(e.shouldBlock(req("https://cdn.x.com/a.js", page = "a.com", type = ResType.SCRIPT)))
        assertFalse(e.shouldBlock(req("https://y.com/pixel/1/track", type = ResType.IMAGE)))
    }

    @Test fun badfilterOnlyMatchesIdenticalRule() {
        // 选项不同 → 不是同一条规则，不作废
        val e = engine("||ads.example.com^\$third-party", "||ads.example.com^\$badfilter", "@@||ads.example.com^\$badfilter")
        assertTrue(e.shouldBlock(req("https://ads.example.com/x.js")))
        // 例外规则也能被 badfilter
        val f = engine("||ads.example.com^", "@@||ads.example.com/ok^", "@@||ads.example.com/ok^\$badfilter")
        assertTrue(f.shouldBlock(req("https://ads.example.com/ok/x.js")))
    }

    @Test fun matchCase() {
        val e = engine("/AdBanner/*\$match-case", "/promo/")
        assertTrue(e.shouldBlock(req("https://x.com/AdBanner/1.png")))
        assertFalse(e.shouldBlock(req("https://x.com/adbanner/1.png")))
        assertFalse(e.shouldBlock(req("https://x.com/ADBANNER/1.png")))
        assertTrue(e.shouldBlock(req("https://x.com/PROMO/1.png"))) // 没有 match-case 的照旧不分大小写
        // 缓存往返后仍然区分大小写
        val c = roundTrip(e)
        assertTrue(c.shouldBlock(req("https://x.com/AdBanner/1.png")))
        assertFalse(c.shouldBlock(req("https://x.com/adbanner/1.png")))
        // 纯小写 / 纯域名的 match-case 没有意义，当普通规则
        val d = engine("||Ads.Example.com^\$match-case", "/lower/\$match-case")
        assertTrue(d.shouldBlock(req("https://ads.example.com/x")))
        assertTrue(d.shouldBlock(req("https://x.com/lower/a")))
        assertFalse(d.shouldBlock(req("https://x.com/LOWER/a")))
    }

    @Test fun typeRestrictedRulesSkipUnknownType() {
        val e = engine("||img.cdn.com^\$image", "||s.cdn.com^\$script,stylesheet", "||n.cdn.com^\$~image", "||all.cdn.com^\$all", "||plain.cdn.com^")
        // 猜不出类型（比如没有后缀的 XHR）：只限定了类型的规则不拦
        assertFalse(e.shouldBlock(req("https://img.cdn.com/api/data")))
        assertFalse(e.shouldBlock(req("https://s.cdn.com/api/data")))
        // 知道类型时照常
        assertTrue(e.shouldBlock(req("https://img.cdn.com/a.png", type = ResType.IMAGE)))
        assertFalse(e.shouldBlock(req("https://img.cdn.com/a.js", type = ResType.SCRIPT)))
        assertTrue(e.shouldBlock(req("https://s.cdn.com/a.css", type = ResType.STYLESHEET)))
        // 只排除类型的 / $all / 没限定类型的：猜不出类型也拦
        assertTrue(e.shouldBlock(req("https://n.cdn.com/api/data")))
        assertFalse(e.shouldBlock(req("https://n.cdn.com/a.png", type = ResType.IMAGE)))
        assertTrue(e.shouldBlock(req("https://all.cdn.com/api/data")))
        assertTrue(e.shouldBlock(req("https://plain.cdn.com/api/data")))
        // 缓存往返后行为一致
        val c = roundTrip(e)
        assertFalse(c.shouldBlock(req("https://img.cdn.com/api/data")))
        assertTrue(c.shouldBlock(req("https://n.cdn.com/api/data")))
    }

    @Test fun typedExceptionDoesNotUnblockUnknown() {
        // 例外规则同样：@@…$image 不放行猜不出类型的请求
        val e = engine("||t.com^", "@@||t.com/pic^\$image")
        assertTrue(e.shouldBlock(req("https://t.com/pic/x")))
        assertFalse(e.shouldBlock(req("https://t.com/pic/x.png", type = ResType.IMAGE)))
    }
}
