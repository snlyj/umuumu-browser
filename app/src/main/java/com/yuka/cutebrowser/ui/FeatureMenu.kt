package com.yuka.cutebrowser.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.yuka.cutebrowser.browser.BrowserController

/** 菜单里的一格「小工具」。checked != null 时是开关（开着会高亮） */
@Immutable
data class ToolTile(val label: String, val icon: ImageVector, val checked: Boolean? = null, val action: () -> Unit)

/** v1.7 常用功能在唔姆菜单里的入口（追加在原来几格后面） */
fun featureTools(controller: BrowserController, close: () -> Unit): List<ToolTile> {
    val f = controller.features
    return listOf(
        ToolTile("页内查找", FeatureIcons.FindInPage) { close(); f.openFind() },
        ToolTile("阅读模式", FeatureIcons.Book) { close(); f.openReader() },
        ToolTile("翻译网页", FeatureIcons.Translate) { close(); f.askTranslate() },
        ToolTile("桌面版网站", FeatureIcons.Desktop, checked = controller.current.desktop) { f.toggleDesktop(); close() },
    )
}
