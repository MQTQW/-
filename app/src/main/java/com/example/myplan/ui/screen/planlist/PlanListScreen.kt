package com.example.myplan.ui.screen.planlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.component.*
import com.example.myplan.voice.VoiceIndicator
import com.example.myplan.voice.VoicePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(
    onAddPlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
    viewModel: PlanListViewModel = viewModel(),
    onVoiceNewPlan: () -> Unit = {},
    voicePhase: StateFlow<VoicePhase> = MutableStateFlow(VoicePhase.IDLE),
    showAll: Boolean = false,
    onAllPlans: () -> Unit = {},
    onBack: () -> Unit = {},
    onStats: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val allPlans by viewModel.allPlans.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheduler = remember(context) { AlarmScheduler(context) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(30_000) }
    }
    var search by rememberSaveable { mutableStateOf("") }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PlanWithReminders?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val todayPlans = allPlans.filter { isScheduledOn(it, today) }
    val pending = todayPlans.filterNot { it.plan.isCompleted }.sortedBy { planTimeLabel(it) }
    val completed = todayPlans.filter { it.plan.isCompleted }
    val next = allPlans.flatMap { plan -> plan.reminders.mapNotNull { reminder ->
        scheduler.nextFireTime(plan.plan, reminder, now)?.let { plan to it }
    } }.minByOrNull { it.second }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (showAll) {
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                CompanionHeader("全部计划", onBack = onBack, trailing = {
                    IconButton(onClick = onAddPlan) { Icon(Icons.Outlined.Add, "新建计划") }
                })
                OutlinedTextField(value = search, onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索想找的计划") },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "搜索计划" })
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(PlanFilter.ALL to "全部", PlanFilter.ACTIVE to "进行中", PlanFilter.COMPLETED to "已完成").forEach { (filter, label) ->
                        FilterChip(selected = state.filter == filter, onClick = { viewModel.setFilter(filter) },
                            label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
                            modifier = Modifier.weight(1f))
                    }
                }
                val filtered = state.plans.filter { it.plan.name.contains(search, ignoreCase = true) }
                    .let { if (oldestFirst) it.sortedBy { row -> row.plan.createdAt } else it.sortedByDescending { row -> row.plan.createdAt } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${filtered.size} 个计划", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { oldestFirst = !oldestFirst }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(16.dp))
                        Text(if (oldestFirst) "先创建的在前" else "最近创建的在前", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (filtered.isEmpty()) item {
                        if (allPlans.isEmpty()) EmptyState(onAddPlan)
                        else Text("没有符合条件的计划", Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(filtered, key = { it.plan.id }) { plan ->
                        PlanCard(plan, onClick = { onEditPlan(plan.plan.id) }, onToggle = { viewModel.togglePlanEnabled(plan) },
                            onComplete = { viewModel.toggleComplete(plan) }, onDelete = { deleteTarget = plan })
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                    item {
                        CompanionHeader("今天", today.format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA)), trailing = {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.WbSunny, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        })
                    }
                    if (allPlans.isEmpty()) item { EmptyState(onAddPlan) }
                    else {
                        if (next != null) item {
                            val fireDate = Instant.ofEpochMilli(next.second).atZone(zone)
                            val dateLabel = when (fireDate.toLocalDate()) { today -> ""; today.plusDays(1) -> "明天 "; else -> fireDate.format(DateTimeFormatter.ofPattern("M/d ")) }
                            val minutes = ((next.second - now + 59_999) / 60_000).coerceAtLeast(1)
                            CompanionCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text("下一条提醒" + if (minutes < 60) " · 还有 $minutes 分钟" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Column {
                                        if (dateLabel.isNotEmpty()) Text(dateLabel.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(fireDate.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp)
                                    }
                                    VerticalDivider(Modifier.height(44.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(next.first.plan.name, style = MaterialTheme.typography.titleMedium)
                                        Text(planRepeatLabel(next.first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (fireDate.toLocalDate() == today) CompanionButton(
                                        if (next.first.plan.isCompleted) "今天已完成" else "完成今天",
                                        { viewModel.toggleComplete(next.first) }, Modifier.weight(1f), Icons.Outlined.Check, !next.first.plan.isCompleted)
                                    FilledTonalButton(onClick = { onEditPlan(next.first.plan.id) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("编辑计划") }
                                }
                            }
                        }
                        item {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                CompanionSectionTitle("今天待办", "全部计划", onAllPlans)
                                Text("已完成 ${completed.size} / ${todayPlans.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val heroCompletableId = next?.takeIf { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() == today }?.first?.plan?.id
                        val visiblePending = pending.filterNot { it.plan.id == heroCompletableId }
                        items(visiblePending, key = { it.plan.id }) { plan ->
                            TodayPlanRow(plan, { onEditPlan(plan.plan.id) }, { viewModel.toggleComplete(plan) })
                        }
                        if (todayPlans.isEmpty()) item {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("今天暂时没有安排", style = MaterialTheme.typography.titleMedium)
                                Text("可以轻松一下，或记下下一件想做的事。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onAllPlans) { Text("查看全部计划") }
                            }
                        } else if (pending.isEmpty()) item {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("今天的计划完成啦", style = MaterialTheme.typography.titleMedium)
                                Text("做得很好，给自己一点轻松的时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onStats) { Text("查看完成统计") }
                            }
                        }
                        if (completed.isNotEmpty()) item {
                            TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("${if (showCompleted) "收起" else "查看"}已完成的 ${completed.size} 项")
                                Icon(if (showCompleted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                            }
                        }
                        if (showCompleted) items(completed, key = { "done-${it.plan.id}" }) { plan ->
                            TodayPlanRow(plan, { onEditPlan(plan.plan.id) }, { viewModel.toggleComplete(plan) })
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allPlans.isNotEmpty()) CompanionButton("新建计划", onAddPlan, Modifier.fillMaxWidth(), Icons.Outlined.Add)
                    VoiceIndicator(voicePhase, Modifier.fillMaxWidth(), onVoiceNewPlan)
                }
            }
        }
    }
    deleteTarget?.let { plan -> DeleteConfirmationDialog(plan.plan.name, onConfirm = {
        viewModel.deletePlan(plan); deleteTarget = null
        scope.launch { if (snackbar.showSnackbar("计划已删除", "撤销", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) viewModel.undoDelete() }
    }, onDismiss = { deleteTarget = null }) }
}

@Composable
private fun TodayPlanRow(plan: PlanWithReminders, onEdit: () -> Unit, onComplete: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(planTimeLabel(plan).substringBefore('、'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 40.dp, max = 80.dp))
            Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 12.dp)) {
                Text(plan.plan.name, style = MaterialTheme.typography.bodyMedium, textDecoration = if (plan.plan.isCompleted) TextDecoration.LineThrough else null,
                    color = if (plan.plan.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text("${plan.plan.category} · ${planRepeatLabel(plan)}" + if (plan.reminders.count { it.isEnabled } > 1) " · ${plan.reminders.count { it.isEnabled }} 个时间" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(plan.plan.isCompleted, { onComplete() }, Modifier.semantics { contentDescription = "${if (plan.plan.isCompleted) "取消今天完成" else "完成今天"}：${plan.plan.name}" })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun isScheduledOn(plan: PlanWithReminders, day: LocalDate): Boolean {
    if (!plan.plan.isEnabled) return false
    val weekday = day.dayOfWeek.value
    val days = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    return plan.reminders.any { reminder ->
        if (!reminder.isEnabled) false else when (reminder.legacyRepeatMode) {
            "NONE" -> reminder.legacyTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() == day } ?: false
            "DAILY" -> true
            "WEEKLY" -> reminder.legacyTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).dayOfWeek.value == weekday } ?: false
            else -> when (plan.plan.repeatMode) { "EVERY_DAY" -> true; "WEEKDAYS" -> weekday in 1..5; "CUSTOM" -> weekday in days; else -> false }
        }
    }
}
