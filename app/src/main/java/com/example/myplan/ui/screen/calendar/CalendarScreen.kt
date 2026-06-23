package com.example.myplan.ui.screen.calendar

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = PlanRepository(PlanDatabase.getInstance(application))
    private val _plans = MutableStateFlow<List<PlanWithReminders>>(emptyList())
    val plans: StateFlow<List<PlanWithReminders>> = _plans
    init { viewModelScope.launch { repo.observeAllPlans().collect { _plans.value = it } } }
}

@Composable
fun CalendarScreen(onEditPlan: (Long) -> Unit, viewModel: CalendarViewModel = viewModel()) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }

    // 哪些日期有计划（基于重复模式判断）
    val planDates = remember(plans, currentMonth) {
        val dates = mutableSetOf<LocalDate>()
        val firstOfMonth = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        for (day in 1..daysInMonth) {
            val date = currentMonth.atDay(day)
            val dow = date.dayOfWeek  // MONDAY..SUNDAY
            val dayIndex = AlarmScheduler.Companion.calendarDayToIndex(
                when (dow) {
                    DayOfWeek.MONDAY -> java.util.Calendar.MONDAY
                    DayOfWeek.TUESDAY -> java.util.Calendar.TUESDAY
                    DayOfWeek.WEDNESDAY -> java.util.Calendar.WEDNESDAY
                    DayOfWeek.THURSDAY -> java.util.Calendar.THURSDAY
                    DayOfWeek.FRIDAY -> java.util.Calendar.FRIDAY
                    DayOfWeek.SATURDAY -> java.util.Calendar.SATURDAY
                    DayOfWeek.SUNDAY -> java.util.Calendar.SUNDAY
                }
            )
            for (plan in plans) {
                if (plan.reminders.isEmpty()) continue
                val customDays = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                val matches = when (plan.plan.repeatMode) {
                    "EVERY_DAY" -> true
                    "WEEKDAYS" -> dayIndex in 1..5
                    "CUSTOM" -> dayIndex in customDays
                    else -> true
                }
                if (matches) { dates.add(date); break }
            }
        }
        dates
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("<", fontSize = 24.sp, color = TextSoft, modifier = Modifier.clickable { currentMonth = currentMonth.minusMonths(1) })
            Text("${currentMonth.year}年 ${currentMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.W700, color = TextDark)
            Text(">", fontSize = 24.sp, color = TextSoft, modifier = Modifier.clickable { currentMonth = currentMonth.plusMonths(1) })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, fontSize = 12.sp, fontWeight = FontWeight.W600, color = if (it in listOf("六", "日")) TextSoft else TextDark, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))

        val firstDay = currentMonth.atDay(1)
        val cells = mutableListOf<Int?>()
        repeat((firstDay.dayOfWeek.value - 1)) { cells.add(null) }
        repeat(currentMonth.lengthOfMonth()) { cells.add(it + 1) }
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                week.forEach { day ->
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val date = currentMonth.atDay(day)
                            val isSelected = date == selectedDate
                            val isToday = date == today
                            val hasPlan = date in planDates
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isSelected) Accent else Color.Transparent)
                                    .clickable { selectedDate = date },
                            ) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "$day",
                                    fontSize = 14.sp,
                                    fontWeight = if (isToday) FontWeight.W700 else FontWeight.W400,
                                    color = if (isSelected) Color.White else TextDark,
                                )
                                if (hasPlan) Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isSelected) Color.White else Accent),
                                )
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 计划",
            fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))

        // 当日计划（根据重复模式筛选）
        val selectedDayIndex = AlarmScheduler.Companion.calendarDayToIndex(
            when (selectedDate.dayOfWeek) {
                DayOfWeek.MONDAY -> java.util.Calendar.MONDAY
                DayOfWeek.TUESDAY -> java.util.Calendar.TUESDAY
                DayOfWeek.WEDNESDAY -> java.util.Calendar.WEDNESDAY
                DayOfWeek.THURSDAY -> java.util.Calendar.THURSDAY
                DayOfWeek.FRIDAY -> java.util.Calendar.FRIDAY
                DayOfWeek.SATURDAY -> java.util.Calendar.SATURDAY
                DayOfWeek.SUNDAY -> java.util.Calendar.SUNDAY
            }
        )
        val dayPlans = remember(plans, selectedDate, selectedDayIndex) {
            plans.filter { plan ->
                if (plan.reminders.isEmpty()) return@filter false
                val customDays = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                when (plan.plan.repeatMode) {
                    "EVERY_DAY" -> true
                    "WEEKDAYS" -> selectedDayIndex in 1..5
                    "CUSTOM" -> selectedDayIndex in customDays
                    else -> true
                }
            }
        }

        if (dayPlans.isEmpty()) Text("暂无计划", fontSize = 13.sp, color = TextSoft, modifier = Modifier.padding(horizontal = 24.dp).weight(1f))
        else LazyColumn(modifier = Modifier.padding(horizontal = 24.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dayPlans) { plan ->
                val firstReminder = plan.reminders.minWithOrNull(compareBy({ it.hour }, { it.minute }))
                val tt = firstReminder?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardYellow)
                        .clickable { onEditPlan(plan.plan.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(plan.plan.name, fontSize = 14.sp, fontWeight = FontWeight.W500, color = TextDark, modifier = Modifier.weight(1f))
                    Text(tt, fontSize = 13.sp, color = TextSoft)
                }
            }
        }
    }
}
