package com.yuka.cutebrowser

import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.privacy.fingerprint.FingerprintShield
import com.yuka.cutebrowser.privacy.locale.LangPolicy
import com.yuka.cutebrowser.privacy.locale.SiteTzLang
import com.yuka.cutebrowser.privacy.locale.SpoofConfig
import com.yuka.cutebrowser.privacy.locale.TzLang
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** v1.5 时区与语言：数据、语言策略、脚本配置、Accept-Language */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TzLangTest {
    @Test fun zones() {
        assertTrue(TzLang.isValidZone("Asia/Tokyo"))
        assertTrue(TzLang.isValidZone("UTC"))
        assertFalse(TzLang.isValidZone("Mars/Base"))
        TzLang.COMMON_ZONES.forEach { assertTrue(it.id, TzLang.isValidZone(it.id)) }
        TzLang.RANDOM_POOL.forEach { assertTrue(it, TzLang.isValidZone(it)) }
        TzLang.PRESETS.forEach { assertTrue(TzLang.isValidZone(it.tz)); assertNotNull(TzLang.normalizeLang(it.lang)) }
        assertEquals("UTC+8", TzLang.offsetLabel("Asia/Shanghai"))
        assertEquals("UTC+5:30", TzLang.offsetLabel("Asia/Kolkata"))
        // 夏令时：走 IANA / java.time，不写死偏移。纽约 1 月 UTC-5、7 月 UTC-4；西雅图同洛杉矶
        assertEquals("UTC-5", TzLang.offsetLabel("America/New_York", 1767225600000L))
        assertEquals("UTC-4", TzLang.offsetLabel("America/New_York", 1782864000000L))
        assertEquals("UTC-8", TzLang.offsetLabel("America/Los_Angeles", 1767225600000L))
        assertEquals("UTC-7", TzLang.offsetLabel("America/Los_Angeles", 1782864000000L))
        val sea = TzLang.COMMON_ZONES.single { it.id == "America/Los_Angeles" }
        assertTrue(sea.label.contains("西雅图"))
        assertTrue(sea.label.contains("Seattle"))
        assertTrue(TzLang.COMMON_ZONES.any { it.id == "America/Denver" && it.label.contains("丹佛") })
        assertTrue(TzLang.COMMON_ZONES.any { it.id == "America/Chicago" })
        assertTrue(TzLang.COMMON_ZONES.any { it.id == "America/New_York" })
        assertTrue(TzLang.LANGUAGES.any { it.id == "en-US" })
        assertEquals("America/Los_Angeles", TzLang.PRESETS.first { it.tz == "America/Los_Angeles" }.tz)
        // 常用的排在最前，且不重复
        val all = TzLang.searchZones("")
        assertEquals("Asia/Shanghai", all.first().id)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.size > 300)
    }

    @Test fun zoneSearch() {
        assertTrue(TzLang.searchZones("东京").any { it.id == "Asia/Tokyo" })
        assertTrue(TzLang.searchZones("tokyo").any { it.id == "Asia/Tokyo" })
        assertTrue(TzLang.searchZones("new york").any { it.id == "America/New_York" })
        assertTrue(TzLang.searchZones("seattle").any { it.id == "America/Los_Angeles" })
        assertTrue(TzLang.searchZones("西雅图").any { it.id == "America/Los_Angeles" })
        assertTrue(TzLang.searchZones("洛杉矶").any { it.id == "America/Los_Angeles" })
        assertTrue(TzLang.searchZones("丹佛").any { it.id == "America/Denver" })
        assertTrue(TzLang.searchZones("utc+9").any { it.id == "Asia/Tokyo" })
        assertTrue(TzLang.searchZones("zzzz").isEmpty())
    }

    @Test fun languageTags() {
        assertEquals("ja-JP", TzLang.normalizeLang("ja_jp"))
        assertEquals("pt-BR", TzLang.normalizeLang(" pt-br "))
        assertEquals("fr", TzLang.normalizeLang("FR"))
        assertNull(TzLang.normalizeLang(""))
        assertNull(TzLang.normalizeLang("x"))
        assertNull(TzLang.normalizeLang("hello world"))
        assertNull(TzLang.normalizeLang("en-US; q=1"))
        TzLang.LANGUAGES.forEach { assertEquals(it.id, TzLang.normalizeLang(it.id)) }
        assertEquals(listOf("ja-JP", "ja"), TzLang.languageList("ja-JP"))
        assertEquals(listOf("fr"), TzLang.languageList("fr"))
    }

    @Test fun acceptLanguageHeader() {
        assertEquals("ja-JP,ja;q=0.9", TzLang.acceptLanguage("ja-JP"))
        assertEquals("en-US,en;q=0.9", TzLang.acceptLanguage("en-US"))
        assertEquals("fr", TzLang.acceptLanguage("fr"))
        assertEquals("zh-CN,zh;q=0.9", TzLang.acceptLanguage("zh-CN"))
    }

    @Test fun langPolicy() {
        val off = SpoofConfig()
        assertNull(LangPolicy.effective(off, antiFp = false, fpExempt = false, site = "a.com"))
        // 防指纹开着、没选语言：统一 zh-CN；站点被排除防指纹时用真实的
        assertEquals("zh-CN", LangPolicy.effective(off, true, false, "a.com"))
        assertNull(LangPolicy.effective(off, true, true, "a.com"))
        // 明确选了语言：压过防指纹默认值，防指纹关着也生效
        val ja = SpoofConfig(on = true, lang = "ja-JP", sites = mapOf("b.com" to SiteTzLang(lang = "de-DE"), "c.com" to SiteTzLang(lang = TzLang.REAL)))
        assertEquals("ja-JP", LangPolicy.effective(ja, false, false, "a.com"))
        assertEquals("ja-JP", LangPolicy.effective(ja, true, false, "a.com"))
        assertEquals("de-DE", LangPolicy.effective(ja, false, false, "b.com"))
        assertNull(LangPolicy.effective(ja, false, false, "c.com"))
        assertNull(LangPolicy.effective(ja, true, false, "c.com"))
        // 总开关关掉后站点设置也不生效
        assertNull(LangPolicy.effective(ja.copy(on = false), false, false, "b.com"))
        // 进程级：只看全局
        assertEquals("ja-JP", LangPolicy.processLang(ja, false))
        assertEquals("zh-CN", LangPolicy.processLang(SpoofConfig(on = true), true))
        assertNull(LangPolicy.processLang(SpoofConfig(on = true), false))
    }

    @Test fun configHasEffect() {
        assertFalse(SpoofConfig().hasEffect)
        assertFalse(SpoofConfig(on = true).hasEffect)
        assertFalse(SpoofConfig(on = false, tz = "Asia/Tokyo").hasEffect)
        assertTrue(SpoofConfig(on = true, tz = "Asia/Tokyo").hasEffect)
        assertTrue(SpoofConfig(on = true, sites = mapOf("a.com" to SiteTzLang(tz = "UTC"))).hasEffect)
        assertEquals("Asia/Tokyo", SpoofConfig(on = true, tz = "Asia/Tokyo", sites = mapOf("a.com" to SiteTzLang(lang = "fr"))).tzFor("a.com"))
        assertEquals("", SpoofConfig(on = false, tz = "Asia/Tokyo").tzFor("a.com"))
    }

    @Test fun shieldConfigAndIndependence() {
        val shield = FingerprintShield(ApplicationProvider.getApplicationContext())
        fun cfg() = JSONObject(shield.script(false)!!.let { it.substring(it.lastIndexOf("({\"k\"") + 1, it.lastIndexOf(")")) })
        // 没开伪造：配置里没有 z/g/o
        assertFalse(cfg().has("z")); assertFalse(cfg().has("g")); assertFalse(cfg().has("o"))
        val sp = SpoofConfig(true, "America/New_York", "en-US", mapOf("jp.example" to SiteTzLang("Asia/Tokyo", null), "rnd.example" to SiteTzLang(TzLang.RANDOM, "-")))
        assertTrue(shield.update(true, false, emptySet(), emptyList(), sp))
        val c = cfg()
        assertEquals(1, c.getInt("l"))
        assertEquals("America/New_York", c.getString("z"))
        assertEquals("en-US", c.getString("g"))
        assertEquals("Asia/Tokyo", c.getJSONObject("o").getJSONObject("jp.example").getString("z"))
        assertFalse(c.getJSONObject("o").getJSONObject("jp.example").has("g"))
        assertEquals("-", c.getJSONObject("o").getJSONObject("rnd.example").getString("g"))
        assertTrue(c.getJSONArray("rz").length() > 5) // 有随机时区，带上候选池
        // 同样的设置不算变化
        assertFalse(shield.update(true, false, emptySet(), emptyList(), sp))
        // 防指纹关掉，伪造照样注入脚本，且 l=0
        assertTrue(shield.update(false, false, emptySet(), emptyList(), sp))
        assertNotNull(shield.script(false))
        assertEquals(0, cfg().getInt("l"))
        assertEquals("America/New_York", cfg().getString("z"))
        // 都关了才不注入
        shield.update(false, false, emptySet(), emptyList(), SpoofConfig())
        assertNull(shield.script(false))
        // 伪造总开关关了但还留着设置：不注入
        shield.update(false, false, emptySet(), emptyList(), sp.copy(on = false))
        assertNull(shield.script(false))
        // 严格 + 开伪造
        shield.update(true, true, emptySet(), emptyList(), SpoofConfig(true, "", "ja-JP"))
        assertEquals(2, cfg().getInt("l"))
        assertEquals("", cfg().getString("z"))
        assertEquals("ja-JP", cfg().getString("g"))
    }

    @Test fun repositoryRoundTrip() = runBlocking {
        val repo = CuteRepository(ApplicationProvider.getApplicationContext())
        // DataStore 文件在同一个 JVM 的测试之间是共用的，先复位
        repo.clearSiteTzLang(); repo.updateSecurity { com.yuka.cutebrowser.data.SecurityPrefs() }
        val d = repo.security.first()
        assertFalse(d.tzOn); assertEquals("", d.tz); assertEquals("", d.lang)
        repo.updateSecurity { it.copy(tzOn = true, tz = "Asia/Tokyo", lang = "ja-JP") }
        val s = repo.security.first()
        assertTrue(s.tzOn); assertEquals("Asia/Tokyo", s.tz); assertEquals("ja-JP", s.lang)
        assertTrue(s.httpsOnly) // 别的字段不受影响

        assertTrue(repo.tzLangSites.first().isEmpty())
        repo.setSiteTzLang("example.com", SiteTzLang("UTC", null))
        repo.setSiteTzLang("b.com", SiteTzLang(null, "fr-FR"))
        assertEquals(mapOf("example.com" to SiteTzLang("UTC", null), "b.com" to SiteTzLang(null, "fr-FR")), repo.tzLangSites.first())
        repo.setSiteTzLang("example.com", SiteTzLang()) // 全空 = 删掉
        assertEquals(setOf("b.com"), repo.tzLangSites.first().keys)
        repo.clearSiteTzLang()
        assertTrue(repo.tzLangSites.first().isEmpty())
        // 坏数据不崩
        assertTrue(repo.decodeSites("{not json").isEmpty())
        assertTrue(repo.decodeSites(null).isEmpty())
    }
}
