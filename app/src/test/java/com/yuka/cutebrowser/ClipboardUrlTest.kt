package com.yuka.cutebrowser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.browser.AddressInput
import com.yuka.cutebrowser.browser.ClipboardUrl
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.data.SearchEngine
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
class ClipboardUrlTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cm: ClipboardManager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun set(clip: ClipData?) {
        if (clip == null) {
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } else cm.setPrimaryClip(clip)
    }

    @Test
    fun plainTextIsNotAUrl() {
        set(ClipData.newPlainText("t", "hello world"))
        val raw = ClipboardUrl.read(cm, ctx)
        assertEquals("hello world", raw)
        assertNull(ClipboardUrl.extractOpenable(raw))
    }

    @Test
    fun plainUrl() {
        set(ClipData.newPlainText("t", "https://jiehua.13-58-183-35.sslip.io/"))
        assertEquals(
            "https://jiehua.13-58-183-35.sslip.io/",
            ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, ctx)),
        )
    }

    @Test
    fun urlPlusNewlinePlusChinese() {
        val pasted = "https://jiehua.13-58-183-35.sslip.io/\n登录到结花的小站"
        set(ClipData.newPlainText("t", pasted))
        assertEquals(
            "https://jiehua.13-58-183-35.sslip.io/",
            ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, ctx)),
        )
    }

    @Test
    fun nullTextItemDoesNotCrash() {
        val item = ClipData.Item(null as CharSequence?)
        val clip = ClipData(ClipDescription("t", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), item)
        assertNull(ClipboardUrl.textFromClip(clip, ctx))
        assertNull(ClipboardUrl.extractOpenable(ClipboardUrl.textFromClip(clip, ctx)))
        set(clip)
        // 读的时候也不能崩
        ClipboardUrl.read(cm, ctx)
    }

    @Test
    fun uriOnlyClip() {
        val clip = ClipData.newRawUri("u", Uri.parse("https://example.com/from-uri"))
        set(clip)
        assertEquals("https://example.com/from-uri", ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, ctx)))
    }

    @Test
    fun htmlClipExtractsSingleUrl() {
        val html = """<a href="https://example.com/a">打开</a>"""
        val clip = ClipData.newHtmlText("h", "打开", html)
        set(clip)
        val raw = ClipboardUrl.read(cm, ctx)
        assertNotNull(raw)
        assertEquals("https://example.com/a", ClipboardUrl.extractOpenable(raw))
    }

    @Test
    fun veryLongTextDoesNotCrash() {
        val long = "https://example.com/path?" + "a".repeat(200_000)
        set(ClipData.newPlainText("t", long))
        val raw = ClipboardUrl.read(cm, ctx)
        assertNotNull(raw)
        assertTrue(raw!!.length <= ClipboardUrl.MAX_CHARS)
        assertEquals("https://example.com/path?" + "a".repeat(ClipboardUrl.MAX_CHARS - "https://example.com/path?".length), raw)
        // 截断后仍然以这个网址开头，extract 应能认出
        val url = ClipboardUrl.extractOpenable(raw)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://example.com/path?"))
    }

    @Test
    fun emptyClip() {
        set(ClipData.newPlainText("t", ""))
        assertTrue(ClipboardUrl.read(cm, ctx).isNullOrEmpty())
        assertNull(ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, ctx)))
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            cm.clearPrimaryClip()
            assertNull(ClipboardUrl.read(cm, ctx))
        }
    }

    @Test
    fun securityExceptionWhenReadingIsSwallowed() {
        assertNull(ClipboardUrl.read({ throw SecurityException("clipboard hidden") }, ctx))
    }

    @Test
    fun clipChangeListenerDoesNotCrashOnAnyPayload() {
        val seen = mutableListOf<String?>()
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            seen.add(runCatching { ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, ctx)) }.getOrNull())
        }
        cm.addPrimaryClipChangedListener(listener)
        try {
            set(ClipData.newPlainText("t", "just text"))
            set(ClipData.newPlainText("t", "https://example.com"))
            set(ClipData.newPlainText("t", "https://jiehua.13-58-183-35.sslip.io/\n登录"))
            set(ClipData.newRawUri("u", Uri.parse("https://example.org/u")))
            set(ClipData.newPlainText("t", "x".repeat(50_000)))
            set(ClipData.newPlainText("t", ""))
            set(ClipData(ClipDescription("t", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(null as CharSequence?)))
        } finally {
            cm.removePrimaryClipChangedListener(listener)
        }
        assertTrue("listener should have fired at least once", seen.isNotEmpty())
    }

    @Test
    fun addressBarSanitizeTurnsNewlinesIntoSpacesAndFixesSelection() {
        val pasted = "https://jiehua.13-58-183-35.sslip.io/\n登录到结花的小站"
        val v = AddressInput.sanitize(TextFieldValue(pasted, TextRange(pasted.length)))
        assertFalse(v.text.contains('\n'))
        assertTrue(v.text.contains("https://jiehua.13-58-183-35.sslip.io/"))
        assertEquals(v.text.length, v.selection.start)
        assertEquals(v.text.length, v.selection.end)
        // 提交时仍应打开网址，而不是整段搜索
        assertEquals(
            "https://jiehua.13-58-183-35.sslip.io/",
            UrlUtils.toUrl(v.text, SearchEngine.GOOGLE),
        )
    }

    @Test
    fun addressBarSanitizeDropsControlsAndCapsLength() {
        val dirty = "https://a.com/\u0000\tfoo\r\nbar" + "x".repeat(20_000)
        val v = AddressInput.sanitize(TextFieldValue(dirty, TextRange(0, dirty.length)))
        assertFalse(v.text.contains('\u0000'))
        assertFalse(v.text.contains('\n'))
        assertTrue(v.text.length <= AddressInput.MAX_CHARS)
        assertTrue(v.selection.start in 0..v.text.length)
        assertTrue(v.selection.end in 0..v.text.length)
    }
}
