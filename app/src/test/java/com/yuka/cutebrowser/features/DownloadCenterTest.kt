package com.yuka.cutebrowser.features

import com.yuka.cutebrowser.browser.DlStatus
import com.yuka.cutebrowser.browser.DownloadItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCenterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: Long, status: DlStatus, time: Long = id, title: String = "f$id.zip") =
        DownloadItem("dm:$id", id, null, title, "application/zip", status, 10, 100, time)

    private fun rec(id: Long, url: String = "https://example.com/f$id.zip", time: Long = id) =
        DownloadRecord(id, url, "f$id.zip", "application/zip", "UA", time)

    @Test
    fun encodeDecodeSkipsBrokenEntries() {
        val list = listOf(rec(1), rec(2))
        assertEquals(list, DownloadRecords.decode(DownloadRecords.encode(list)))
        val broken = """[{"id":1,"url":"https://a/x","title":"x","time":5},{"nope":true},"str",{"id":3,"url":"https://b/y","time":6}]"""
        val d = DownloadRecords.decode(broken)
        assertEquals(listOf(1L, 3L), d.map { it.dmId })
        assertEquals("未命名文件", d[1].title)
        assertTrue(DownloadRecords.decode("not json").isEmpty())
    }

    @Test
    fun mergeKeepsMissingRecordsAndSortsByTime() {
        val rows = DownloadRecords.merge(
            items = listOf(item(1, DlStatus.DONE, time = 100), item(2, DlStatus.FAILED, time = 300), item(5, DlStatus.RUNNING, time = 400)),
            records = listOf(rec(2), rec(9, time = 200), rec(1)),
        )
        assertEquals(listOf("dm:5", "dm:2", "r:9:200", "dm:1"), rows.map { it.key })
        val failed = rows.first { it.item?.dmId == 2L }
        assertTrue(failed.canRetry)
        assertFalse(failed.canShare)
        val missing = rows.first { it.missing }
        assertTrue(missing.canRetry)
        val done = rows.first { it.item?.dmId == 1L }
        assertTrue(done.canShare)
        assertFalse(done.canRetry)
        val running = rows.first { it.item?.dmId == 5L }
        assertTrue(running.active)
        assertFalse("没有记录的（比如隐身下载）不能重试", running.canRetry)
    }

    @Test
    fun recordsStoreAddReplaceRemoveAndCap() = runBlocking {
        val dir = tmp.newFolder("downloads")
        val r = DownloadRecords(dir)
        r.add(rec(1)); r.add(rec(2))
        assertEquals(listOf(2L, 1L), r.all().map { it.dmId })
        r.replace(1, rec(1).copy(dmId = 7))
        assertEquals(listOf(7L, 2L), r.all().map { it.dmId })
        r.remove(2)
        // 新实例从文件读
        assertEquals(listOf(7L), DownloadRecords(dir).all().map { it.dmId })
        repeat(DownloadRecords.MAX + 20) { r.add(rec(1000L + it)) }
        assertEquals(DownloadRecords.MAX, DownloadRecords(dir).all().size)
    }

    @Test
    fun nonWebUrlsCannotBeRetried() {
        val row = DownloadRow(null, rec(1, url = "javascript:alert(1)"))
        assertFalse(row.canRetry)
    }
}
