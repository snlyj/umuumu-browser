package com.yuka.cutebrowser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义首页壁纸：把用户从相册选的图复制进 App 自己的目录（filesDir），同时缩小、重新编码成 JPEG
 * （顺便去掉照片里的位置等 EXIF 信息）。之后不再依赖原图，用户删了相册里的图也不影响。
 * 所有函数都是阻塞的，调用方要放到 IO 线程。
 */
object WallpaperStore {
    /** 壁纸最长边（像素）。手机屏幕再大也用不着更大的图，更大只会占内存 */
    const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 85
    private const val PREFIX = "wallpaper_"

    fun file(ctx: Context, stamp: Long) = File(ctx.filesDir, "$PREFIX$stamp.jpg")

    /** 只缩小不放大：最长边不超过 [maxSide] */
    internal fun fitSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide || w <= 0 || h <= 0) return w to h
        val s = maxSide.toFloat() / longest
        return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
    }

    /** 解码时用的采样倍数：2 的幂，并且解出来的最长边仍然 ≥ [target]，所以不会比目标糊 */
    internal fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        val longest = max(w, h)
        while (longest / (s * 2) >= target) s *= 2
        return s
    }

    /** 复制 + 缩小 + 保存。成功返回新文件的版本号（时间戳），失败返回 null（原来的壁纸不动） */
    fun import(ctx: Context, uri: Uri): Long? = try {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) null else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(sw, sh, MAX_SIDE) }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null) null else {
                val (tw, th) = fitSize(bmp.width, bmp.height)
                if (tw != bmp.width || th != bmp.height) {
                    val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                val rotation = runCatching {
                    resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
                }.getOrNull() ?: 1
                bmp = rotated(bmp!!, rotation)
                val stamp = System.currentTimeMillis()
                val tmp = File(ctx.filesDir, "$PREFIX$stamp.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                bmp.recycle()
                val dest = file(ctx, stamp)
                if (tmp.renameTo(dest)) { deleteOthers(ctx, stamp); stamp } else { tmp.delete(); null }
            }
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    private fun rotated(b: Bitmap, exif: Int): Bitmap {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return runCatching { Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true) }
            .getOrNull()?.also { if (it !== b) b.recycle() } ?: b
    }

    /** 读已保存的壁纸；[maxSide] 用来给设置里的小缩略图省内存。文件没了/坏了返回 null */
    fun load(ctx: Context, stamp: Long, maxSide: Int = MAX_SIDE): ImageBitmap? = try {
        val f = file(ctx, stamp)
        if (!f.exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) null else {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
                BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
            }
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    /** 删掉除了 [keep] 以外的旧壁纸文件；keep 为 null 就全删 */
    fun deleteOthers(ctx: Context, keep: Long?) {
        ctx.filesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(PREFIX) && (keep == null || f.name != "$PREFIX$keep.jpg")) f.delete()
        }
    }
}
