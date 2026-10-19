package com.example.myplan.ui.screen.stats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.DailyCheckin
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.ui.theme.*
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
        val catCounts: Map<String, Int> = emptyMap()
    )

    private val _stats = MutableStateFlow(StatsData())
    val stats: StateFlow<StatsData> = _stats

    private var allPlans: List<PlanWithReminders> = emptyList()

    init {
        viewModelScope.launch {
            combine(repo.observeAllPlans(), dao.observeAll(),
                (application as com.example.myplan.MyPlanApp).dates.today) { plans, checkins, _ -> plans to checkins }
                .collect { (plans, checkins) ->
                allPlans = plans
                val streak = computeStreak(checkins)
                val completed = plans.count { it.plan.isCompleted }
                val active = plans.count { !it.plan.isCompleted }
                val cats = plans.groupBy { it.plan.category }.mapValues { it.value.size }
                _stats.value = StatsData(streak, completed, active, cats)
            }
        }
    }

    private fun computeStreak(checkins: List<DailyCheckin>): Int {
        var s = 0; val today = LocalDate.now()
        for (i in 0..365) { val d = today.minusDays(i.toLong()).toString(); if (checkins.any { it.date == d && it.completedCount > 0 }) s++ else break }
        return s
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Text("📊 我的统计", fontSize = 20.sp, fontWeight = FontWeight.W700, color = TextDark, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(24.dp)).background(CardYellow).padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("🔥 连续打卡", fontSize = 13.sp, fontWeight = FontWeight.W500, color = TextSoft); Text("${stats.streak}", fontSize = 40.sp, fontWeight = FontWeight.W800, color = Accent); Text("天", fontSize = 13.sp, color = TextSoft) }
            Text("🌟", fontSize = 48.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("✅", "${stats.completed}", "已完成", CardVanilla, Modifier.weight(1f))
            StatCard("📋", "${stats.active}", "进行中", CardVanilla, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("分类分布", fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        val maxVal = stats.catCounts.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        val catAccents = mapOf("日常" to Color(0xFFE08D5A), "紧急" to Color(0xFFE8786E), "工作" to Color(0xFF6B8AC4), "学习" to Color(0xFF8B7AB8), "健康" to Color(0xFF8CA889), "其他" to Color(0xFF9B8579))
        stats.catCounts.forEach { (name, count) ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextDark, modifier = Modifier.width(80.dp))
                Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF0E8E0))) {
                    Box(modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(catAccents[name] ?: Color.Gray).fillMaxWidth(fraction = (count.toFloat() / maxVal).coerceIn(0f, 1f)))
                }
                Spacer(Modifier.width(8.dp))
                Text("$count", fontSize = 12.sp, fontWeight = FontWeight.W600, color = TextDark)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun StatCard(emoji: String, num: String, label: String, bg: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(emoji, fontSize = 28.sp); Text(num, fontSize = 24.sp, fontWeight = FontWeight.W800, color = TextDark); Text(label, fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextSoft)
    }
}
