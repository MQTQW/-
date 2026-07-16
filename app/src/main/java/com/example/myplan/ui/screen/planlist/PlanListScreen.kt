package com.example.myplan.ui.screen.planlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.R
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.ui.component.*
import com.example.myplan.ui.theme.*
import com.example.myplan.voice.VoiceIndicator
import com.example.myplan.voice.VoicePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlanListScreen(
    onAddPlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
    viewModel: PlanListViewModel = viewModel(),
    onVoiceNewPlan: () -> Unit = {},
    voicePhase: StateFlow<VoicePhase> = MutableStateFlow(VoicePhase.IDLE)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val streakDays by viewModel.streakDays.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<PlanWithReminders?>(null) }
    var showSnackbar by remember { mutableStateOf(false) }
    var deletedPlan by remember { mutableStateOf<PlanWithReminders?>(null) }
    var showCelebration by remember { mutableStateOf(false) }
    var sortAsc by remember { mutableStateOf(false) }
    var prevStreak by remember { mutableIntStateOf(0) }

    // Celebration when streak reaches 7
    LaunchedEffect(streakDays) {
        if (streakDays == 7 && prevStreak < 7) showCelebration = true
        prevStreak = streakDays
    }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPlan,
                containerColor = Accent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                modifier = Modifier.size(60.dp)
            ) {
                Image(painter = painterResource(id = R.drawable.ic_add), "新建")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GreetingBar()

            // Voice Assistant capsule — state-driven
            VoiceIndicator(
                phaseFlow = voicePhase,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = onVoiceNewPlan
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Today Overview: 筛选今天真正触发的计划 + 下一个未过的提醒
            val cal = java.util.Calendar.getInstance()
            val nowHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val nowMinute = cal.get(java.util.Calendar.MINUTE)
            val todayIndex = com.example.myplan.notification.AlarmScheduler.calendarDayToIndex(cal.get(java.util.Calendar.DAY_OF_WEEK))
            val todayPlans = state.plans.filter { plan ->
                val days = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                val firesToday = when (plan.plan.repeatMode) {
                    "EVERY_DAY" -> true
                    "WEEKDAYS" -> todayIndex in 1..5
                    "CUSTOM" -> todayIndex in days
                    else -> true
                }
                firesToday && plan.reminders.any { it.isEnabled }
            }
            val nextReminder = todayPlans
                .flatMap { it.reminders }
                .filter { it.isEnabled }
                .filter { it.hour > nowHour || (it.hour == nowHour && it.minute > nowMinute) }
                .minWithOrNull(compareBy({ it.hour }, { it.minute }))
            val nextTimeText = nextReminder?.let {
                "%02d:%02d".format(it.hour, it.minute)
            }
            TodayOverviewCard(
                planCount = todayPlans.size,
                nextReminderTime = nextTimeText,
                streakDays = streakDays,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            // Search bar
            var searchQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索计划...", fontSize = 14.sp, color = Color(0xFFC8B8A8)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardYellow, unfocusedContainerColor = CardYellow),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📋 我的计划", fontSize = 16.sp, fontWeight = FontWeight.W600, color = TextSoft)
                    // Sort toggle
                    Text(if (sortAsc) "↑" else "↓", fontSize = 16.sp, color = Accent, modifier = Modifier.clickable { sortAsc = !sortAsc })
                }
                FilterDropdown(
                    selected = when (state.filter) {
                        PlanFilter.ALL -> "全部"
                        PlanFilter.ACTIVE -> "进行中"
                        PlanFilter.COMPLETED -> "已完成"
                    },
                    onSelect = { label ->
                        viewModel.setFilter(when (label) {
                            "进行中" -> PlanFilter.ACTIVE
                            "已完成" -> PlanFilter.COMPLETED
                            else -> PlanFilter.ALL
                        })
                    }
                )
            }

            val filteredPlans = remember(state.plans, searchQuery, sortAsc) {
                val list = if (searchQuery.isBlank()) state.plans.toList()
                else state.plans.filter { it.plan.name.contains(searchQuery, ignoreCase = true) }
                list.sortedWith(if (sortAsc) compareBy { it.plan.name } else compareByDescending { it.plan.name })
            }

            if (filteredPlans.isEmpty()) {
                EmptyState(onCreateClick = onAddPlan)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(items = filteredPlans, key = { it.plan.id }) { plan ->
                        PlanCard(
                            plan = plan,
                            onClick = { onEditPlan(plan.plan.id) },
                            onToggle = { viewModel.togglePlanEnabled(plan) },
                            onComplete = { viewModel.toggleComplete(plan) },
                            onDelete = { deleteTarget = plan },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("🌸", fontSize = 16.sp, color = Color(0xFFFFB800))
                            Text(" 今天也要加油哦 ", fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextSoft)
                            Text("🌸", fontSize = 16.sp, color = Color(0xFFFFB800))
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { plan ->
        DeleteConfirmationDialog(
            planName = plan.plan.name,
            onConfirm = {
                deletedPlan = plan
                viewModel.deletePlan(plan)
                deleteTarget = null
                showSnackbar = true
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (showSnackbar) {
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            UndoSnackBar(
                planName = deletedPlan?.plan?.name ?: "",
                onUndo = { viewModel.undoDelete(); showSnackbar = false; deletedPlan = null },
                onTimeout = { showSnackbar = false; deletedPlan = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // Celebration dialog for 7-day streak
    if (showCelebration) {
        AlertDialog(
            onDismissRequest = { showCelebration = false },
            title = { Text("🎉 太棒了！", fontWeight = FontWeight.W800, color = TextDark) },
            text = { Text("你已经连续打卡 7 天！\n坚持就是胜利 🌟", lineHeight = 22.sp, color = TextSoft) },
            confirmButton = {
                TextButton(onClick = { showCelebration = false }) {
                    Text("继续加油 💪", color = Accent, fontWeight = FontWeight.W700)
                }
            }
        )
    }
}
