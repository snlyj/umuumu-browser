package com.yuka.cutebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yuka.cutebrowser.ui.theme.LocalCutePalette

/** v1.7 隐私 / 备份界面共用的小部件（单独一个文件，不和主题/菜单分支改的文件冲突） */
@Composable
internal fun CutePill(text: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (enabled) Modifier.bouncyClick(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** 一行可以点的勾选框（☑️ / ⬜） */
@Composable
internal fun CuteCheckRow(label: String, sub: String?, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalCutePalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).bouncySwitch(checked, onToggle).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (checked) "☑️" else "⬜", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = p.text, fontSize = 15.sp)
            if (sub != null) Text(sub, color = p.subText, fontSize = 12.sp)
        }
    }
}

/** 可爱的确认对话框 */
@Composable
internal fun CuteConfirmDialog(
    emoji: String,
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalCutePalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(p.surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 40.sp)
            Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.padding(4.dp))
            Text(message, color = p.subText, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.padding(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CutePill("算啦", p.pink, p.text, onDismiss, Modifier.weight(1f))
                CutePill(confirm, p.accent, p.onAccent, onConfirm, Modifier.weight(1f))
            }
        }
    }
}
