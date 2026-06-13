package com.example.myplan.ui.screen.planedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.ui.component.*
import com.example.myplan.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(
    planId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlanEditViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showTimePicker by remember { mutableStateOf(false) }
    var editingReminderIndex by remember { mutableIntStateOf(-1) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(planId) { viewModel.loadPlan(planId) }

    if (state.isDirty) {
        BackHandler { showUnsavedDialog = true }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditMode) "✏️ 编辑计划" else "✨ 新建计划", fontWeight = FontWeight.W700, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { if (state.isDirty) showUnsavedDialog = true else onBack() }) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Accent)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (state.name.isNotBlank()) { viewModel.save(onComplete = onSaved) }
                    }) { Text("保存", fontWeight = FontWeight.W700, color = Accent, fontSize = 16.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 分类 ──
            CategorySelector(
                selected = Categories.first { it.name == state.category },
                onSelect = { viewModel.updateCategory(it.name) }
            )

            // ── 完成状态 ──
            if (state.isEditMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("✅ 标记为已完成", fontSize = 15.sp, fontWeight = FontWeight.W600, color = TextDark)
                    IconButton(
                        onClick = { viewModel.toggleComplete() },
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (state.isCompleted) CardMint else Divider)
                    ) {
                        if (state.isCompleted) Icon(Icons.Rounded.Check, "完成", tint = Complete, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── 名称 ──
            OutlinedTextField(
                value = state.name, onValueChange = { viewModel.updateName(it) },
                placeholder = { Text("输入你的计划名称...", color = Color(0xFFC8B8A8)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardYellow, unfocusedContainerColor = CardYellow),
                singleLine = true
            )

            // ── 描述 ──
            OutlinedTextField(
                value = state.description, onValueChange = { viewModel.updateDescription(it) },
                placeholder = { Text("写点什么...", color = Color(0xFFC8B8A8)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardYellow, unfocusedContainerColor = CardYellow),
                maxLines = 3
            )

            // ── 提醒分隔 ──
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Divider(modifier = Modifier.weight(1f), color = Divider)
                Text("⏰ 提醒时间" + if (state.reminders.isNotEmpty()) " (${state.reminders.size}个)" else "", fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft)
                Divider(modifier = Modifier.weight(1f), color = Divider)
            }

            // ── 提醒列表 ──
            if (state.reminders.isEmpty()) {
                Text("暂未设置提醒", fontSize = 14.sp, color = TextSoft, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                state.reminders.forEachIndexed { index, rem ->
                    ReminderTimeRow(
                        time = rem.timeText(),
                        repeatMode = when (rem.repeatMode) { "DAILY" -> "每天"; "WEEKLY" -> "每周"; else -> "仅一次" },
                        enabled = rem.isEnabled,
                        onTimeClick = { editingReminderIndex = index; showTimePicker = true },
                        onRepeatClick = {
                            val modes = listOf("NONE", "DAILY", "WEEKLY")
                            viewModel.updateRepeatMode(index, modes[(modes.indexOf(rem.repeatMode) + 1) % 3])
                        },
                        onToggle = { viewModel.toggleReminder(index) },
                        onDelete = { viewModel.removeReminder(index) }
                    )
                }
            }

            // ── + 添加 ──
            OutlinedButton(
                onClick = { viewModel.addReminder(); editingReminderIndex = state.reminders.size - 1; showTimePicker = true },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, Color(0xFFC8E8D4)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Complete)
            ) {
                Icon(Icons.Rounded.Add, "添加", modifier = Modifier.size(18.dp))
                Text(" 添加提醒时间", fontSize = 14.sp, fontWeight = FontWeight.W500)
            }

            // ── 底部 ──
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                Text("🌸", fontSize = 16.sp, color = Color(0xFFFFB800))
                Text(" ${if (state.isEditMode) "修改你的计划" else "每个计划都是一颗种子"} ", fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextSoft)
                Text("🌸", fontSize = 16.sp, color = Color(0xFFFFB800))
            }
        }
    }

    // 时间选择器
    if (showTimePicker && editingReminderIndex >= 0 && editingReminderIndex < state.reminders.size) {
        val rem = state.reminders[editingReminderIndex]
        val mins = (rem.time / 60_000).toInt()
        TimePickerDialog(
            initialHour = (mins / 60) % 24, initialMinute = mins % 60,
            onConfirm = { h, m -> viewModel.updateReminder(editingReminderIndex, h, m); showTimePicker = false },
            onDismiss = { showTimePicker = false }
        )
    }

    // 未保存弹窗
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的更改") },
            text = { Text("确定放弃当前修改吗？") },
            confirmButton = { TextButton(onClick = { showUnsavedDialog = false; onBack() }) { Text("放弃并退出", color = Delete) } },
            dismissButton = { TextButton(onClick = { showUnsavedDialog = false }) { Text("继续编辑", color = Accent) } }
        )
    }
}
