package com.example.myplan.ui.screen.planedit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.component.CompanionButton
import com.example.myplan.ui.component.CompanionCard

@Composable
internal fun FocusDurationSelector(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 25, 45).forEach { minutes ->
            FilterChip(selected = selected == minutes, onClick = { onSelect(minutes) }, enabled = enabled,
                label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("$minutes 分钟") } },
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun PlanStepsSection(
    state: PlanEditUiState,
    viewModel: PlanEditViewModel,
    onStartFocus: (Long, Long) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionCard {
            Text("拆成小步骤", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text("把模糊的目标变成下一步。步骤不会单独发提醒，也不会重复奖励积分。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            CompanionButton(text = if (state.isSplitting) "正在整理步骤…" else "AI 帮我拆解",
                onClick = viewModel::requestSplit, enabled = !state.busy && !state.isSplitting,
                modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome)
            if (state.isSplitting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                TextButton(onClick = viewModel::cancelSplit, modifier = Modifier.align(Alignment.End)) { Text("取消生成") }
            }
            Text("仅发送当前计划的名称和补充说明，生成结果由你确认。手动添加可离线使用。",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.assistantError != null) {
                Text(state.assistantError, modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onOpenAiSettings, enabled = !state.busy) { Text("配置 AI 服务") }
            }
        }
        if (state.steps.isNotEmpty()) {
            Text(if (state.isDirty) "保存修改后，可以勾选步骤或开始专注。" else "勾选只记录步骤进度；整个计划仍需单独确认完成。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.steps.forEachIndexed { index, step ->
            key(step.key) {
                CompanionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = step.isDone(state.repeatMode), onCheckedChange = { viewModel.toggleStepCompleted(step.key) },
                            modifier = Modifier.semantics { contentDescription = "标记步骤 ${index + 1} 完成" },
                            enabled = !state.busy && !state.isCompleted && step.id > 0)
                        Text("步骤 ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeStep(step.key) }, enabled = !state.busy) {
                            Icon(Icons.Rounded.DeleteOutline, "删除步骤 ${index + 1}")
                        }
                    }
                    OutlinedTextField(value = step.title, onValueChange = { viewModel.updateStepTitle(step.key, it) },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text("这一步做什么") },
                        shape = RoundedCornerShape(14.dp), minLines = 1, maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    Spacer(Modifier.height(8.dp))
                    FocusDurationSelector(step.focusMinutes, !state.busy) { viewModel.updateStepMinutes(step.key, it) }
                    if (state.isEditMode && step.id > 0) {
                        TextButton(onClick = { viewModel.startStepFocus(step.key, onStartFocus) }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && !state.isCompleted && state.isEnabled && !step.isDone(state.repeatMode)) {
                            Text(if (step.isDone(state.repeatMode)) "这一步已完成" else "专注这一步")
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = viewModel::addStep, enabled = !state.busy && state.steps.size < PlanEditViewModel.MAX_STEPS,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("手动添加步骤")
        }
    }

    var showReplaceConfirmation by rememberSaveable { mutableStateOf(false) }
    if (state.suggestions.isNotEmpty() && !showReplaceConfirmation) {
        val canAccept = state.suggestions.any { it.selected } && state.suggestions.filter { it.selected }.all { it.title.isNotBlank() }
        AlertDialog(onDismissRequest = viewModel::dismissSuggestions, shape = RoundedCornerShape(22.dp),
            title = { Text("先看看这几步") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可以改写、调整时长，只采纳你需要的步骤。采纳后仍需保存计划。", style = MaterialTheme.typography.bodySmall)
                    state.suggestions.forEachIndexed { index, suggestion ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = suggestion.selected, onCheckedChange = { viewModel.toggleSuggestion(index) },
                                    modifier = Modifier.semantics { contentDescription = "采纳步骤 ${index + 1}" })
                                Text("步骤 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedTextField(value = suggestion.title, onValueChange = { viewModel.updateSuggestion(index, it) },
                                modifier = Modifier.fillMaxWidth(), label = { Text("步骤内容") }, minLines = 1, maxLines = 4,
                                isError = suggestion.selected && suggestion.title.isBlank(), shape = RoundedCornerShape(14.dp))
                            FocusDurationSelector(suggestion.focusMinutes, true) { viewModel.updateSuggestionMinutes(index, it) }
                        }
                    }
                    if (state.message != null) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.acceptSuggestions() }, enabled = canAccept) {
                        Text(if (state.steps.isEmpty()) "采纳所选步骤" else "追加所选步骤")
                    }
                    if (state.steps.isNotEmpty()) TextButton(onClick = { showReplaceConfirmation = true }, enabled = canAccept) { Text("替换现有步骤") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissSuggestions) { Text("暂不采纳") } })
    }
    if (showReplaceConfirmation && state.suggestions.isNotEmpty()) {
        AlertDialog(onDismissRequest = { showReplaceConfirmation = false }, shape = RoundedCornerShape(22.dp),
            title = { Text("替换现有步骤？") },
            text = { Text("现有步骤及其进度将在保存计划时被替换。也可以返回，选择追加建议。") },
            confirmButton = { TextButton(onClick = { viewModel.acceptSuggestions(replace = true); showReplaceConfirmation = false }) { Text("确认替换") } },
            dismissButton = { TextButton(onClick = { showReplaceConfirmation = false }) { Text("返回预览") } })
    }
}
