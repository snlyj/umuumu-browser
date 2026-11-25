package com.yuka.cutebrowser

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        CrashLog.file(ctx).delete()
    }

    @Test
    fun writeAndReadRoundTrip() {
        assertNull(CrashLog.read(ctx))
        assertFalse(CrashLog.copyToClipboard(ctx))
        CrashLog.write(ctx, Thread.currentThread(), RuntimeException("boom-test"))
        val text = CrashLog.read(ctx)
        assertNotNull(text)
        assertTrue(text!!, text.contains("boom-test"))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("local only"))
        assertTrue(CrashLog.copyToClipboard(ctx))
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = cm.primaryClip?.getItemAt(0)?.text?.toString()
        assertNotNull(copied)
        assertTrue(copied!!.contains("boom-test"))
    }

    @Test
    fun writeSurvivesWhenCauseHasNoMessage() {
        CrashLog.write(ctx, Thread.currentThread(), OutOfMemoryError())
        val text = CrashLog.read(ctx)!!
        assertTrue(text.contains("OutOfMemoryError"))
    }
}
