package com.yuka.cutebrowser

import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun parsesInput() {
        assertEquals("https://bilibili.com", UrlUtils.toUrl("bilibili.com", SearchEngine.BING))
        assertEquals("https://www.zhihu.com/question/1", UrlUtils.toUrl(" www.zhihu.com/question/1 ", SearchEngine.BING))
        assertEquals("http://example.com", UrlUtils.toUrl("http://example.com", SearchEngine.BING))
        assertEquals("https://192.168.1.1:8080", UrlUtils.toUrl("192.168.1.1:8080", SearchEngine.BING))
        assertEquals("https://www.baidu.com/s?wd=%E5%B0%8F%E7%8C%AB", UrlUtils.toUrl("小猫", SearchEngine.BAIDU))
        assertEquals("https://cn.bing.com/search?q=cute+cat", UrlUtils.toUrl("cute cat", SearchEngine.BING))
        assertEquals("https://www.google.com/search?q=kotlin", UrlUtils.toUrl("kotlin", SearchEngine.GOOGLE))
    }
}
