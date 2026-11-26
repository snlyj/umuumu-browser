package com.yuka.cutebrowser.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** v1.7 常用功能用到的小图标（和 CuteIcons 同一种圆润线条，24x24 视口；单独放一个文件方便合并） */
object FeatureIcons {
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

    /** 放大镜 + 小星星：页面内查找 */
    val FindInPage = icon(
        "findInPage",
        listOf("M10 5a5 5 0 1 0 0 10a5 5 0 1 0 0-10z", "M13.8 13.8l5.2 5.2"),
        fills = listOf("M18 3.2l.7 1.6 1.6.7-1.6.7-.7 1.6-.7-1.6-1.6-.7 1.6-.7z"),
    )
    val ChevronUp = icon("chevronUp", listOf("M6.5 14.5L12 9l5.5 5.5"))
    val ChevronDown = icon("chevronDown", listOf("M6.5 9.5L12 15l5.5-5.5"))

    /** 打开的书：阅读模式 */
    val Book = icon(
        "book",
        listOf(
            "M12 7.2C10.2 5.6 7.6 5 4.5 5.2v12.4c3.1-.2 5.7.4 7.5 2 1.8-1.6 4.4-2.2 7.5-2V5.2C16.4 5 13.8 5.6 12 7.2z",
            "M12 7.2v12.2",
        ),
        width = 2f,
    )
    /** 文 A：翻译 */
    val Translate = icon(
        "translate",
        listOf("M4 6h8", "M8 4.5V6", "M10.5 6c-.6 3.3-2.6 6-5.5 7.5", "M6.5 9c1 1.8 2.6 3.2 4.5 4", "M12.5 20l3.5-8.5 3.5 8.5", "M13.7 17.2h4.6"),
        width = 2f,
    )
    /** 电脑屏幕：桌面版网站 */
    val Desktop = icon(
        "desktop",
        listOf("M5 5h14a1.5 1.5 0 0 1 1.5 1.5v8A1.5 1.5 0 0 1 19 16H5a1.5 1.5 0 0 1-1.5-1.5v-8A1.5 1.5 0 0 1 5 5z", "M9.5 19.5h5", "M12 16v3.5"),
        width = 2f,
    )
    /** 小房子加号：添加到主屏幕 */
    val AddHome = icon(
        "addHome",
        listOf("M4.5 11L12 4.5 19.5 11", "M6.5 9.5V19h6", "M17.5 13.5v6", "M14.5 16.5h6"),
        width = 2f,
    )
    /** 刷新箭头（重试下载） */
    val Retry = icon("retry", listOf("M18.5 12a6.5 6.5 0 1 1-1.9-4.6", "M18.8 4.6v3.6h-3.6"))
    /** 文字大小 */
    val TextSize = icon("textSize", listOf("M3.5 18L8 6l4.5 12", "M5.2 14h5.6", "M14 18l3-7.5 3 7.5", "M15.1 15.6h3.8"), width = 2f)
    /** 行距 */
    val LineHeight = icon("lineHeight", listOf("M10 6.5h10", "M10 12h10", "M10 17.5h10", "M5.5 5v14", "M3.5 7l2-2 2 2", "M3.5 17l2 2 2-2"), width = 2f)
}
