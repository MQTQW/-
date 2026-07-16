package com.example.myplan.focus.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.util.AppIcon
import com.example.myplan.focus.util.TimeFormatter
import com.example.myplan.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showAddFlow by remember { mutableStateOf(false) }
    var selectedPackage by remember { mutableStateOf("") }
    var selectedAppName by remember { mutableStateOf("") }

    if (showAddFlow) {
        LimitEditScreen(
            app = context.applicationContext as MyPlanApp,
            onBack = { showAddFlow = false },
            onSaved = { showAddFlow = false }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
        ) {
            // Title
            Text(
                "🎯 专注",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp)
            )
            Text(
                "掌控时间，战胜拖延",
                fontSize = 13.sp,
                color = TextSoft,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp, end = 24.dp)
            )

            // Monitor status chip
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentLight)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.isMonitoringActive) "监控中" else "未开启",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Accent
                    )
                }
            }

            HorizontalDivider(color = Divider, thickness = 1.dp)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ===== Usage Section =====
                item {
                    Text(
                        "📊  今日使用时长",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSoft,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                if (state.todayUsage.isEmpty() && !state.isLoading) {
                    item { FocusEmpty("暂无使用数据", "开始使用手机后，使用时长会在这里显示") }
                } else {
                    items(state.todayUsage.take(10), key = { it.id }) { usage ->
                        UsageCard(usage = usage)
                    }
                }

                // ===== Limits Section =====
                item {
                    HorizontalDivider(
                        color = Divider, thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                    Text(
                        "⏰  应用限制",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSoft,
                        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                    )
                }

                // Add button
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AccentLight)
                            .clickable { showAddFlow = true }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("＋", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Accent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加新限制", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Accent)
                    }
                }

                if (state.limits.isEmpty() && !state.isLoading) {
                    item { FocusEmpty("暂无限制", "点击上方按钮为应用设置每日使用限额") }
                } else {
                    items(state.limits, key = { it.id }) { limit ->
                        LimitCard(
                            limit = limit,
                            onToggle = { viewModel.toggleLimit(limit) },
                            onDelete = { viewModel.deleteLimit(limit) }
                        )
                    }
                }

                // Footer
                item {
                    Text(
                        "🌸  保持专注，掌控时间  🌸",
                        fontSize = 12.sp,
                        color = TextSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageCard(usage: DailyUsageEntity) {
    val progress = remember(usage.totalTimeMillis) {
        (usage.totalTimeMillis.toFloat() / (2 * 60 * 60 * 1000f)).coerceIn(0f, 1f)
    }
    val progressColor = when {
        progress > 0.85f -> Delete
        progress > 0.5f -> Color(0xFFFFB800)
        else -> Complete
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardYellow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(packageName = usage.packageName, size = 32.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        usage.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        TimeFormatter.formatDuration(usage.totalTimeMillis),
                        fontSize = 12.sp,
                        color = TextSoft
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Divider)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(progressColor)
                )
            }
        }
    }
}

@Composable
private fun LimitCard(
    limit: AppLimitEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardYellow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clickable { showDelete = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (limit.packageName != null) {
                AppIcon(packageName = limit.packageName, size = 28.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Accent)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "${limit.appName} · ${TimeFormatter.formatDuration(limit.dailyLimitMillis)}/天",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextDark,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = limit.isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = ToggleOff
                )
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除限制", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除「${limit.appName}」的使用限制吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) {
                    Text("删除", color = Delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text("取消", color = TextSoft)
                }
            }
        )
    }
}

@Composable
private fun FocusEmpty(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextDark)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, fontSize = 13.sp, color = TextSoft, textAlign = TextAlign.Center)
    }
}

// ===== Add Limit Flow =====

@Composable
fun LimitEditScreen(
    app: MyPlanApp,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usageRepo = remember { AppUsageRepository(context, app.focusDb) }
    val limitRepo = remember { LimitRepository(app.focusDb) }
    val focusManager = LocalFocusManager.current

    // Step 1: search & pick app
    var step by remember { mutableIntStateOf(0) } // 0=search, 1=set time
    var searchQuery by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf("") }
    var limitHours by remember { mutableIntStateOf(1) }
    var limitMinutes by remember { mutableIntStateOf(0) }

    val allApps = remember { usageRepo.getInstalledApps() }
    var otherUsed = false

    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter { (_, name) -> name.contains(searchQuery, ignoreCase = true) }
    }

    if (step == 0) {
        // Search app screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "← 返回",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Accent,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "添加应用限制",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索应用名称...", fontSize = 14.sp, color = Color(0xFFC8B8A8)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = CardYellow,
                    unfocusedContainerColor = CardYellow
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                leadingIcon = { Text("🔍", fontSize = 14.sp) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                items(filteredApps) { (pkg, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                selectedPkg = pkg
                                selectedName = name
                                step = 1
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(packageName = pkg, size = 36.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                            Text(pkg, fontSize = 11.sp, color = TextSoft)
                        }
                        Text(">", fontSize = 16.sp, color = Color(0xFFC8B8A8))
                    }
                }
            }
        }
    } else {
        // Set time screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "← 返回",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Accent,
                    modifier = Modifier.clickable { step = 0 }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "设置每日限额",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            // App header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(packageName = selectedPkg, size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(selectedName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Text(selectedPkg, fontSize = 11.sp, color = TextSoft)
                }
            }

            HorizontalDivider(color = Divider, thickness = 1.dp)

            Spacer(modifier = Modifier.height(24.dp))

            // Time label
            Text(
                "每日使用时长上限",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDark,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Time display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$limitHours",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
                Text(
                    " 小时  ",
                    fontSize = 16.sp,
                    color = TextSoft
                )
                Text(
                    "${limitMinutes.toString().padStart(2, '0')}",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
                Text(
                    " 分钟",
                    fontSize = 16.sp,
                    color = TextSoft
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hour/minute sliders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("小时", fontSize = 12.sp, color = TextSoft)
                    Slider(
                        value = limitHours.toFloat(),
                        onValueChange = { limitHours = it.toInt() },
                        valueRange = 0f..12f,
                        steps = 11,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("分钟", fontSize = 12.sp, color = TextSoft)
                    Slider(
                        value = limitMinutes.toFloat(),
                        onValueChange = { limitMinutes = (it.toInt() / 5) * 5 },
                        valueRange = 0f..55f,
                        steps = 10,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val presets = listOf(
                    "15分钟" to Pair(0, 15),
                    "30分钟" to Pair(0, 30),
                    "1小时" to Pair(1, 0),
                    "2小时" to Pair(2, 0)
                )
                presets.forEach { (label, time) ->
                    val isSelected = limitHours == time.first && limitMinutes == time.second
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) AccentLight else CardYellow)
                            .clickable {
                                limitHours = time.first
                                limitMinutes = time.second
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) Accent else TextDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    scope.launch {
                        val millis = TimeFormatter.toMillis(limitHours, limitMinutes)
                        withContext(Dispatchers.IO) {
                            limitRepo.insertLimit(
                                AppLimitEntity(
                                    packageName = selectedPkg,
                                    appName = selectedName,
                                    dailyLimitMillis = millis
                                )
                            )
                        }
                        onSaved()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                enabled = limitHours > 0 || limitMinutes > 0
            ) {
                Text("保存限制", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

