package com.example.myplan.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen(onEditPlan: (Long) -> Unit) {
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("<", fontSize = 24.sp, color = TextSoft, modifier = Modifier.clickable { currentMonth = currentMonth.minusMonths(1) })
            Text("${currentMonth.year}年 ${currentMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.W700, color = TextDark)
            Text(">", fontSize = 24.sp, color = TextSoft, modifier = Modifier.clickable { currentMonth = currentMonth.plusMonths(1) })
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("一","二","三","四","五","六","日").forEach {
                Text(it, fontSize = 12.sp, fontWeight = FontWeight.W600, color = if (it in listOf("六","日")) TextSoft else TextDark, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        val firstDay = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        val startDayOfWeek = (firstDay.dayOfWeek.value - 1)
        val cells = mutableListOf<Int?>()
        repeat(startDayOfWeek) { cells.add(null) }
        repeat(daysInMonth) { cells.add(it + 1) }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
                week.forEach { day ->
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSelected = day == selectedDate.dayOfMonth && currentMonth == YearMonth.from(selectedDate)
                            val isToday = day == today.dayOfMonth && currentMonth == YearMonth.from(today)
                            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(if (isSelected) Accent else Color.Transparent).clickable { selectedDate = currentMonth.atDay(day) }, contentAlignment = Alignment.Center) {
                                Text("$day", fontSize = 14.sp, fontWeight = if (isToday) FontWeight.W700 else FontWeight.W400, color = if (isSelected) Color.White else TextDark)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 计划", fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Text("暂无计划", fontSize = 13.sp, color = TextSoft, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
