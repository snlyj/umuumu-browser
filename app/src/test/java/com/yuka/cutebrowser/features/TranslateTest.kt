package com.yuka.cutebrowser.features

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateTest {
    private var h: ControllerHarness? = null

    @After
    fun tearDown() { h?.close(); h = null }

    @Test
    fun providerUrlsEncodePageAndDropFragment() {
        val page = "https://en.wikipedia.org/wiki/Rabbit?x=1&y=2#Biology"
        assertEquals(
            "https://translate.google.com/translate?sl=auto&tl=zh-CN&hl=zh-CN&u=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FRabbit%3Fx%3D1%26y%3D2",
            Translate.url(TranslateProvider.GOOGLE, TranslateTarget.ZH_CN, page),
        )
        assertEquals(
            "https://translate.yandex.com/translate?url=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FRabbit%3Fx%3D1%26y%3D2&lang=ja",
            Translate.url(TranslateProvider.YANDEX, TranslateTarget.JA, page),
        )
    }

    @Test
    fun privateAndLocalPagesAreNotSent() {
        listOf(
            "http://192.168.1.1/", "http://10.0.0.8:8080/a", "http://172.20.3.4/", "http://127.0.0.1:8000/",
            "http://localhost/", "http://router/", "http://nas.local/", "http://[fe80::1]/", "http://[::1]/",
            "http://100.64.1.1/", "http://printer.lan/", "file:///sdcard/a.html", "about:blank",
        ).forEach { assertFalse(it, Translate.isTranslatable(it)) }
        listOf("https://en.wikipedia.org/wiki/Rabbit", "http://example.com/", "https://8.8.8.8/", "https://fdroid.org/")
            .forEach { assertTrue(it, Translate.isTranslatable(it)) }
    }

    @Test
    fun nothingIsSentUntilConfirmed() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        hh.openPage("https://en.wikipedia.org/wiki/Rabbit")
        val before = c.tabs.size
        c.features.askTranslate()
        val ask = c.features.translateAsk
        assertNotNull(ask)
        assertEquals("en.wikipedia.org", ask!!.host)
        assertEquals("还没确认，不能开翻译页", before, c.tabs.size)

        c.features.cancelTranslate()
        assertNull(c.features.translateAsk)
        assertEquals(before, c.tabs.size)

        c.features.askTranslate()
        c.features.confirmTranslate(TranslateProvider.YANDEX, TranslateTarget.EN)
        assertEquals(before + 1, c.tabs.size)
        assertTrue(c.current.url.startsWith("https://translate.yandex.com/translate?url=https%3A%2F%2Fen.wikipedia.org"))
        assertEquals(TranslateProvider.YANDEX, c.features.prefs.translateProvider)
        assertEquals(TranslateTarget.EN, c.features.prefs.translateTarget)
    }

    @Test
    fun incognitoTranslatesInIncognitoTab() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        c.newTab("https://example.com/secret", incognito = true)
        c.features.askTranslate()
        assertTrue(c.features.translateAsk!!.incognito)
        c.features.confirmTranslate(TranslateProvider.GOOGLE, TranslateTarget.ZH_CN)
        assertTrue(c.current.incognito)
    }

    @Test
    fun homeAndLanCannotAsk() {
        val hh = ControllerHarness().also { h = it }
        val c = hh.controller
        c.features.askTranslate()
        assertNull(c.features.translateAsk)
        hh.openPage("http://192.168.1.1/")
        c.features.askTranslate()
        assertNull(c.features.translateAsk)
    }
}
