package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

private val DayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun DayOfWeekPicker(
    selectedDays: Set<Int>,  // 1=Mon..7=Sun
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DayLabels.forEachIndexed { index, label ->
            val day = index + 1  // 1=Mon..7=Sun
            val isSelected = day in selectedDays
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Accent else Divider)
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W400,
                    color = if (isSelected) Color.White else TextSoft,
                )
            }
        }
    }
}
