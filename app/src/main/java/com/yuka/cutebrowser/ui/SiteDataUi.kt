package com.yuka.cutebrowser.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.TabState
import com.yuka.cutebrowser.privacy.fingerprint.FpSeed
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/** 盾牌菜单最下面的「清除本站数据」（v1.7） */
@Composable
internal fun ClearSiteDataRow(tab: TabState, host: String, controller: BrowserController, onDone: () -> Unit) {
    val p = LocalCutePalette.current
    var confirm by remember { mutableStateOf(false) }
    val site = FpSeed.site(host).ifEmpty { host }
    Spacer(Modifier.height(10.dp))
    SettingAction("清除本站数据", "🧽", p.lavender) { confirm = true }
    if (confirm) {
        CuteConfirmDialog(
            emoji = "🧽",
            title = "清除 $site 的数据？",
            message = if (tab.incognito) "会删掉这次隐身里 $site 的 Cookie 和网站存储，以及在隐身里改过的网站设置。"
            else "会删掉 $site（包括子域名）的 Cookie、网站存储、记住的权限和例外设置。之后可能需要重新登录哦。",
            confirm = "清除",
            onConfirm = {
                confirm = false
                controller.clearSiteData(tab, host)
                onDone()
            },
            onDismiss = { confirm = false },
        )
    }
}
