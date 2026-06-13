package com.example.myplan.ui.screen.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

data class OnboardingPage(val emoji: String, val title: String, val desc: String)

val pages = listOf(
    OnboardingPage("🌱", "种下计划", "把你想做的事写下来\n每个计划都是一颗种子"),
    OnboardingPage("⏰", "准时提醒", "设置多个提醒时间\n再也不怕忘记重要的事"),
    OnboardingPage("🌟", "收获成长", "坚持完成计划，看到自己的进步\n每一天都是值得庆祝的"),
)

@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    var current by remember { mutableIntStateOf(0) }
    val page = pages[current]

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.size(160.dp).clip(CircleShape).background(AccentLight), contentAlignment = Alignment.Center) {
                Text(page.emoji, fontSize = 64.sp)
            }
            Spacer(Modifier.height(32.dp))
            Text(page.title, fontSize = 24.sp, fontWeight = FontWeight.W800, color = TextDark)
            Spacer(Modifier.height(12.dp))
            Text(page.desc, fontSize = 15.sp, fontWeight = FontWeight.W400, color = TextSoft, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp)) {
            if (current < 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { i ->
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (i == current) Accent else Divider))
                    }
                }
                Spacer(Modifier.height(32.dp))
                Box(modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Accent).padding(horizontal = 48.dp, vertical = 16.dp).clickable { current++ }) {
                    Text("下一步", fontSize = 16.sp, fontWeight = FontWeight.W700, color = Color.White)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { i ->
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (i == 2) Accent else Divider))
                    }
                }
                Spacer(Modifier.height(32.dp))
                Box(modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Accent).padding(horizontal = 48.dp, vertical = 16.dp).clickable { onStart() }) {
                    Text("开始使用", fontSize = 16.sp, fontWeight = FontWeight.W700, color = Color.White)
                }
            }
        }
    }
}
