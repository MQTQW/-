package com.example.myplan.ui.screen.calendar

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.ui.component.*
import com.example.myplan.travel.data.TravelDatabase
import com.example.myplan.travel.data.TravelRepository
import com.example.myplan.travel.data.TripWithStops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = PlanRepository(PlanDatabase.getInstance(application))
    private val _plans = MutableStateFlow<List<PlanWithReminders>>(emptyList())
    val plans: StateFlow<List<PlanWithReminders>> = _plans
    private val _trips = MutableStateFlow<List<TripWithStops>>(emptyList())
    val trips: StateFlow<List<TripWithStops>> = _trips
    init {
        viewModelScope.launch { repo.observeAllPlans().collect { _plans.value = it } }
        viewModelScope.launch { TravelRepository(TravelDatabase.getInstance(application)).observeTrips().collect { _trips.value = it } }
    }
}

private fun PlanWithReminders.matchesDate(date: LocalDate): Boolean {
    if (reminders.isEmpty()) return false
    return when (plan.repeatMode) {
        "ONCE" -> plan.oneTimeAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == date } ?: false
        "EVERY_DAY" -> true
        "WEEKDAYS" -> date.dayOfWeek.value in 1..5
        "CUSTOM" -> date.dayOfWeek.value in plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }
        else -> true
    }
}

@Composable
fun CalendarScreen(onEditPlan: (Long) -> Unit, viewModel: CalendarViewModel = viewModel(), onTravel: () -> Unit = {},
    onOpenTrip: (Long, Int) -> Unit = { _, _ -> }) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dates = remember(context) { (context.applicationContext as MyPlanApp).dates }
    val today by dates.today.collectAsStateWithLifecycle()
    var selectedDate by remember { mutableStateOf(today) }
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }
    val colors = MaterialTheme.colorScheme
    val planDates = remember(plans, trips, currentMonth) {
        (1..currentMonth.lengthOfMonth()).map { currentMonth.atDay(it) }
            .filterTo(mutableSetOf()) { date -> plans.any { it.matchesDate(date) } || trips.any { it.includesDate(date) } }
    }
    val dayPlans = remember(plans, selectedDate) { plans.filter { it.matchesDate(selectedDate) } }
    val dayTrips = remember(trips, selectedDate) { trips.filter { it.includesDate(selectedDate) } }
    val days = remember(currentMonth) {
        List<Int?>(currentMonth.atDay(1).dayOfWeek.value - 1) { null } +
            (1..currentMonth.lengthOfMonth()).map { it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CompanionHeader("把日子慢慢安排好", "日历", contentPadding = PaddingValues(vertical = 8.dp), trailing = {
                TextButton(onClick = { currentMonth = YearMonth.from(today); selectedDate = today }) { Text("今天") }
            })
        }
        item {
            CompanionCard(Modifier.clickable(onClickLabel = "打开行程规划", onClick = onTravel)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Map, null, tint = colors.primary)
                    Column(Modifier.weight(1f)) {
                        Text("行程规划", style = MaterialTheme.typography.titleMedium)
                        Text("按天安排地点、活动和出发时间", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = colors.primary)
                }
            }
        }
        item {
            CompanionCard(contentPadding = 8.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月")
                    }
                    Text("${currentMonth.year} 年 ${currentMonth.monthValue} 月", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                        Text(day, Modifier.weight(1f).padding(vertical = 10.dp),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center)
                    }
                }
                days.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (week + List<Int?>(7 - week.size) { null }).forEach { day ->
                            if (day == null) Spacer(Modifier.weight(1f).height(46.dp))
                            else {
                                val date = currentMonth.atDay(day)
                                val selected = date == selectedDate
                                Column(
                                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp))
                                        .background(if (selected) colors.primary else colors.surface)
                                        .clickable(onClickLabel = "选择 ${date.monthValue} 月 ${date.dayOfMonth} 日") { selectedDate = date },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text("$day", style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (date == today) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) colors.onPrimary else if (date == today) colors.primary else colors.onSurface)
                                    Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape)
                                        .background(if (date in planDates) {
                                            if (selected) colors.onPrimary else colors.primary
                                        } else androidx.compose.ui.graphics.Color.Transparent))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { CompanionSectionTitle("${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日 · ${dayPlans.size} 项计划" +
            if (dayTrips.isNotEmpty()) " · ${dayTrips.size} 个行程" else "") }
        items(dayTrips, key = { "trip-${it.trip.id}" }) { row ->
            val day = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(row.trip.startDate), selectedDate).toInt()
            val stops = row.stops.filter { it.dayIndex == day }
            CompanionCard(Modifier.clickable { onOpenTrip(row.trip.id, day) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Map, null, tint = colors.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.trip.title, style = MaterialTheme.typography.titleMedium)
                        Text("${row.trip.destination} · 第 ${day + 1} 天 · ${stops.size} 个节点",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("行程时区：${row.trip.timeZoneId}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "打开行程", tint = colors.onSurfaceVariant)
                }
            }
        }
        if (dayPlans.isEmpty() && dayTrips.isEmpty()) {
            item {
                CompanionCard {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = colors.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("这一天，还没有安排", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("已有计划会按重复星期出现在这里。", color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(dayPlans, key = { it.plan.id }) { plan ->
            CompanionCard(Modifier.clickable { onEditPlan(plan.plan.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plan.plan.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (plan.plan.repeatMode == "ONCE" && plan.plan.oneTimeAt != null)
                            java.time.Instant.ofEpochMilli(plan.plan.oneTimeAt).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                            else plan.reminders.sortedWith(compareBy({ it.hour }, { it.minute }))
                                .joinToString(" · ") { "%02d:%02d".format(it.hour, it.minute) },
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (!plan.plan.isEnabled) CompanionTag("提醒已暂停")
                    }
                    CompanionTag(plan.plan.category)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "编辑计划", tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
        item { CompanionNote("这里按计划的重复规则展示安排，完成状态以今天为准。") }
    }
}

private fun TripWithStops.includesDate(date: LocalDate): Boolean {
    val start = LocalDate.parse(trip.startDate)
    return date >= start && date < start.plusDays(trip.dayCount.toLong())
}
