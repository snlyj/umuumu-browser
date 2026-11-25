package com.yuka.cutebrowser

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun badEntriesAreSkippedNotFatal() {
        val repo = CuteRepository(app)
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
        val repo = CuteRepository(app)
        val s = SecurityPrefs(httpsOnly = false, appLock = true, tzOn = true, tz = "Asia/Tokyo", lang = "ja-JP")
        assertEquals(s, repo.decodeSec(repo.encodeSec(s)))
        assertEquals(SecurityPrefs(), repo.decodeSec("not json"))
    }

    @Test
    fun corruptedDataStoreFileFallsBackToDefaults() = runBlocking {
        // CuteRepository 里的 DataStore 是进程级单例（按文件名复用），其它测试一旦打开过，
        // 再往共享文件里写乱码也测不到“从坏文件启动”。这里用同一套 ReplaceFileCorruptionHandler
        // 和一份独立文件，确认损坏时回到空设置，并且之后还能写。
        val file = File(app.cacheDir, "corrupt-${System.nanoTime()}.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x55, 0x66, 0x77, 0x13, 0x37))
        val ds = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { file },
        )
        assertTrue(ds.data.first().asMap().isEmpty())
        val key = stringPreferencesKey("theme")
        ds.edit { it[key] = CuteThemeId.STARRY.name }
        assertEquals(CuteThemeId.STARRY.name, ds.data.first()[key])
    }
}
