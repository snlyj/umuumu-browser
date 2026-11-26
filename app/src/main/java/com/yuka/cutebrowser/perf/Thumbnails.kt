package com.yuka.cutebrowser.perf

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager

/**
 * 标签页缩略图：用 PixelCopy 从窗口当前画面里截 WebView 区域的上半部分，由 GPU/渲染线程直接缩小到很小的位图
 * （[WIDTH]×[HEIGHT] 像素，约 150 KB），主线程只做一次分配和一次回调，不会像 View.draw() 那样在主线程上整页重绘。
 * API 26+；更老的系统、有 FLAG_SECURE 的窗口、拿不到有效画面时都返回 null（调用方显示 emoji 占位）。
 */
object Thumbnails {
    const val WIDTH = 240
    const val HEIGHT = 160
    /** 每个标签只保留最近用过的这么多张，其余释放（约 2.4 MB 封顶） */
    const val MAX_KEPT = 16

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 截取 [view]（WebView）可见区域的上部。[onDone] 在主线程回调；失败给 null */
    fun capture(activity: Activity, view: View, onDone: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26) { onDone(null); return }
        if (!view.isShown || view.width < 32 || view.height < 32) { onDone(null); return }
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) { onDone(null); return }
        val src = sourceRect(view) ?: run { onDone(null); return }
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val rect = Rect(loc[0] + src.left, loc[1] + src.top, loc[0] + src.right, loc[1] + src.bottom)
        val bmp = runCatching { Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bmp == null) { onDone(null); return }
        runCatching {
            PixelCopy.request(activity.window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) onDone(bmp) else { bmp.recycle(); onDone(null) }
            }, main)
        }.onFailure { bmp.recycle(); onDone(null) }
    }

    /** 视图内的一块区域（相对视图左上角） */
    internal data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** 取视图里和缩略图同一长宽比的上部区域 */
    internal fun sourceRect(view: View): Crop? = cropRect(view.width, view.height)

    internal fun cropRect(w: Int, h: Int): Crop? {
        if (w <= 0 || h <= 0) return null
        val wantH = (w.toLong() * HEIGHT / WIDTH).toInt()
        return if (wantH <= h) Crop(0, 0, w, wantH)
        else { // 视图比 3:2 还扁（横屏）：取全高，宽度按比例居中裁一块
            val cw = (h.toLong() * WIDTH / HEIGHT).toInt()
            val left = (w - cw) / 2
            Crop(left, 0, left + cw, h)
        }
    }
}
