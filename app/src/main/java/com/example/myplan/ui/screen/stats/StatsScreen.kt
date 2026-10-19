package com.example.myplan.ui.screen.stats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.DailyCheckin
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.ui.component.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = PlanRepository(PlanDatabase.getInstance(application))
    private val dao = PlanDatabase.getInstance(application).dailyCheckinDao()

    data class StatsData(
        val streak: Int = 0,
        val completed: Int = 0,
        val active: Int = 0,
        val catCounts: Map<String, Int> = emptyMap(),
    )

    private val _stats = MutableStateFlow(StatsData())
    val stats: StateFlow<StatsData> = _stats

    init {
        viewModelScope.launch {
            combine(repo.observeAllPlans(), dao.observeAll(), (application as MyPlanApp).dates.today) { plans, checkins, today ->
                StatsData(
                    streak = computeStreak(checkins, today),
                    completed = plans.count { it.plan.isCompleted },
                    active = plans.count { !it.plan.isCompleted },
                    catCounts = plans.groupBy { it.plan.category }.mapValues { it.value.size },
                )
            }.collect { _stats.value = it }
        }
    }

    private fun computeStreak(checkins: List<DailyCheckin>, today: LocalDate): Int {
        val completedDates = checkins.filter { it.completedCount > 0 }.mapTo(mutableSetOf()) { it.date }
        var streak = 0
        for (i in 0..365) {
            if (today.minusDays(i.toLong()).toString() in completedDates) streak++ else break
        }
        return streak
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(colors.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CompanionHeader("每一小步，都算数", "统计", contentPadding = PaddingValues(vertical = 8.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = colors.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连续打卡", style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${stats.streak}", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Medium, color = colors.onPrimaryContainer)
                        Text("天", Modifier.padding(bottom = 7.dp), color = colors.onPrimaryContainer)
                    }
                    Text(if (stats.streak > 0) "把一点坚持，留给自己。" else "从今天完成一件小事开始。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                }
                Icon(Icons.Outlined.Eco, null, Modifier.size(62.dp), tint = colors.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Icons.Outlined.CheckCircle, "${stats.completed}", "已完成", Modifier.weight(1f))
            StatCard(Icons.Outlined.FormatListBulleted, "${stats.active}", "进行中", Modifier.weight(1f))
        }
        CompanionSectionTitle("计划分类")
        CompanionCard {
            if (stats.catCounts.isEmpty()) {
                Text("还没有计划分类记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("新建计划后，就能在这里看到分布。", color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                val max = stats.catCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                stats.catCounts.entries.sortedByDescending { it.value }.forEachIndexed { index, (category, count) ->
                    if (index > 0) Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(category, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("$count 项", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth((count.toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp)).background(colors.primary))
                    }
                }
            }
        }
        CompanionNote("已完成和进行中展示当前计划状态；连续打卡来自每天的完成记录。")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatCard(icon: ImageVector, number: String, label: String, modifier: Modifier = Modifier) {
    CompanionCard(modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(14.dp))
        Text(number, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
