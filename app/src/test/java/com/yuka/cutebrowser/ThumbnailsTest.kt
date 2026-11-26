package com.yuka.cutebrowser

import com.yuka.cutebrowser.perf.Thumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailsTest {
    private val ratio = Thumbnails.WIDTH.toDouble() / Thumbnails.HEIGHT

    @Test
    fun portraitViewCropsTheTopWithTheThumbnailAspect() {
        val r = Thumbnails.cropRect(1080, 1800)!!
        assertEquals(0, r.left); assertEquals(0, r.top); assertEquals(1080, r.width)
        assertEquals(1080.0 / r.height, ratio, 0.01)
        assertTrue(r.bottom <= 1800)
    }

    @Test
    fun landscapeViewCropsACenteredBlockOfFullHeight() {
        val r = Thumbnails.cropRect(2400, 900)!!
        assertEquals(900, r.height); assertEquals(0, r.top)
        assertEquals(r.width.toDouble() / r.height, ratio, 0.01)
        assertEquals((2400 - r.width) / 2, r.left)
    }

    @Test
    fun veryShortViewStillStaysInsideTheView() {
        val r = Thumbnails.cropRect(1000, 200)!!
        assertTrue(r.left >= 0 && r.right <= 1000 && r.top == 0 && r.bottom == 200)
    }

    @Test
    fun emptyViewHasNoRect() {
        assertNull(Thumbnails.cropRect(0, 100)); assertNull(Thumbnails.cropRect(100, 0))
    }

    @Test
    fun thumbnailIsSmall() {
        // 240×160×4 ≈ 150 KB，16 张封顶约 2.4 MB
        val bytes = Thumbnails.WIDTH * Thumbnails.HEIGHT * 4 * Thumbnails.MAX_KEPT
        assertTrue("thumbs too big: $bytes", bytes < 3 * 1024 * 1024)
    }
}
