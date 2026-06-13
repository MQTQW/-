package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

@Composable
fun GreetingBar(modifier: Modifier = Modifier) {
    val now = remember { LocalTime.now() }
    val today = remember { LocalDate.now() }

    val greeting = when {
        now.hour in 6..11 -> "上午好呀"
        now.hour in 12..17 -> "下午好呀"
        else -> "晚上好呀"
    }

    val sunIcon = if (now.hour in 6..17) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode

    val dateText = remember(today) {
        val weekDay = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val formatter = DateTimeFormatter.ofPattern("M月d日")
        "${today.format(formatter)} $weekDay"
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(greeting, fontSize = 26.sp, fontWeight = FontWeight.W800, color = TextDark)
            Text(dateText, fontSize = 14.sp, fontWeight = FontWeight.W400, color = TextSoft)
        }
        Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(AccentLight), contentAlignment = Alignment.Center) {
            Icon(sunIcon, "天气", tint = Accent, modifier = Modifier.size(32.dp))
        }
    }
}
