package com.yuka.cutebrowser.features

import org.junit.Assert.assertEquals
import org.junit.Test

class FindStateTest {
    @Test
    fun countText() {
        val s = FindState()
        assertEquals("", s.countText)
        s.query = "a"; s.counting = true
        assertEquals("…", s.countText)
        s.counting = false
        assertEquals("没找到", s.countText)
        s.total = 5; s.active = 0
        assertEquals("1/5", s.countText)
        s.active = 4
        assertEquals("5/5", s.countText)
    }
}
