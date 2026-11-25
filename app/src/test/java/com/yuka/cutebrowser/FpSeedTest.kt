package com.yuka.cutebrowser

import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.privacy.fingerprint.FingerprintShield
import com.yuka.cutebrowser.privacy.fingerprint.FpSeed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 防指纹：网站(eTLD+1)、种子推导（和 JS 版对照）、脚本组装 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FpSeedTest {
    @Test fun siteIsApproxEtldPlusOne() {
        // 这些期望值和 `node fp-js/seed-vectors.mjs` 用 JS 源码算出来的一样
        assertEquals("example.com", FpSeed.site("www.example.com"))
        assertEquals("example.co.uk", FpSeed.site("a.b.example.co.uk"))
        assertEquals("sina.com.cn", FpSeed.site("news.sina.com.cn"))
        assertEquals("example.com", FpSeed.site("Example.COM."))
        assertEquals("yuka.github.io", FpSeed.site("yuka.github.io"))
        assertEquals("192.168.1.10", FpSeed.site("192.168.1.10"))
        assertEquals("[::1]", FpSeed.site("[::1]"))
        assertEquals("localhost", FpSeed.site("localhost"))
        assertEquals("", FpSeed.site(""))
        assertEquals("", FpSeed.site(null))
        assertEquals(FpSeed.site("m.baidu.com"), FpSeed.site("www.baidu.com"))
    }

    @Test fun seedMatchesJsVectors() {
        // node fp-js/seed-vectors.mjs --check 会确认这些数字和 JS 一致
        assertEquals(3947827130L, FpSeed.seed("aaaa1111bbbb2222", "example.com"))
        assertEquals(1146157053L, FpSeed.seed("aaaa1111bbbb2222", "example.co.uk"))
        assertEquals(434317807L, FpSeed.seed("aaaa1111bbbb2222", ""))
        assertEquals(186900422L, FpSeed.seed("唔姆", "example.com"))
        assertEquals(2726116393L, FpSeed.seed("唔姆", "example.co.uk"))
        assertEquals(2037559987L, FpSeed.seed("唔姆", ""))
    }

    @Test fun seedsDifferPerSiteAndKey() {
        val k1 = FpSeed.newKey()
        val k2 = FpSeed.newKey()
        assertEquals(32, k1.length)
        assertNotEquals(k1, k2)
        assertEquals(FpSeed.seed(k1, "a.com"), FpSeed.seed(k1, "a.com"))
        assertNotEquals(FpSeed.seed(k1, "a.com"), FpSeed.seed(k1, "b.com"))
        assertNotEquals(FpSeed.seed(k1, "a.com"), FpSeed.seed(k2, "a.com"))
        // 1000 个网站基本不撞
        val seeds = (0 until 1000).map { FpSeed.seed(k1, "site$it.com") }.toSet()
        assertTrue(seeds.size >= 999)
    }

    @Test fun scriptAssembly() {
        val shield = FingerprintShield(ApplicationProvider.getApplicationContext())
        val normal = shield.script(false)!!
        assertTrue("placeholder replaced", !normal.contains(FingerprintShield.PLACEHOLDER))
        val cfg = configOf(normal)
        assertEquals(1, cfg.getInt("l"))
        assertEquals(0, cfg.getJSONArray("x").length())
        assertTrue(cfg.getJSONArray("p").length() > 10)
        // 隐身用自己的密钥；结束隐身后换新的
        val inc1 = configOf(shield.script(true)!!).getString("k")
        assertNotEquals(cfg.getString("k"), inc1)
        assertEquals(inc1, configOf(shield.script(true)!!).getString("k"))
        shield.endIncognito()
        assertNotEquals(inc1, configOf(shield.script(true)!!).getString("k"))
        // 严格 + 例外
        assertTrue(shield.update(true, true, setOf("example.com"), emptyList()))
        val c2 = configOf(shield.script(false)!!)
        assertEquals(2, c2.getInt("l"))
        assertEquals("example.com", c2.getJSONArray("x").getString(0))
        assertTrue(shield.isExempt("www.example.com"))
        assertTrue(!shield.update(true, true, setOf("example.com"), emptyList()))
        // 关掉
        shield.update(false, true, emptySet(), emptyList())
        assertNull(shield.script(false))
        assertNull(shield.fallbackScript(false))
    }

    /** 组装好的脚本能被 JS 引擎解析（机器上有 node 才跑） */
    @Test fun assembledScriptParses() {
        val node = listOf("/usr/local/bin/node", "/usr/bin/node", "/usr/bin/nodejs").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("no node on this machine", node != null)
        val shield = FingerprintShield(ApplicationProvider.getApplicationContext())
        val f = File.createTempFile("umu-fp", ".js").apply { writeText(shield.script(false)!!); deleteOnExit() }
        val p = ProcessBuilder(node!!.path, "--check", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("node --check: $out", 0, p.waitFor())
    }

    private fun configOf(script: String): JSONObject {
        val end = script.lastIndexOf(")")
        val start = script.lastIndexOf("({\"k\"") + 1
        return JSONObject(script.substring(start, end))
    }
}
