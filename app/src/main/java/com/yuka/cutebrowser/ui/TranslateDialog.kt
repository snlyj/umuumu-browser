package com.yuka.cutebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yuka.cutebrowser.features.TranslateAsk
import com.yuka.cutebrowser.features.TranslateProvider
import com.yuka.cutebrowser.features.TranslateTarget
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/** 「翻译网页」确认弹窗：说清楚网址会发给谁，选好服务和语言后才发送 */
@Composable
fun TranslateDialog(
    ask: TranslateAsk,
    initialProvider: TranslateProvider,
    initialTarget: TranslateTarget,
    onConfirm: (TranslateProvider, TranslateTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TranslateCard(ask, initialProvider, initialTarget, onConfirm, onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateCard(
    ask: TranslateAsk,
    initialProvider: TranslateProvider,
    initialTarget: TranslateTarget,
    onConfirm: (TranslateProvider, TranslateTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalCutePalette.current
    var provider by remember { mutableStateOf(initialProvider) }
    var target by remember { mutableStateOf(initialTarget) }
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.surface)
            .border(2.dp, p.pink, RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatureIcons.Translate, null, tint = p.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text("翻译这个网页？", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "手机里没有翻译引擎，需要把这个网页的地址发给下面选的翻译服务，由它打开并翻译。",
            color = p.text, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(p.pink.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("会发送：${ask.host} 的网址", color = p.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "· 翻译服务会知道你在看哪个网页\n· 网址里如果带着登录信息之类的参数，也会一起发过去\n· 要登录才能看的内容一般翻译不了",
                color = p.subText, fontSize = 12.sp, lineHeight = 18.sp,
            )
            if (ask.incognito) {
                Spacer(Modifier.height(4.dp))
                Text("🎭 隐身模式：翻译结果会在新的隐身标签里打开，但网址仍然会发出去", color = p.subText, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("翻译服务", color = p.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TranslateProvider.entries.forEach { pr -> PillChoice(pr.label, provider == pr) { provider = pr } }
        }
        Spacer(Modifier.height(10.dp))
        Text("翻译成", color = p.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TranslateTarget.entries.forEach { t -> PillChoice(t.label, target == t) { target = t } }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "先不要",
                color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(p.lavender)
                    .bouncyClick(onDismiss)
                    .padding(vertical = 13.dp),
            )
            Text(
                "发送并翻译 🌏",
                color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1.4f)
                    .clip(RoundedCornerShape(50))
                    .background(p.accent)
                    .bouncyClick { onConfirm(provider, target) }
                    .padding(vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun PillChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalCutePalette.current
    Text(
        label,
        color = if (selected) p.onAccent else p.text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.accent else p.background)
            .border(2.dp, if (selected) p.accent else p.pink, RoundedCornerShape(50))
            .bouncyClick(onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
