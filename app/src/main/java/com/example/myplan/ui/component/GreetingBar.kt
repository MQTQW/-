package com.example.myplan.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.R
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
    val isDaytime = now.hour in 6..17

    val greeting = when {
        now.hour in 6..11 -> "上午好呀"
        now.hour in 12..17 -> "下午好呀"
        else -> "晚上好呀"
    }
    val timeEmoji = if (isDaytime) "☀️" else "🌙"

    val dateText = remember(today) {
        val weekDay = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val formatter = DateTimeFormatter.ofPattern("M月d日")
        "${today.format(formatter)} $weekDay $timeEmoji"
    }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$greeting 👋", fontSize = 26.sp, fontWeight = FontWeight.W800, color = TextDark)
                }
                Text(dateText, fontSize = 14.sp, fontWeight = FontWeight.W400, color = TextSoft)
            }
            // 白天=Pencil太阳PNG, 晚上=Material月亮图标
            Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(if (isDaytime) AccentLight else Color(0xFFEDE4F4)), contentAlignment = Alignment.Center) {
                if (isDaytime) {
                    Image(painter = painterResource(id = R.drawable.ic_sun), "天气", modifier = Modifier.size(32.dp))
                } else {
                    Icon(Icons.Rounded.DarkMode, "夜晚", tint = Color(0xFF8B7AB8), modifier = Modifier.size(32.dp))
                }
            }
        }
        // Sparkles decoration
        Row(modifier = Modifier.offset(x = 80.dp, y = (-4).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✨", fontSize = 14.sp, color = Color(0xFFFFB800))
            Text("⭐", fontSize = 10.sp, color = Color(0xFFFFB800))
            Text("✨", fontSize = 12.sp, color = Color(0xFFFFB800))
        }
    }
}
