package com.yuka.cutebrowser.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 自己画的圆润线条小图标（24x24 视口） */
object CuteIcons {
    private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList(), width: Float = 2.2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        fills.forEach { b.addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        strokes.forEach {
            b.addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val HEART = "M12 20.5s-7.6-4.6-9.2-9.3C1.7 7.9 3.9 4.5 7.3 4.5c2 0 3.6 1.1 4.7 2.8 1.1-1.7 2.7-2.8 4.7-2.8 3.4 0 5.6 3.4 4.5 6.7-1.6 4.7-9.2 9.3-9.2 9.3z"
    private const val STAR = "M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.6l-5.1 2.7 1-5.7-4.1-4 5.7-.8z"

    /** 隐身用的小眼罩 */
    val Mask = icon(
        "mask",
        listOf("M3 9.5c3-1.6 6-1.6 9 0 3-1.6 6-1.6 9 0-.3 4-2.6 6-5 6-2 0-3-1.3-4-2.6-1 1.3-2 2.6-4 2.6-2.4 0-4.7-2-5-6z"),
        fills = listOf("M7.6 11.2a1.4 1.1 0 1 0 0 .01z", "M16.4 11.2a1.4 1.1 0 1 0 0 .01z"),
    )

    val Back = icon("back", listOf("M14.5 5.5L8 12l6.5 6.5"))
    val Forward = icon("forward", listOf("M9.5 5.5L16 12l-6.5 6.5"))
    val Refresh = icon("refresh", listOf("M19 12a7 7 0 1 1-2.1-5", "M19.2 4.2v3.6h-3.6"))
    val Close = icon("close", listOf("M7 7l10 10", "M17 7L7 17"))
    val Plus = icon("plus", listOf("M12 5.5v13", "M5.5 12h13"))
    val Search = icon("search", listOf("M10.5 4.5a6 6 0 1 0 0 12a6 6 0 1 0 0-12z", "M15.2 15.2l4.3 4.3"))
    val Home = icon(
        "home",
        listOf("M4 11L12 4.5l8 6.5v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z"),
        fills = listOf("M12 17.6s-2.9-1.7-2.9-3.6c0-1 .7-1.7 1.6-1.7.6 0 1 .3 1.3.8.3-.5.7-.8 1.3-.8.9 0 1.6.7 1.6 1.7 0 1.9-2.9 3.6-2.9 3.6z"),
    )
    val Heart = icon("heart", listOf(HEART))
    val HeartFilled = icon("heartFilled", emptyList(), fills = listOf(HEART))
    val Star = icon("star", listOf(STAR))
    val StarFilled = icon("starFilled", emptyList(), fills = listOf(STAR))
    val Bow = icon(
        "bow",
        listOf(
            "M10.6 12L4.8 8.4C3.7 7.7 2.5 8.4 2.5 9.7v4.6c0 1.3 1.2 2 2.3 1.3z",
            "M13.4 12l5.8-3.6c1.1-.7 2.3 0 2.3 1.3v4.6c0 1.3-1.2 2-2.3 1.3z",
            "M10.8 13.6l-1.6 5.4", "M13.2 13.6l1.6 5.4",
        ),
        fills = listOf("M12 10.1a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 1 0 0-3.8z"),
        width = 2f,
    )
    val Clock = icon("clock", listOf("M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M12 8v4.4l2.9 1.8"))
    val Download = icon("download", listOf("M12 4.5v10", "M7.8 10.8L12 15l4.2-4.2", "M5 19.5h14"))
    val Trash = icon("trash", listOf("M4.5 7h15", "M9.5 7V5h5v2", "M6.8 7l.9 11.6a1.6 1.6 0 0 0 1.6 1.4h5.4a1.6 1.6 0 0 0 1.6-1.4L17.2 7"))
    val Share = icon("share", listOf("M12 14.5V4.5", "M8.2 8.2L12 4.4l3.8 3.8", "M6 11.5v6.5a1.5 1.5 0 0 0 1.5 1.5h9a1.5 1.5 0 0 0 1.5-1.5v-6.5"))
    val Flower = icon(
        "flower",
        listOf(
            "M12 2.8a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M18.4 7.4a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M15.9 14.9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M8.1 14.9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M5.6 7.4a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        ),
        fills = listOf("M12 10a2.2 2.2 0 1 0 0 4.4a2.2 2.2 0 1 0 0-4.4z"),
        width = 1.8f,
    )
    val Sparkle = icon("sparkle", emptyList(), fills = listOf("M12 3l1.9 5.6L19.5 10.5l-5.6 1.9L12 18l-1.9-5.6L4.5 10.5l5.6-1.9z"))
}
