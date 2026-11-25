package com.yuka.cutebrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun badEntriesAreSkippedNotFatal() {
        val repo = CuteRepository(ctx)
        // 中间一条不是对象、一条没有 url：只丢这两条，其余照常
        val list = repo.decode("""[{"u":"https://a.com","t":"A","d":1},42,{"t":"no url"},{"u":"https://b.com","t":"B","d":2}]""")
        assertEquals(listOf("https://a.com", "https://b.com"), list.map { it.url })
        assertTrue(repo.decode("{broken").isEmpty())
        assertTrue(repo.decode(null).isEmpty())
        assertEquals(setOf("a.com"), repo.decodeSet("""["a.com",""]"""))
        assertTrue(repo.decodePerms("""[{"o":"https://x.com","p":"NOPE","a":true},7]""").isEmpty())
        assertEquals(1, repo.decodeLocal("""[{"u":"content://x","t":"f","m":"a/b","s":3,"d":4},"junk"]""").size)
    }

    @Test
    fun securityPrefsRoundTrip() {
        val repo = CuteRepository(ctx)
        val s = SecurityPrefs(httpsOnly = false, appLock = true, tzOn = true, tz = "Asia/Tokyo", lang = "ja-JP")
        assertEquals(s, repo.decodeSec(repo.encodeSec(s)))
        assertEquals(SecurityPrefs(), repo.decodeSec("not json"))
    }

    @Test
    fun corruptedDataStoreFileFallsBackToDefaults() = runBlocking {
        // 先写入一个乱码文件，再创建仓库：读设置不应抛异常，而是回到默认值
        val dir = File(ctx.filesDir, "datastore").apply { mkdirs() }
        File(dir, "cute_prefs.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x55, 0x66, 0x77, 0x13, 0x37))
        File(dir, "cute_history.preferences_pb").writeBytes(byteArrayOf(9, 9, 9, 9, 9))
        val repo = CuteRepository(ctx)
        assertEquals(CuteThemeId.STRAWBERRY, repo.theme.first())
        assertEquals(SecurityPrefs(), repo.security.first())
        assertTrue(repo.history.first().isEmpty())
        // 之后还能正常写、正常读
        repo.setTheme(CuteThemeId.STARRY)
        assertEquals(CuteThemeId.STARRY, repo.theme.first())
        repo.addHistory("https://a.com", "A")
        assertEquals("https://a.com", repo.history.first().first().url)
        assertFalse(repo.bookmarks.first().isNotEmpty())
    }
}
