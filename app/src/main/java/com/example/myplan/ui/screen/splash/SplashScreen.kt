package com.example.myplan.ui.screen.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { delay(1500); onTimeout() }
    Box(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(24.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(124.dp).clip(RoundedCornerShape(38.dp)).background(colors.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Eco, null, Modifier.size(64.dp), tint = colors.primary)
            }
            Text("MY PLAN", fontSize = 32.sp, fontWeight = FontWeight.Medium, color = colors.onBackground)
            Text("把重要的小事，轻轻记住", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        Text("为每一天，留一点从容", Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
