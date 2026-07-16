package com.example.myplan.focus.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.util.AppIcon
import com.example.myplan.focus.util.TimeFormatter
import com.example.myplan.ui.theme.Accent
import com.example.myplan.ui.theme.Complete
import com.example.myplan.ui.theme.Delete
import com.example.myplan.ui.theme.TextDark
import com.example.myplan.ui.theme.TextSoft

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(navController: NavController) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.startMonitoring() }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Accent, Color(0xFFFF9A7C)),
                            start = Offset(0f, 0f),
                            end = Offset(1000f, 800f)
                        )
                    )
                    .padding(top = 48.dp, bottom = 24.dp, start = 20.dp, end = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("专注", style = MaterialTheme.typography.headlineLarge,
                            color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("掌控时间，战胜拖延", style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f))
                    }
                    Surface(shape = RoundedCornerShape(50),
                        color = if (state.isMonitoringActive) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f)) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (state.isMonitoringActive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (state.isMonitoringActive) "监控中" else "未开启",
                                style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), Icons.Filled.Timer, "今日追踪", "${state.todayUsage.size}", Accent)
                    StatCard(Modifier.weight(1f), Icons.Filled.Block, "已锁定", "${state.lockedApps.size}", Delete)
                    StatCard(Modifier.weight(1f), Icons.Filled.TaskAlt, "待办任务", "${state.uncompletedTaskCount}", Complete)
                }
            }

            if (state.lockedApps.isNotEmpty()) {
                item { FocusSectionHeader(Icons.Filled.Block, "已锁定的应用", Delete) }
                items(state.lockedApps, key = { it.id }) { LockedAppCard(it) }
            }

            if (state.todayUsage.isNotEmpty()) {
                item { FocusSectionHeader(Icons.Filled.BarChart, "今日使用概览", Accent) }
                items(state.todayUsage.take(10), key = { it.id }) { UsageCard(it) }
            }

            if (!state.isLoading && state.todayUsage.isEmpty() && state.lockedApps.isEmpty()) {
                item {
                    FocusEmptyState(Icons.Outlined.PhoneAndroid, "暂无使用数据",
                        "开始使用手机后，数据会在这里显示。")
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, icon: ImageVector, label: String, value: String, color: Color) {
    val target = value.toIntOrNull() ?: 0
    val animated by animateIntAsState(target, animationSpec = tween(800, easing = FastOutSlowInEasing))
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("$animated", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSoft, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun FocusSectionHeader(icon: ImageVector, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
    }
}

@Composable
fun LockedAppCard(app: DailyUsageEntity) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Delete.copy(alpha = 0.1f))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Delete))
            Spacer(modifier = Modifier.width(12.dp))
            AppIcon(packageName = app.packageName, size = 26.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(app.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("今日已用 ${TimeFormatter.formatDuration(app.totalTimeMillis)} · 已锁定",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
        }
    }
}

@Composable
fun UsageCard(usage: DailyUsageEntity) {
    val progress = remember(usage.totalTimeMillis) {
        (usage.totalTimeMillis.toFloat() / (2 * 60 * 60 * 1000f)).coerceIn(0f, 1f)
    }
    val progressColor = when {
        progress > 0.85f -> Delete
        progress > 0.6f -> Color(0xFFFFB800)
        else -> Accent
    }
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(packageName = usage.packageName, size = 24.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(usage.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                }
                Text(TimeFormatter.formatDuration(usage.totalTimeMillis), style = MaterialTheme.typography.bodySmall, color = TextSoft, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = progressColor, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
fun FocusEmptyState(icon: ImageVector, title: String, subtitle: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(50), color = Accent.copy(alpha = 0.1f), modifier = Modifier.size(80.dp)) {
            Icon(icon, null, tint = Accent.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp).fillMaxSize())
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
        Spacer(modifier = Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSoft, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
