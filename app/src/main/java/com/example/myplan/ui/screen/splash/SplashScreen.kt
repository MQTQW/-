package com.example.myplan.ui.screen.splash

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
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) { delay(1500); onTimeout() }
    Box(modifier = Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.size(100.dp).clip(RoundedCornerShape(30.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Text("📋", fontSize = 44.sp, fontWeight = FontWeight.W700)
            }
            Text("MY PLAN", fontSize = 32.sp, fontWeight = FontWeight.W800, color = TextDark)
            Text("种下一颗种子，收获一种习惯", fontSize = 13.sp, fontWeight = FontWeight.W400, color = TextSoft)
        }
    }
}
