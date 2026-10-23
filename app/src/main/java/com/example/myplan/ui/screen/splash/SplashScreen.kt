package com.example.myplan.ui.screen.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.R
import com.example.myplan.ui.theme.MyPlanTheme
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { delay(1500); onTimeout() }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(24.dp)) {
        val illustrationSize = minOf(maxWidth * .9f, maxHeight * .5f, 320.dp)
        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.cover_companion_v1), contentDescription = null,
                modifier = Modifier.size(illustrationSize), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(18.dp))
            Text("MY PLAN", fontSize = 32.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif,
                letterSpacing = 3.sp, color = colors.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("计划 · 提醒 · 专注", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Cover · Light", widthDp = 360, heightDp = 800)
@Preview(name = "Cover · Dark", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CoverPreview() {
    MyPlanTheme { SplashScreen(onTimeout = {}) }
}
