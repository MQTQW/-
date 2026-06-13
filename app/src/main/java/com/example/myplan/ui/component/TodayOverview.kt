package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

@Composable
fun TodayOverviewCard(
    planCount: Int,
    nextReminderTime: String?,
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AccentLight).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (planCount > 0) "今天有 ${planCount} 个计划" else "今天没有计划 🌿",
                fontSize = 15.sp, fontWeight = FontWeight.W600, color = TextDark
            )
            Text(
                if (nextReminderTime != null) "下一个提醒 $nextReminderTime" else "放松一下也不错",
                fontSize = 13.sp, fontWeight = FontWeight.W400, color = TextSoft
            )
        }
        if (streakDays > 0) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Accent).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔥", fontSize = 14.sp); Text(" $streakDays 天", fontSize = 12.sp, fontWeight = FontWeight.W700, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
