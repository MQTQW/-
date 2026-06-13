package com.example.myplan.ui.screen.planlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.ui.component.*
import com.example.myplan.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlanListScreen(
    onAddPlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
    viewModel: PlanListViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<PlanWithReminders?>(null) }
    var showSnackbar by remember { mutableStateOf(false) }
    var deletedPlan by remember { mutableStateOf<PlanWithReminders?>(null) }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPlan,
                containerColor = Accent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                modifier = Modifier.size(60.dp)
            ) {
                Icon(Icons.Rounded.Add, "新建", tint = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GreetingBar()

            // Today Overview
            val todayPlans = state.plans.filter {
                it.reminders.any { r -> r.isEnabled }
            }
            val nextReminder = todayPlans.flatMap { it.reminders }.filter { it.isEnabled }.minByOrNull { it.time }
            val nextTimeText = nextReminder?.let {
                val h = (it.time / 3600000).toInt() % 24
                val m = ((it.time % 3600000) / 60000).toInt()
                "%02d:%02d".format(h, m)
            }
            TodayOverviewCard(
                planCount = todayPlans.size,
                nextReminderTime = nextTimeText,
                streakDays = viewModel.streakDays.collectAsStateWithLifecycle().value,
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
                Text("📋 我的计划", fontSize = 16.sp, fontWeight = FontWeight.W600, color = TextSoft)
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

            val filteredPlans = remember(state.plans, searchQuery) {
                if (searchQuery.isBlank()) state.plans
                else state.plans.filter { it.plan.name.contains(searchQuery, ignoreCase = true) }
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
        Box(modifier = Modifier.fillMaxSize()) {
            UndoSnackBar(
                planName = deletedPlan?.plan?.name ?: "",
                onUndo = { viewModel.undoDelete(); showSnackbar = false; deletedPlan = null },
                onTimeout = { showSnackbar = false; deletedPlan = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
