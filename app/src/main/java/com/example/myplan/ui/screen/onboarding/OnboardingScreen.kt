package com.example.myplan.ui.screen.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.component.CompanionButton

private data class OnboardingPage(val icon: ImageVector, val title: String, val description: String,
    val firstExample: String, val secondExample: String)

private val pages = listOf(
    OnboardingPage(Icons.Outlined.Eco, "从一件小事开始", "把想做的事轻轻记下，\n给今天一个舒服的开始。", "读书二十分钟", "照顾好自己"),
    OnboardingPage(Icons.Outlined.NotificationsActive, "重要的事，交给提醒", "一天里的多个时刻，\n都可以有温柔的提醒。", "08:30 喝一杯温水", "20:00 读书二十分钟"),
    OnboardingPage(Icons.Outlined.Spa, "慢慢做，也在前进", "完成一件，记下一点成长。\n你的每一小步，都算数。", "今天完成啦", "为坚持留下记录"),
)

@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    var current by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[current]
    val colors = MaterialTheme.colorScheme
    BackHandler(enabled = current > 0) { current-- }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MY PLAN", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            TextButton(onClick = onStart) { Text("跳过") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(196.dp).clip(RoundedCornerShape(56.dp)).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(page.icon, null, Modifier.size(86.dp), tint = colors.primary)
                }
                ExamplePill(page.firstExample, Modifier.align(Alignment.CenterStart).offset(y = 70.dp))
                ExamplePill(page.secondExample, Modifier.align(Alignment.CenterEnd).offset(y = (-70).dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(page.description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { index ->
                Box(Modifier.padding(horizontal = 4.dp).size(width = if (index == current) 24.dp else 7.dp, height = 7.dp)
                    .clip(CircleShape).background(if (index == current) colors.primary else colors.outlineVariant))
            }
        }
        CompanionButton(if (current == pages.lastIndex) "开始使用" else "下一步",
            onClick = { if (current == pages.lastIndex) onStart() else current++ },
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            icon = if (current == pages.lastIndex) null else Icons.AutoMirrored.Outlined.ArrowForward)
    }
}

@Composable
private fun ExamplePill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
