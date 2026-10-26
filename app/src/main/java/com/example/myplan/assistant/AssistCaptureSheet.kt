package com.example.myplan.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.component.CompanionButton
import com.example.myplan.ui.component.CompanionCard
import com.example.myplan.ui.component.CompanionNote
import com.example.myplan.ui.component.CompanionTag
import com.example.myplan.voice.PlanVoiceData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistCaptureSheet(
    state: AssistCaptureState,
    onInput: (String) -> Unit,
    onReply: (String) -> Unit,
    onAnalyze: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PlanVoiceData) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onVoiceText: (String) -> Unit,
    onVoiceError: (String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val currentSaving by rememberUpdatedState(state.saving)
    val currentState by rememberUpdatedState(state)
    val currentVoiceText by rememberUpdatedState(onVoiceText)
    val currentVoiceError by rememberUpdatedState(onVoiceError)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !currentSaving })
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!currentState.saving && !currentState.busy) {
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
                if (text == null) currentVoiceError("没有听清，可以再试一次，或直接打字。")
                else currentVoiceText(text.take(if (currentState.question != null) 200 else 500))
            } else if (result.resultCode != Activity.RESULT_CANCELED) {
                currentVoiceError("语音识别未能完成，可以使用键盘语音输入或直接打字。")
            }
        }
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (state.question != null) "补充你的安排" else "说出你的计划")
        }
        try {
            if (intent.resolveActivity(context.packageManager) == null) {
                onVoiceError("手机没有可用的系统语音识别服务，可以使用键盘语音输入或直接打字。")
            } else {
                focusManager.clearFocus()
                voiceLauncher.launch(intent)
            }
        } catch (_: ActivityNotFoundException) {
            onVoiceError("系统语音识别服务不可用，可以使用键盘语音输入或直接打字。")
        } catch (_: SecurityException) {
            onVoiceError("系统暂时无法启动语音识别，可以使用键盘语音输入或直接打字。")
        }
    }
    BackHandler(enabled = state.saving) { }
    ModalBottomSheet(onDismissRequest = { if (!currentSaving) onDismiss() }, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.savedPlanId != null) "计划已保存" else "一句话安排", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.savedPlanId != null) "可以返回首页查看或编辑。" else if (state.preview != null) "确认后，才会保存并设置提醒。" else "把想到的事，先记下来。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, enabled = !state.saving) { Icon(Icons.Rounded.Close, "关闭") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.savedPlanId != null) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("已加入你的计划，到点会提醒。", style = MaterialTheme.typography.bodyMedium)
                }
                if (state.preview == null && state.savedPlanId == null) {
                    OutlinedTextField(value = state.input, onValueChange = { onInput(it.take(500)) }, enabled = !state.busy && !state.saving,
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                        label = { Text("想安排什么？") }, placeholder = { Text("比如：明晚八点提醒我读书，专注二十五分钟") },
                        supportingText = { Text("${state.input.length}/500") }, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
                    if (state.question != null) {
                        CompanionNote(state.question)
                        OutlinedTextField(value = state.reply, onValueChange = { onReply(it.take(200)) }, enabled = !state.busy && !state.saving,
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                            label = { Text("补充一下") }, placeholder = { Text("比如：明天晚上八点") },
                            supportingText = { Text("${state.reply.length}/200") }, shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
                    }
                    OutlinedButton(onClick = startVoice, enabled = !state.busy && !state.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.MicNone, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (state.question != null) "说一句补充" else "说出计划")
                    }
                    Text("语音识别由手机系统提供，仅在点击按钮后启动。也可以直接打字或使用键盘语音输入。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.serviceConfigured) {
                        CompanionCard {
                            Text("简单快记可离线使用", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text("日期和时间明确的提醒可在本机整理。更复杂的理解和计划拆解需要配置 AI 服务。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onSettings, enabled = !state.busy && !state.saving) { Text("配置 AI 服务") }
                        }
                    } else {
                        Text("需要 AI 时，仅发送本次描述和补充内容。生成结果可修改，确认后才保存。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.preview?.let { draft ->
                    CompanionTag(if (state.usedAi) "智能整理" else "已在本机整理")
                    AssistDraftPreview(draft)
                }
                if (state.busy || state.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.saving) "正在保存并设置提醒…" else "正在整理，可以取消。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    state.savedPlanId != null -> CompanionButton("完成", onDismiss, Modifier.fillMaxWidth(), enabled = !state.saving)
                    state.preview != null -> {
                        CompanionButton(if (state.saving) "正在保存…" else "确认保存计划",
                            onClick = { focusManager.clearFocus(); onConfirm() }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && !state.saving)
                        TextButton(onClick = { onEdit(state.preview) }, enabled = !state.busy && !state.saving, modifier = Modifier.fillMaxWidth()) { Text("修改内容") }
                    }
                    else -> {
                        CompanionButton(if (state.busy) "正在整理…" else if (state.question != null) "补充并继续" else "整理为计划",
                            onClick = { focusManager.clearFocus(); onAnalyze() }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && !state.saving && (if (state.question != null) state.reply.isNotBlank() else state.input.isNotBlank()))
                        if (state.busy) TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistDraftPreview(draft: PlanVoiceData) {
    CompanionCard {
        Text(draft.name, style = MaterialTheme.typography.titleMedium)
        DraftSummaryLine("分类", draft.category)
        val once = draft.oneTimeAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        if (draft.repeatMode == "ONCE") {
            DraftSummaryLine("日期", once?.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)) ?: "待补充")
            DraftSummaryLine("重复", "仅这一次")
        } else {
            DraftSummaryLine("重复", when (draft.repeatMode) {
                "WEEKDAYS" -> "周一至周五"
                "CUSTOM" -> "每周" + draft.customDays.sorted().filter { it in 1..7 }.joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] }
                else -> "每天"
            })
        }
        DraftSummaryLine("提醒", if (once != null && draft.repeatMode == "ONCE") once.format(DateTimeFormatter.ofPattern("HH:mm"))
            else draft.reminderTimes.joinToString("、").ifBlank { "未设置" })
        draft.focusMinutes?.let { DraftSummaryLine("建议专注", "$it 分钟") }
    }
}

@Composable
private fun DraftSummaryLine(label: String, value: String) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
