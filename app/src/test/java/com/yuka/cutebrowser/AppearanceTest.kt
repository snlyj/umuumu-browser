package com.yuka.cutebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yuka.cutebrowser.data.AppearancePrefs
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.Mascot
import com.yuka.cutebrowser.data.ProgressStyle
import com.yuka.cutebrowser.data.Wallpaper
import com.yuka.cutebrowser.data.WallpaperStore
import com.yuka.cutebrowser.data.decodeAppearance
import com.yuka.cutebrowser.data.encodeAppearance
import com.yuka.cutebrowser.ui.blinkScale
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import com.yuka.cutebrowser.ui.theme.NightTheme
import com.yuka.cutebrowser.ui.theme.ThemeOrder
import com.yuka.cutebrowser.ui.theme.isMotionReduced
import com.yuka.cutebrowser.ui.theme.paletteOf
import com.yuka.cutebrowser.ui.theme.resolveTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    // ---------------- 设置的编码 ----------------

    @Test
    fun defaultsLookLikeV16() {
        val d = AppearancePrefs()
        assertEquals(Wallpaper.NONE, d.wallpaper)
        assertEquals(Mascot.BUNNY, d.mascot)
        assertEquals(ProgressStyle.BUNNY, d.progress)
        assertFalse(d.followSystem); assertFalse(d.reduceMotion)
        // 震动和声音默认关
        assertFalse(d.haptics); assertFalse(d.sound)
    }

    @Test
    fun roundTrip() {
        val a = AppearancePrefs(
            followSystem = true, wallpaper = Wallpaper.CUSTOM, wallpaperStamp = 1234L, scrim = 0.8f,
            mascot = Mascot.CAT, progress = ProgressStyle.RIBBON, reduceMotion = true, haptics = true, sound = true,
        )
        assertEquals(a, decodeAppearance(encodeAppearance(a)))
    }

    @Test
    fun garbageFallsBackToDefaults() {
        assertEquals(AppearancePrefs(), decodeAppearance(null))
        assertEquals(AppearancePrefs(), decodeAppearance(""))
        assertEquals(AppearancePrefs(), decodeAppearance("{not json"))
        // 未知的枚举名（比如以后版本删掉的壁纸）→ 用默认值，其余字段照常
        val x = decodeAppearance("""{"wp":"GONE","mascot":"DRAGON","prog":"X","rm":true}""")
        assertEquals(Wallpaper.NONE, x.wallpaper); assertEquals(Mascot.BUNNY, x.mascot); assertTrue(x.reduceMotion)
        // 自定义壁纸但没有文件版本号 → 当作没有壁纸；遮罩被夹在合法范围里
        assertEquals(Wallpaper.NONE, decodeAppearance("""{"wp":"CUSTOM"}""").wallpaper)
        assertEquals(AppearancePrefs.MAX_SCRIM, decodeAppearance("""{"scrim":5.0}""").scrim, 0f)
        assertEquals(AppearancePrefs.MIN_SCRIM, decodeAppearance("""{"scrim":-1}""").scrim, 0f)
    }

    @Test
    fun repositoryPersistsAppearance() = runBlocking {
        val repo = CuteRepository(app)
        assertEquals(AppearancePrefs(), repo.appearance.first())
        repo.updateAppearance { it.copy(mascot = Mascot.RIBBON, haptics = true) }
        repo.updateAppearance { it.copy(followSystem = true) }
        val r = repo.appearance.first()
        assertEquals(Mascot.RIBBON, r.mascot); assertTrue(r.haptics); assertTrue(r.followSystem)
        // 旧版本存的主题名仍然能读（枚举名没改）
        repo.setTheme(CuteThemeId.STARRY)
        assertEquals(CuteThemeId.STARRY, repo.theme.first())
    }

    // ---------------- 主题 ----------------

    @Test
    fun resolveThemeFollowsSystem() {
        // 不跟随系统：永远是用户选的
        assertEquals(CuteThemeId.MINT, resolveTheme(CuteThemeId.MINT, false, true))
        assertEquals(CuteThemeId.STARRY, resolveTheme(CuteThemeId.STARRY, false, false))
        // 跟随系统：深色 → 夜间；浅色 → 选的浅色主题
        assertEquals(NightTheme, resolveTheme(CuteThemeId.PEACH, true, true))
        assertEquals(CuteThemeId.PEACH, resolveTheme(CuteThemeId.PEACH, true, false))
        // 选的本来就是夜间主题，系统浅色时退回默认的草莓牛奶
        assertEquals(CuteThemeId.STRAWBERRY, resolveTheme(CuteThemeId.STARRY, true, false))
        assertTrue(paletteOf(NightTheme).isDark)
    }

    @Test
    fun themeListIsCompleteAndStable() {
        // 设置里显示的顺序必须包含每个主题一次；枚举名存在用户设置里，不能少
        assertEquals(CuteThemeId.entries.toSet(), ThemeOrder.toSet())
        assertEquals(ThemeOrder.size, ThemeOrder.toSet().size)
        for (old in listOf("STRAWBERRY", "SAKURA", "STARRY")) assertNotNull(CuteThemeId.entries.firstOrNull { it.name == old })
        assertEquals(7, CuteThemeId.entries.size)
    }

    // ---------------- 减少动画 ----------------

    @Test
    fun reduceMotionRules() {
        assertFalse(isMotionReduced(false, 1f))
        assertFalse(isMotionReduced(false, 0.5f))
        assertTrue(isMotionReduced(true, 1f))          // 应用内开关
        assertTrue(isMotionReduced(false, 0f))         // 系统 ANIMATOR_DURATION_SCALE = 0
        assertTrue(isMotionReduced(true, 0f))
    }

    @Test
    fun blinkIsBriefAndClosesThenOpens() {
        assertEquals(1f, blinkScale(0f), 0f)
        assertEquals(1f, blinkScale(0.5f), 0f)
        assertEquals(1f, blinkScale(0.99f), 0f)
        assertTrue(blinkScale(0.935f) < 0.2f)           // 中间闭眼
        assertTrue(blinkScale(0.91f) in 0.2f..1f)
    }

    // ---------------- 壁纸文件 ----------------

    @Test
    fun fitAndSampleMath() {
        assertEquals(1000 to 800, WallpaperStore.fitSize(1000, 800))                 // 不放大
        assertEquals(2048 to 1536, WallpaperStore.fitSize(4000, 3000))               // 最长边 2048
        assertEquals(1536 to 2048, WallpaperStore.fitSize(3000, 4000))
        val s = WallpaperStore.sampleSize(8000, 6000, 2048)
        assertEquals(2, s)                                                          // 8000/4=2000 < 2048 → 只能 2
        assertEquals(1, WallpaperStore.sampleSize(1000, 800, 2048))
        assertTrue(8000 / s >= 2048)
    }

    private fun writeImage(w: Int, h: Int): File {
        val f = File(app.cacheDir, "pick-${System.nanoTime()}.png")
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFFFF8FAB.toInt())
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }

    @Test
    fun importDownscalesCopiesAndReplacesOld() {
        val big = writeImage(3200, 2400)
        val stamp1 = WallpaperStore.import(app, Uri.fromFile(big))
        assertNotNull(stamp1)
        val saved = WallpaperStore.file(app, stamp1!!)
        assertTrue(saved.exists())
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(saved.path, o)
        assertEquals(2048, maxOf(o.outWidth, o.outHeight))
        assertTrue("缩小后体积应该比原图小", saved.length() < big.length() * 4)
        // 复制进了 App 目录：原图删了也不影响
        big.delete()
        assertNotNull(WallpaperStore.load(app, stamp1))

        Thread.sleep(5) // 时间戳不同
        val stamp2 = WallpaperStore.import(app, Uri.fromFile(writeImage(400, 300)))
        assertNotNull(stamp2)
        assertFalse("换了新壁纸，旧文件要删掉", WallpaperStore.file(app, stamp1).exists())
        // 小缩略图读取
        val thumb = WallpaperStore.load(app, stamp2!!, 128)
        assertNotNull(thumb)

        WallpaperStore.deleteOthers(app, null)
        assertFalse(WallpaperStore.file(app, stamp2).exists())
        assertNull(WallpaperStore.load(app, stamp2))
    }

    @Test
    fun importRejectsNonImages() {
        val junk = File(app.cacheDir, "junk.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        assertNull(WallpaperStore.import(app, Uri.fromFile(junk)))
        assertNull(WallpaperStore.import(app, Uri.fromFile(File(app.cacheDir, "missing.png"))))
    }
}
