package com.yuka.cutebrowser.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.yuka.cutebrowser.browser.ClipboardUrl
import com.yuka.cutebrowser.browser.UrlUtils
import com.yuka.cutebrowser.ui.theme.LocalCutePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 只在界面处于前台时读剪贴板：后台读在 Android 10+ 会抛 SecurityException。
 * 读和解析都在 IO 线程，主线程只收结果；任何失败都当成「没有链接」。
 */
@Composable
fun rememberCopiedLink(): String? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, context) {
        val app = context.applicationContext
        val cm = runCatching { app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }.getOrNull()
        if (cm == null) {
            onDispose { }
        } else {
            var watching = false
            fun refresh() {
                if (!watching) return
                scope.launch {
                    val url = withContext(Dispatchers.IO) {
                        runCatching { ClipboardUrl.extractOpenable(ClipboardUrl.read(cm, app)) }.getOrNull()
                    }
                    if (watching) link = url
                }
            }
            val listener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
            val obs = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        watching = true
                        runCatching { cm.addPrimaryClipChangedListener(listener) }
                        refresh()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        watching = false
                        runCatching { cm.removePrimaryClipChangedListener(listener) }
                        link = null
                    }
                    else -> {}
                }
            }
            lifecycle.addObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                watching = true
                runCatching { cm.addPrimaryClipChangedListener(listener) }
                refresh()
            }
            onDispose {
                watching = false
                lifecycle.removeObserver(obs)
                runCatching { cm.removePrimaryClipChangedListener(listener) }
            }
        }
    }
    return link
}

@Composable
fun CopiedLinkBanner(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val url = rememberCopiedLink() ?: return
    val p = LocalCutePalette.current
    val host = UrlUtils.host(url)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 0.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(p.mint)
            .bouncyClick { onOpen(url) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📋", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("打开复制的链接", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(host.ifBlank { url }, color = p.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(4.dp))
        Text("去看看 →", color = p.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
