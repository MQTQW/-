package com.example.myplan.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.component.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistantSheet(phase: VoicePhase, ui: VoiceUiState, onCancel: () -> Unit, onConfirm: () -> Unit, onEdit: (PlanVoiceData) -> Unit) {
    if (phase == VoicePhase.IDLE) return
    ModalBottomSheet(onDismissRequest = { if (!ui.isSaving) onCancel() }, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val pending = ui.pendingPlan
            Text(when { ui.isSaving -> "正在保存计划…"; ui.saved -> "计划已保存"; pending == null -> phase.label; else -> "这样安排，可以吗？" }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (pending == null) {
                Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.MicNone, null, Modifier.padding(20.dp).size(38.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (phase == VoicePhase.PROCESSING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(ui.reply.ifBlank { "比如：工作日晚上九点提醒我阅读。" }, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.transcript.isNotBlank()) CompanionNote("你说：${ui.transcript}")
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), enabled = !ui.isSaving) {
                    Text(if (ui.saved) "关闭" else if (ui.isSaving) "正在保存" else "取消")
                }
            } else {
                CompanionCard {
                    Text(pending.name, style = MaterialTheme.typography.titleSmall)
                    SummaryLine("分类", pending.category)
                    SummaryLine("重复", when (pending.repeatMode) {
                        "WEEKDAYS" -> "周一至周五"
                        "CUSTOM" -> "每周" + pending.customDays.sorted().joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] }
                        else -> "每天"
                    })
                    SummaryLine("提醒时间", pending.reminderTimes.joinToString("、"))
                }
                Text("确认后，才会保存并设置提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onEdit(pending) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("修改内容") }
                    CompanionButton("保存计划", onConfirm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
