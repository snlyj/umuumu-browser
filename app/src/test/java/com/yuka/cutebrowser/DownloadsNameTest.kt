package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsNameTest {
    @Test
    fun stripsPathsAndReservedCharacters() {
        assertEquals("passwd", Downloads.sanitizeFileName("../../etc/passwd").substringAfterLast('_'))
        assertFalse(Downloads.sanitizeFileName("../../etc/passwd").contains('/'))
        assertFalse(Downloads.sanitizeFileName("..\\..\\x.txt").contains('\\'))
        assertEquals("a_b_c.txt", Downloads.sanitizeFileName("a:b*c.txt"))
        assertEquals("a_b.txt", Downloads.sanitizeFileName("a\u0000b.txt"))
        assertEquals("download", Downloads.sanitizeFileName(".."))
        assertEquals("download", Downloads.sanitizeFileName("   "))
        assertEquals("hidden", Downloads.sanitizeFileName(".hidden"))
        assertEquals("report.pdf", Downloads.sanitizeFileName(" report.pdf. "))
    }

    @Test
    fun removesBidiOverrides() {
        // "photo\u202Egpj.exe" 在界面里会显示成 photoexe.jpg
        assertEquals("photogpj.exe", Downloads.sanitizeFileName("photo\u202Egpj.exe"))
    }

    @Test
    fun capsLengthKeepingExtension() {
        val n = Downloads.sanitizeFileName("x".repeat(500) + ".mp4")
        assertEquals(120, n.length)
        assertTrue(n.endsWith(".mp4"))
        assertEquals("你好.txt", Downloads.sanitizeFileName("你好.txt"))
    }

    @Test
    fun blobJsonLimitMatchesBase64Overhead() {
        assertTrue(Downloads.MAX_BLOB_JSON_CHARS > Downloads.MAX_BLOB_BYTES * 4 / 3)
        assertTrue(Downloads.MAX_BLOB_JSON_CHARS < Downloads.MAX_BLOB_BYTES * 2)
    }
}
