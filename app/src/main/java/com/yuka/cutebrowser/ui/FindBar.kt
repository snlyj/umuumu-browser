package com.yuka.cutebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuka.cutebrowser.features.FindState
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/**
 * 页面内查找栏：查找时替换地址栏。圆润药丸输入框 + 「3/12」计数 + 上一个/下一个 + 关闭。
 * 键盘上的「搜索」键 = 下一个。
 */
@Composable
fun FindBar(
    state: FindState,
    onQuery: (String) -> Unit,
    onNext: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
    autoFocus: Boolean = true,
) {
    val p = LocalCutePalette.current
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .shadow(6.dp, RoundedCornerShape(50), ambientColor = p.accent, spotColor = p.accent)
                .clip(RoundedCornerShape(50))
                .background(p.surface)
                .border(2.dp, p.accent, RoundedCornerShape(50))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FeatureIcons.FindInPage, null, tint = p.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) Text("在页面里找找… 🔍", color = p.subText, fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    value = state.query,
                    onValueChange = { onQuery(it.replace('\n', ' ')) },
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "页面内查找输入框" },
                )
            }
            val count = state.countText
            if (count.isNotEmpty()) {
                Text(
                    count,
                    color = if (count == "没找到") p.accentInk else p.subText,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        val hasMatches = state.total > 0
        JellyButton(onClick = { onNext(false) }, enabled = hasMatches, size = 38.dp, background = p.pink) {
            Icon(FeatureIcons.ChevronUp, "上一个", tint = p.text, modifier = Modifier.size(20.dp))
        }
        JellyButton(onClick = { onNext(true) }, enabled = hasMatches, size = 38.dp, background = p.mint) {
            Icon(FeatureIcons.ChevronDown, "下一个", tint = p.text, modifier = Modifier.size(20.dp))
        }
        JellyButton(onClick = onClose, size = 38.dp, background = p.lavender) {
            Icon(CuteIcons.Close, "关闭查找", tint = p.text, modifier = Modifier.size(18.dp))
        }
    }
}
