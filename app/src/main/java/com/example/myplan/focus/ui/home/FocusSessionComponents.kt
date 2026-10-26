package com.example.myplan.focus.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepEntity
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.util.AppIcon
import com.example.myplan.ui.component.*

internal fun focusCountdown(millis: Long): String {
    val seconds = ((millis.coerceAtLeast(0) + 999) / 1000)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun FocusSessionCard(
    session: FocusSessionEntity?,
    remainingMillis: Long,
    busy: Boolean,
    permissionReady: Boolean,
    onSetup: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    onPermissions: () -> Unit,
    restrictedAppNames: List<String> = emptyList()
) {
    var confirmCancel by remember { mutableStateOf(false) }
    val current = session?.takeIf { it.isUnfinished }
    CompanionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CenterFocusStrong, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Text("为一件事专注", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (current != null) CompanionTag(when (current.status) {
                FocusSessionEntity.PAUSED -> "已暂停"
                FocusSessionEntity.READY -> "待确认"
                else -> "进行中"
            })
        }
        Spacer(Modifier.height(12.dp))
        if (current == null) {
            Text("选一条计划，暂时放下分心的应用。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            CompanionButton("选择计划开始", onClick = onSetup, modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.PlayArrow, enabled = !busy)
        } else {
            Text(current.stepTitle ?: current.planName, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (current.stepId != null) Text("所属计划 · ${current.planName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(focusCountdown(remainingMillis), fontSize = 44.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary)
            Text(when (current.status) {
                FocusSessionEntity.READY -> if (current.stepId == null) "时间到了，确认后完成计划并获得积分。"
                    else "时间到了，确认后只标记这一步完成，整条计划仍需单独打卡。"
                FocusSessionEntity.PAUSED -> "计时已暂停，应用恢复原有的限额规则。"
                else -> if (restrictedAppNames.isEmpty()) "专注期间限制 ${current.packages.size} 个应用"
                    else "限制中 · ${restrictedAppNames.take(2).joinToString("、")}${if (restrictedAppNames.size > 2) "等 ${current.packages.size} 个应用" else ""}"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            if (current.status == FocusSessionEntity.READY) {
                CompanionButton(if (current.stepId == null) "确认完成计划" else "确认完成这一步",
                    onClick = onComplete, modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.TaskAlt, enabled = !busy)
                TextButton(onClick = { confirmCancel = true }, enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (current.stepId == null) "结束本次，不完成计划" else "结束本次，不完成步骤")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompanionButton(if (current.status == FocusSessionEntity.PAUSED) "继续专注" else "暂停",
                        onClick = if (current.status == FocusSessionEntity.PAUSED) onResume else onPause,
                        modifier = Modifier.weight(1f),
                        icon = if (current.status == FocusSessionEntity.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        enabled = !busy && (current.status != FocusSessionEntity.PAUSED || permissionReady))
                    OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("结束")
                    }
                }
            }
        }
        if (!permissionReady) {
            Spacer(Modifier.height(12.dp))
            Text("应用拦截权限未就绪", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onPermissions) { Text("检查权限") }
        }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("结束这次专注？") },
        text = { Text(if (current?.stepId == null) "本次不会完成计划或发放积分，应用恢复原有的限额规则。"
            else "本次不会标记步骤完成，应用恢复原有的限额规则。") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("结束专注") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("继续保留") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusSetupScreen(
    plans: List<PlanEntity>,
    apps: List<Pair<String, String>>,
    busy: Boolean,
    permissionReady: Boolean,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onStart: (Long, Int, Set<String>, Long?) -> Unit,
    message: String? = null,
    appsLoading: Boolean = false,
    timedUnlockPackages: Set<String> = emptySet(),
    steps: List<PlanStepEntity> = emptyList(),
    date: String = java.time.LocalDate.now().toString(),
    initialPlanId: Long? = null,
    initialStepId: Long? = null
) {
    var selectedPlanId by rememberSaveable(initialPlanId, initialStepId) { mutableLongStateOf(initialPlanId ?: 0L) }
    var selectedStepId by rememberSaveable(initialPlanId, initialStepId) { mutableStateOf(initialStepId) }
    var minutes by rememberSaveable(initialPlanId, initialStepId) { mutableIntStateOf(
        steps.firstOrNull { it.id == initialStepId && it.planId == initialPlanId }?.focusMinutes
            ?: plans.firstOrNull { it.id == initialPlanId }?.suggestedFocusMinutes ?: 25
    ) }
    var selectedPackages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showPlans by remember { mutableStateOf(false) }
    val plan = plans.firstOrNull { it.id == selectedPlanId }
    val planSteps = steps.filter { it.planId == selectedPlanId }
    val availableSteps = planSteps.filter { plan != null && !it.isDoneOn(plan.repeatMode, date) }
    val selectedStep = availableSteps.firstOrNull { it.id == selectedStepId }
    val filteredApps = remember(apps, query) { apps.filter { it.second.contains(query, ignoreCase = true) } }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize().imePadding()) {
        CompanionHeader("开始一段专注", onBack = { if (!busy) onBack() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CompanionCard {
                    Text("这次想完成什么", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showPlans = true }, enabled = plans.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.TaskAlt, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(plan?.name ?: if (plans.isEmpty()) "还没有待完成的计划" else "选择一条待完成的计划",
                            Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    if (plans.isEmpty()) Text("先在「今天」新建一条计划，再来开始专注。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan != null && (planSteps.isNotEmpty() || selectedStepId != null)) {
                        Spacer(Modifier.height(12.dp))
                        Text("本次专注目标", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            selectedStepId = null; minutes = plan.suggestedFocusMinutes
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedStepId == null, enabled = !busy, onClick = {
                                selectedStepId = null; minutes = plan.suggestedFocusMinutes
                            })
                            Text("整条计划", style = MaterialTheme.typography.bodyMedium)
                        }
                        availableSteps.forEach { step ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selectedStepId = step.id; minutes = step.focusMinutes
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedStepId == step.id, enabled = !busy, onClick = {
                                    selectedStepId = step.id; minutes = step.focusMinutes
                                })
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(step.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("建议 ${step.focusMinutes} 分钟", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (availableSteps.isEmpty()) Text("这些步骤已完成，可以选择整条计划。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selectedStepId != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(if (selectedStep == null) "这一步已完成或删除，请重新选择目标。"
                            else "本次只完成所选步骤，不会自动完成整条计划或发放积分。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedStep == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("专注时长", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 25, 45).forEach { preset ->
                            FilterChip(selected = minutes == preset, onClick = { minutes = preset },
                                enabled = !busy, label = { Text("$preset 分钟") })
                        }
                    }
                }
            }
            item {
                Text("专注期间限制的应用 · 已选 ${selectedPackages.size} 个", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(5.dp))
                Text("暂停或结束后，恢复这些应用原有的限额规则。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("搜索应用名称") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            }
            items(filteredApps, key = { it.first }) { (pkg, name) ->
                val selected = pkg in selectedPackages
                Surface(shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        selectedPackages = if (selected) selectedPackages - pkg else selectedPackages + pkg
                    }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(packageName = pkg, size = 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Checkbox(checked = selected, enabled = !busy, onCheckedChange = { checked ->
                            selectedPackages = if (checked) selectedPackages + pkg else selectedPackages - pkg
                        })
                    }
                }
            }
            if (apps.isEmpty()) item {
                Text(if (appsLoading) "正在读取可选择的应用…" else "暂无可选择的应用", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (filteredApps.isEmpty()) item {
                Text("没有找到这个应用", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!permissionReady) item {
                CompanionNote("请先开启使用情况访问和 MY PLAN 无障碍服务，才能在专注时拦截应用。")
                TextButton(onClick = onPermissions) { Text("检查权限") }
            }
            if (selectedPackages.any { it in timedUnlockPackages }) item {
                CompanionNote("所选应用的限时解锁会继续计时，专注期间仍会限制使用。")
            }
            message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
        }
        CompanionButton(if (busy) "正在开始…" else "开始 $minutes 分钟专注",
            onClick = { plan?.let { onStart(it.id, minutes, selectedPackages.toSet(), selectedStepId) } },
            enabled = plan != null && (selectedStepId == null || selectedStep != null) &&
                selectedPackages.isNotEmpty() && permissionReady && !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), icon = Icons.Rounded.PlayArrow)
    }
    if (showPlans) AlertDialog(
        onDismissRequest = { showPlans = false }, title = { Text("选择计划") },
        text = {
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(plans, key = { it.id }) { option ->
                    val select = { selectedPlanId = option.id; selectedStepId = null;
                        minutes = option.suggestedFocusMinutes; showPlans = false }
                    Row(Modifier.fillMaxWidth().clickable(onClick = select).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedPlanId == option.id,
                            onClick = select)
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showPlans = false }) { Text("取消") } }
    )
}

@Composable
internal fun TimedUnlockDialog(
    apps: List<Pair<String, String>>,
    credits: Int,
    activePackages: Set<String>,
    sessionPackages: Set<String>,
    busy: Boolean,
    message: String? = null,
    onDismiss: () -> Unit,
    onRedeem: (String, String) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable(selected) { java.util.UUID.randomUUID().toString() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.LockOpen, null) },
        title = { Text("兑换 10 分钟") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("使用 1 积分，为一个受限应用解锁 10 分钟。立即开始计时，离开应用也会继续计时。",
                    style = MaterialTheme.typography.bodySmall)
                Text("可用积分 $credits", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(apps, key = { it.first }) { (pkg, name) ->
                        val enabled = pkg !in activePackages && pkg !in sessionPackages && !busy
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { selected = pkg }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected == pkg, enabled = enabled, onClick = { selected = pkg })
                            AppIcon(packageName = pkg, size = 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (pkg in sessionPackages || pkg in activePackages) Text(
                                    if (pkg in sessionPackages) "专注中，暂不可兑换" else "已在解锁中，无需重复兑换",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (apps.isEmpty() && !busy) Text("当前没有需要解锁的应用。", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (credits <= 0) Text("完成计划可获得积分。", style = MaterialTheme.typography.bodySmall)
                Text("到期后恢复原有限额规则。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onRedeem(selected, requestId) }, enabled = apps.any { it.first == selected } && credits > 0 &&
                selected !in activePackages && selected !in sessionPackages && !busy) {
                Text(if (busy) "正在兑换…" else "确认兑换")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}
