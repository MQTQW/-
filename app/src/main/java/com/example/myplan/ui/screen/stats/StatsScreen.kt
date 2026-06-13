package com.example.myplan.ui.screen.stats

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
import com.example.myplan.ui.theme.*

@Composable
fun StatsScreen() {
    Column(modifier = Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())) {
        Text("📊 我的统计", fontSize = 20.sp, fontWeight = FontWeight.W700, color = TextDark, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(24.dp)).background(CardYellow).padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("🔥 连续打卡", fontSize = 13.sp, fontWeight = FontWeight.W500, color = TextSoft); Text("7", fontSize = 40.sp, fontWeight = FontWeight.W800, color = Accent); Text("天", fontSize = 13.sp, color = TextSoft) }
            Text("🌟", fontSize = 48.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("✅", "34", "已完成", CardVanilla, Modifier.weight(1f))
            StatCard("📋", "12", "进行中", CardVanilla, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("分类分布", fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        val accents = listOf(Color(0xFFE08D5A), Color(0xFFE8786E), Color(0xFF6B8AC4), Color(0xFF8B7AB8))
        listOf("📖 日常" to 14, "🔥 紧急" to 3, "💼 工作" to 8, "📚 学习" to 6).forEachIndexed { i, (name, count) ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextDark, modifier = Modifier.width(80.dp))
                Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF0E8E0))) {
                    Box(modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(accents[i]).fillMaxWidth(fraction = (count / 20f).coerceIn(0f, 1f)))
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
