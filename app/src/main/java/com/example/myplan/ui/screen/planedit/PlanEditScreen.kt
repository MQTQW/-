package com.example.myplan.ui.screen.planedit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.ui.component.*

@Composable
fun PlanEditScreen(
    planId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlanEditViewModel = viewModel(),
    initialVoiceDraft: com.example.myplan.voice.PlanVoiceData? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var editingReminderIndex by rememberSaveable { mutableIntStateOf(-1) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val nameFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val nameError = showValidation && state.name.isBlank()
    val daysError = showValidation && state.repeatMode == "CUSTOM" && state.customDays.isEmpty()

    LaunchedEffect(planId, initialVoiceDraft) {
        viewModel.loadPlan(planId)
        if (planId == null && initialVoiceDraft != null) viewModel.applyVoiceDraft(initialVoiceDraft)
    }

    val requestBack = {
        if (!state.isSaving) {
            if (state.isDirty) showUnsavedDialog = true else onBack()
        }
    }
    BackHandler(enabled = state.isDirty || state.isSaving) { requestBack() }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompanionHeader(
                title = if (state.isEditMode) "编辑计划" else "新建计划",
                onBack = requestBack,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    CompanionButton(
                        text = if (state.isSaving) "正在保存…" else "保存计划",
                        onClick = {
                            showValidation = true
                            when {
                                state.name.isBlank() -> nameFocus.requestFocus()
                                state.repeatMode == "CUSTOM" && state.customDays.isEmpty() -> Unit
                                else -> {
                                    focusManager.clearFocus()
                                    viewModel.save(onComplete = onSaved)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Check,
                        enabled = !state.isSaving,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorFieldLabel("计划名称", "必填")
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { viewModel.updateName(it) },
                    placeholder = { Text("想要做些什么？") },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    enabled = !state.isSaving,
                    shape = RoundedCornerShape(14.dp),
                    colors = editorFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) ({ Text("给计划起个名字吧") }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorFieldLabel("补充说明", "选填")
                OutlinedTextField(
                    value = state.description,
                    onValueChange = { viewModel.updateDescription(it) },
                    placeholder = { Text("留下一点说明，让开始更轻松") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).focusRequester(descriptionFocus),
                    enabled = !state.isSaving,
                    shape = RoundedCornerShape(14.dp),
                    colors = editorFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            CategorySelector(
                selected = Categories.firstOrNull { it.name == state.category } ?: Categories.first(),
                onSelect = { viewModel.updateCategory(it.name) },
                enabled = !state.isSaving,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EditorFieldLabel("重复方式", when (state.repeatMode) {
                    "ONCE" -> "仅这一次"
                    "WEEKDAYS" -> "周一至周五"
                    "CUSTOM" -> "自定义星期"
                    else -> "每天"
                })
                RepeatModeSelector(
                    selected = state.repeatMode,
                    onSelect = { viewModel.updateRepeatMode(it) },
                    enabled = !state.isSaving,
                )
                if (state.repeatMode == "ONCE") {
                    OutlinedButton(onClick = {
                        val d = java.time.LocalDate.parse(state.oneTimeDate)
                        android.app.DatePickerDialog(context, { _, y, m, day ->
                            viewModel.updateOneTimeDate(java.time.LocalDate.of(y, m + 1, day).toString())
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }, modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving) { Text("提醒日期：${state.oneTimeDate}") }
                }
                AnimatedVisibility(visible = state.repeatMode == "CUSTOM") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeekPicker(
                            selectedDays = state.customDays,
                            onToggle = { viewModel.toggleCustomDay(it) },
                            enabled = !state.isSaving,
                        )
                        if (daysError) Text("至少选择一个星期", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
                if (state.repeatMode == "WEEKDAYS") {
                    Text("按周一至周五重复，不自动调整节假日。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("提醒时间", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = { editingReminderIndex = -1; showTimePicker = true },
                        enabled = !state.isSaving && (state.repeatMode != "ONCE" || state.reminders.isEmpty()),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加时间", fontSize = 14.sp)
                    }
                }
                if (state.reminders.isEmpty()) {
                    CompanionCard {
                        Text("还没有提醒时间", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("添加一个时间，到点轻轻提醒你。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    state.reminders.forEachIndexed { index, reminder ->
                        ReminderTimeRow(
                            time = reminder.timeText(),
                            enabled = reminder.isEnabled,
                            onTimeClick = { editingReminderIndex = index; showTimePicker = true },
                            onToggle = { viewModel.toggleReminder(index) },
                            onDelete = { viewModel.removeReminder(index) },
                            editable = !state.isSaving,
                        )
                    }
                }
            }

            if (state.isEditMode) {
                CompanionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (state.repeatMode == "ONCE") "已完成" else "今天已完成", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (state.repeatMode == "ONCE") "完成后不再提醒，状态不会每天重置。" else "只标记今天，重复提醒会继续保留。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.isCompleted, onCheckedChange = { viewModel.toggleComplete() }, enabled = !state.isSaving)
                    }
                }
            }

            CompanionNote(text = if (state.repeatMode == "ONCE") "仅在指定日期提醒一次，完成后不再提醒。" else "完成今天的计划后，重复提醒会继续保留。暂停提醒请使用计划开关。")
            Spacer(Modifier.height(4.dp))
        }
    }

    if (showTimePicker) {
        val reminder = state.reminders.getOrNull(editingReminderIndex)
        TimePickerDialog(
            initialHour = reminder?.hour ?: 9,
            initialMinute = reminder?.minute ?: 0,
            onConfirm = { hour, minute ->
                if (editingReminderIndex < 0) viewModel.addReminder(hour, minute)
                else viewModel.updateReminder(editingReminderIndex, hour, minute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("要离开编辑吗？", fontSize = 20.sp, fontWeight = FontWeight.Medium) },
            text = { Text("还有修改没有保存。可以继续编辑，或放弃这次修改。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { showUnsavedDialog = false; onBack() }) {
                    Text("放弃修改", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun EditorFieldLabel(title: String, hint: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)
