package com.example.myplan.focus.ui.home

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.myplan.MyPlanApp
import com.example.myplan.BuildConfig
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.ui.permission.PermissionGuideScreen
import com.example.myplan.focus.util.AppIcon
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.focus.util.TimeFormatter
import com.example.myplan.ui.component.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onDetailScreenChanged: (Boolean) -> Unit = {}, initialPlanId: Long? = null,
    initialStepId: Long? = null, onInitialFocusConsumed: () -> Unit = {}) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as MyPlanApp
    val credits by app.unlockCredits.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionNavController = rememberNavController()
    var permissionRevision by remember { mutableIntStateOf(0) }
    var showPermissions by remember { mutableStateOf(false) }
    var showAddFlow by remember { mutableStateOf(false) }
    var editingLimit by remember { mutableStateOf<AppLimitEntity?>(null) }
    var showAllUsage by remember { mutableStateOf(false) }
    var showFocusSetup by rememberSaveable { mutableStateOf(false) }
    var showRedeem by rememberSaveable { mutableStateOf(false) }
    var setupInitialPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    var setupInitialStepId by rememberSaveable { mutableStateOf<Long?>(null) }
    var setupSelectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var handledInitial by rememberSaveable(initialPlanId, initialStepId) { mutableStateOf(false) }
    val setupState = rememberSaveableStateHolder()
    val requestPlanId = initialPlanId
    val requestStepId = initialStepId
    val focusDataLoaded = state.focusDataLoaded

    fun traceTarget(stage: String, result: Boolean? = null) {
        if (BuildConfig.DEBUG) Log.d("FocusTarget", "home stage=$stage vm=${System.identityHashCode(viewModel)} " +
            "plan=$requestPlanId step=$requestStepId loaded=$focusDataLoaded currentLoaded=${viewModel.state.value.focusDataLoaded} " +
            "handled=$handledInitial result=$result setup=$showFocusSetup " +
            "sessionStatus=${viewModel.state.value.focusSession?.status}")
    }

    LaunchedEffect(requestPlanId, requestStepId, focusDataLoaded) {
        traceTarget("effect")
        try {
            if (requestPlanId != null && focusDataLoaded && !handledInitial) {
                traceTarget("prepare_before")
                val prepared = viewModel.prepareFocusTarget(requestPlanId, requestStepId)
                traceTarget("prepare_after", prepared)
                if (prepared) {
                    setupInitialPlanId = requestPlanId
                    setupInitialStepId = requestStepId
                    setupState.removeState("focus_setup_$setupSelectionVersion")
                    setupSelectionVersion++
                    showPermissions = false
                    showAddFlow = false
                    showFocusSetup = true
                    traceTarget("open_initial", true)
                }
                handledInitial = true
                traceTarget("consume_before", prepared)
                onInitialFocusConsumed()
                traceTarget("consume_after", prepared)
            }
        } catch (cancelled: CancellationException) {
            traceTarget("effect_cancelled")
            throw cancelled
        }
    }

    LaunchedEffect(showPermissions, showAddFlow, showFocusSetup) {
        traceTarget(if (showPermissions) "visibility_permissions" else if (showAddFlow) "visibility_limit"
            else if (showFocusSetup) "visibility_setup" else "visibility_home")
        onDetailScreenChanged(showPermissions || showAddFlow || showFocusSetup)
    }
    LaunchedEffect(state.focusSession?.sessionId, state.focusMessage) {
        if (state.focusSession?.isUnfinished == true) {
            showFocusSetup = false
            traceTarget("close_unfinished")
        }
        if (state.focusMessage?.startsWith("已解锁") == true) showRedeem = false
    }
    LaunchedEffect(showRedeem) {
        if (showRedeem) viewModel.prepareRedemption()
    }
    DisposableEffect(Unit) {
        onDispose { traceTarget("dispose"); onDetailScreenChanged(false) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val monitoringReady = remember(permissionRevision) {
        PermissionHelper.hasUsageStatsPermission(context) &&
            PermissionHelper.hasAccessibilityPermission(context)
    }

    when {
        showPermissions -> {
            BackHandler { showPermissions = false }
            PermissionGuideScreen(permissionNavController, onBack = { showPermissions = false })
        }
        showAddFlow -> LimitEditScreen(
            app = app,
            onBack = { showAddFlow = false; editingLimit = null },
            onSaved = { showAddFlow = false; editingLimit = null },
            initialLimit = editingLimit
        )
        showFocusSetup -> setupState.SaveableStateProvider("focus_setup_$setupSelectionVersion") {
            FocusSetupScreen(plans = state.focusPlans, apps = state.focusApps, busy = state.focusBusy,
                permissionReady = monitoringReady, onBack = { showFocusSetup = false; traceTarget("close_back") },
                onPermissions = { showPermissions = true }, onStart = viewModel::startFocus,
                message = state.focusMessage, appsLoading = !state.focusAppsLoaded,
                timedUnlockPackages = state.timedUnlocks.keys, steps = state.focusSteps, date = state.focusDate,
                initialPlanId = setupInitialPlanId, initialStepId = setupInitialStepId)
        }
        else -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompanionHeader("专注", "把时间留给喜欢的事", trailing = {
                TextButton(onClick = { showPermissions = true }) {
                    Icon(if (monitoringReady) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                        null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (monitoringReady) "权限已就绪" else "检查权限", fontSize = 12.sp)
                }
            })
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    FocusSessionCard(session = state.focusSession, remainingMillis = state.focusRemainingMillis,
                        busy = state.focusBusy, permissionReady = monitoringReady,
                        onSetup = { viewModel.clearFocusMessage(); setupInitialPlanId = null; setupInitialStepId = null;
                            setupState.removeState("focus_setup_$setupSelectionVersion")
                            setupSelectionVersion++; showFocusSetup = true; traceTarget("open_manual", true) },
                        onPause = viewModel::pauseFocus, onResume = viewModel::resumeFocus,
                        onCancel = viewModel::cancelFocus, onComplete = viewModel::completeFocus,
                        onPermissions = { showPermissions = true },
                        restrictedAppNames = state.focusSession?.packages.orEmpty().map { pkg ->
                            state.focusApps.firstOrNull { it.first == pkg }?.second ?: pkg.substringAfterLast('.')
                        })
                }
                state.focusMessage?.let { message -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CompanionNote(message) }
                        IconButton(onClick = viewModel::clearFocusMessage) { Icon(Icons.Rounded.Close, "关闭提示") }
                    }
                } }
                item {
                    CompanionCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("今日使用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(TimeFormatter.formatDuration(state.todayUsage.sumOf { it.totalTimeMillis }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.isLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
                        } else if (state.todayUsage.isEmpty()) {
                            FocusEmpty("暂时还没有使用记录", "允许使用情况访问后，应用用量会在这里显示。")
                        } else {
                            val visibleUsage = if (showAllUsage) state.todayUsage else state.todayUsage.take(3)
                            visibleUsage.forEachIndexed { index, usage ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                UsageRow(usage, state.limits, state.todayUsage, state.timedUnlocks[usage.packageName],
                                    state.focusSession?.let { it.status == com.example.myplan.focus.session.FocusSessionEntity.RUNNING &&
                                        usage.packageName in it.packages } == true)
                            }
                            if (state.todayUsage.size > 3) {
                                TextButton(onClick = { showAllUsage = !showAllUsage }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                    Text(if (showAllUsage) "收起使用记录" else "查看全部 ${state.todayUsage.size} 个应用")
                                }
                            }
                        }
                    }
                }
                item {
                    CompanionSectionTitle("应用限制", "添加限制", onAction = { editingLimit = null; showAddFlow = true })
                }
                if (state.limits.isEmpty() && !state.isLoading) {
                    item {
                        CompanionCard {
                            FocusEmpty("给使用时间留一点边界", "为应用设置每日限额，到了时间就提醒自己停一停。")
                            CompanionButton("添加第一条限制", onClick = { editingLimit = null; showAddFlow = true },
                                modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
                        }
                    }
                } else {
                    items(state.limits, key = { it.id }) { limit ->
                        LimitCard(
                            limit = limit,
                            onEdit = { editingLimit = limit; showAddFlow = true },
                            onToggle = { viewModel.toggleLimit(limit) },
                            onDelete = { viewModel.deleteLimit(limit) }
                        )
                    }
                }
                item {
                    CompanionCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("可用积分", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$credits", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("完成今天的计划，可以获得解锁积分。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.timedUnlocks.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            state.timedUnlocks.forEach { (pkg, remaining) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(packageName = pkg, size = 30.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text(state.focusApps.firstOrNull { it.first == pkg }?.second ?: pkg.substringAfterLast('.'),
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("剩余 ${focusCountdown(remaining)}", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = {
                            showRedeem = true
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                            enabled = monitoringReady && !state.focusBusy) {
                            Icon(Icons.Rounded.LockOpen, null, Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("1 积分兑换 10 分钟")
                        }
                    }
                }
                if (!monitoringReady) item {
                    CompanionNote("应用限制需要使用情况访问和 MY PLAN 无障碍服务。可点击上方「检查权限」查看真实状态。")
                }
            }
        }
    }
    if (showRedeem) TimedUnlockDialog(apps = state.redemptionApps, credits = credits,
        activePackages = state.timedUnlocks.keys,
        sessionPackages = state.focusSession?.takeIf { it.status == com.example.myplan.focus.session.FocusSessionEntity.RUNNING }
            ?.packages.orEmpty(), busy = state.focusBusy, message = state.focusMessage,
        onDismiss = { showRedeem = false }, onRedeem = viewModel::redeem)
}

@Composable
private fun UsageRow(usage: DailyUsageEntity, limits: List<AppLimitEntity>, allUsage: List<DailyUsageEntity>,
    timedRemaining: Long? = null, sessionRestricted: Boolean = false) {
    val appLimit = limits.filter { it.isEnabled && it.packageName == usage.packageName && it.dailyLimitMillis > 0 }
        .minByOrNull { it.dailyLimitMillis }
    val categoryLimit = limits.filter { it.isEnabled && it.packageName == null && it.categoryId != null &&
        it.categoryId == usage.categoryId && it.dailyLimitMillis > 0 }.minByOrNull { it.dailyLimitMillis }
    val categoryUsage = if (categoryLimit != null) allUsage.filter { it.categoryId == usage.categoryId }.sumOf { it.totalTimeMillis } else 0L
    val progress = maxOf(
        appLimit?.let { usage.totalTimeMillis.toFloat() / it.dailyLimitMillis } ?: 0f,
        categoryLimit?.let { categoryUsage.toFloat() / it.dailyLimitMillis } ?: 0f
    ).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = usage.packageName, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(usage.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sessionRestricted) Text("专注限制中", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                else if (timedRemaining != null) Text("限时解锁 · 剩余 ${focusCountdown(timedRemaining)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                else if (usage.isLocked && !usage.isUnlocked) Text("已达限额", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (appLimit != null) "${TimeFormatter.formatDuration(usage.totalTimeMillis)} / ${TimeFormatter.formatDuration(appLimit.dailyLimitMillis)}"
                else TimeFormatter.formatDuration(usage.totalTimeMillis),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 145.dp)
            )
        }
        if (appLimit != null || categoryLimit != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer)
            if (categoryLimit != null) {
                Spacer(Modifier.height(5.dp))
                Text("分类合计 ${TimeFormatter.formatDuration(categoryUsage)} / ${TimeFormatter.formatDuration(categoryLimit.dailyLimitMillis)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LimitCard(limit: AppLimitEntity, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    CompanionCard(contentPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onEdit).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (limit.packageName != null) AppIcon(packageName = limit.packageName, size = 38.dp)
                else Surface(Modifier.size(38.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(limit.appName.ifBlank { "应用分类" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text("${if (limit.packageName == null) "分类合计 · " else ""}每天 ${TimeFormatter.formatDuration(limit.dailyLimitMillis)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!limit.isEnabled) Text("已暂停", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = limit.isEnabled, onCheckedChange = { onToggle() })
            IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, "删除 ${limit.appName} 的限制", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text("删除这条限制？") },
        text = { Text("「${limit.appName}」的使用记录会保留，这条限额将被移除。") },
        confirmButton = { TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除限制", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("先保留") } },
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
private fun FocusEmpty(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Spa, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitEditScreen(app: MyPlanApp, onBack: () -> Unit, onSaved: () -> Unit, initialLimit: AppLimitEntity? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usageRepo = remember { AppUsageRepository(context, app.focusDb) }
    val limitRepo = remember { LimitRepository(app.focusDb) }
    val focusManager = LocalFocusManager.current
    var step by remember(initialLimit) { mutableIntStateOf(if (initialLimit == null) 0 else 1) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedPkg by remember(initialLimit) { mutableStateOf(initialLimit?.packageName.orEmpty()) }
    var selectedName by remember(initialLimit) { mutableStateOf(initialLimit?.appName.orEmpty()) }
    var limitHours by remember(initialLimit) { mutableIntStateOf(initialLimit?.let { TimeFormatter.getHours(it.dailyLimitMillis) } ?: 0) }
    var limitMinutes by remember(initialLimit) { mutableIntStateOf(initialLimit?.let { TimeFormatter.getMinutes(it.dailyLimitMillis) } ?: 45) }
    var enabled by remember(initialLimit) { mutableStateOf(initialLimit?.isEnabled ?: true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val allApps by produceState<List<Pair<String, String>>?>(null, usageRepo) {
        value = withContext(Dispatchers.IO) { usageRepo.getInstalledApps() }
    }
    val filteredApps = remember(searchQuery, allApps) {
        allApps.orEmpty().filter { (_, name) -> name.contains(searchQuery, ignoreCase = true) }
    }
    val back = { if (!isSaving) { if (step == 1 && initialLimit == null) step = 0 else onBack() } }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        CompanionHeader(if (step == 0) "选择一个应用" else "每日使用限额", onBack = back)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            if (step == 0) {
                Text("想为哪个应用留一点使用边界？", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索应用名称") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (allApps == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
                    else if (filteredApps.isEmpty()) item { FocusEmpty("没有找到这个应用", "试试其他名称，或检查应用是否已安装。") }
                    items(filteredApps, key = { it.first }) { (pkg, name) ->
                        Surface(shape = RoundedCornerShape(16.dp), color = if (selectedPkg == pkg) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable { selectedPkg = pkg; selectedName = name; focusManager.clearFocus() }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(packageName = pkg, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                RadioButton(selected = selectedPkg == pkg, onClick = { selectedPkg = pkg; selectedName = name; focusManager.clearFocus() })
                            }
                        }
                    }
                }
                CompanionButton("继续设置限额", onClick = { step = 1 }, enabled = selectedPkg.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), icon = Icons.AutoMirrored.Rounded.ArrowForward)
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedPkg.isNotBlank()) AppIcon(packageName = selectedPkg, size = 48.dp)
                        else Icon(Icons.Rounded.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selectedName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(if (initialLimit?.packageName == null && initialLimit != null) "分类中的应用共享每日限额" else "每天给它留多少时间？",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    CompanionCard {
                        Text("每日使用时长上限", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.Bottom) {
                            if (limitHours > 0) {
                                Text("$limitHours", fontSize = 44.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                                Text(" 小时 ", Modifier.padding(bottom = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("$limitMinutes", fontSize = 44.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Text(" 分钟", Modifier.padding(bottom = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("小时 · $limitHours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = limitHours.toFloat(), onValueChange = { limitHours = it.toInt() }, valueRange = 0f..12f, steps = 11, enabled = !isSaving)
                        Text("分钟 · $limitMinutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = limitMinutes.toFloat(), onValueChange = { limitMinutes = (it.toInt() / 5) * 5 }, valueRange = 0f..55f, steps = 10, enabled = !isSaving)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 30, 45, 60).forEach { minutes ->
                                FilterChip(selected = limitHours * 60 + limitMinutes == minutes,
                                    onClick = { limitHours = minutes / 60; limitMinutes = minutes % 60 },
                                    label = { Text(if (minutes == 60) "1小时" else "${minutes}分", fontSize = 12.sp) },
                                    enabled = !isSaving)
                            }
                        }
                    }
                    CompanionCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用这条限制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text("到达限额后，提醒你停一停", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !isSaving)
                        }
                    }
                    CompanionNote("新的一天会重置用量。完成计划获得积分后，可以解锁受限应用。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                CompanionButton(if (isSaving) "正在保存…" else "保存限制", onClick = {
                    if (!isSaving) {
                        isSaving = true
                        error = null
                        scope.launch {
                            try {
                                val millis = TimeFormatter.toMillis(limitHours, limitMinutes)
                                withContext(Dispatchers.IO) {
                                    if (initialLimit != null) limitRepo.updateLimit(initialLimit.copy(dailyLimitMillis = millis, isEnabled = enabled))
                                    else limitRepo.insertLimit(AppLimitEntity(packageName = selectedPkg, appName = selectedName, dailyLimitMillis = millis, isEnabled = enabled))
                                }
                                onSaved()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "暂时没能保存，请再试一次。"
                            } finally { isSaving = false }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), icon = Icons.Rounded.Check,
                    enabled = !isSaving && (limitHours > 0 || limitMinutes > 0))
            }
        }
    }
}
