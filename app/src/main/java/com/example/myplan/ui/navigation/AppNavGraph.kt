package com.example.myplan.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.myplan.focus.ui.focusnav.FocusNavGraph
import com.example.myplan.ui.screen.calendar.CalendarScreen
import com.example.myplan.ui.screen.planedit.PlanEditScreen
import com.example.myplan.ui.screen.planlist.PlanListScreen
import com.example.myplan.ui.screen.settings.SettingsScreen
import com.example.myplan.ui.screen.stats.StatsScreen
import com.example.myplan.assistant.*
import com.example.myplan.voice.PlanVoiceData
import com.example.myplan.travel.TravelActions
import com.example.myplan.travel.data.TravelDatabase
import com.example.myplan.travel.data.TravelRepository
import com.example.myplan.travel.ui.TravelHomeScreen
import com.example.myplan.travel.ui.TravelDetailScreen
import kotlinx.coroutines.launch

@Composable
fun MainApp(
    navController: NavHostController,
    deepLinkPlanId: Long? = null,
    openFocus: Boolean = false,
    openAiSettings: Boolean = false,
    launchRequestKey: Int = 0
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')?.let {
        if (it.startsWith("focus_target/")) "focus_home" else it
    }
    val context = LocalContext.current
    val captureViewModel: AssistCaptureViewModel = viewModel()
    val capture by captureViewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, captureViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) captureViewModel.cancelAnalysis()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var showCapture by rememberSaveable { mutableStateOf(false) }
    var returnToCapture by rememberSaveable { mutableStateOf(false) }
    var handledLaunch by rememberSaveable { mutableIntStateOf(-1) }
    var focusDetailScreen by remember { mutableStateOf(false) }
    var settingsDetailScreen by remember { mutableStateOf(false) }
    val addPlan = { navController.navigate("plan_edit/0") }
    val mainRoutes = listOf("plan_list", "calendar", "stats", "focus_home", "settings")

    LaunchedEffect(capture.savedPlanId) {
        capture.savedPlanId?.let { id ->
            android.widget.Toast.makeText(context, capture.error ?: "计划已保存", android.widget.Toast.LENGTH_LONG).show()
            showCapture = false
            captureViewModel.reset()
            navController.navigate("plan_edit/$id")
        }
    }

    LaunchedEffect(deepLinkPlanId, openFocus, openAiSettings, launchRequestKey) {
        if (handledLaunch == launchRequestKey) return@LaunchedEffect
        handledLaunch = launchRequestKey
        if (openAiSettings) navController.navigate("assist_settings") { launchSingleTop = true }
        else if (deepLinkPlanId != null && deepLinkPlanId > 0) navController.navigate("plan_edit/$deepLinkPlanId") { launchSingleTop = true }
        else if (openFocus) navController.navigate("focus_home") { launchSingleTop = true }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            if (route in mainRoutes && (route != "focus_home" || !focusDetailScreen) && (route != "settings" || !settingsDetailScreen)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.CalendarMonth, Icons.Outlined.BarChart, Icons.Outlined.CenterFocusStrong, Icons.Outlined.Tune)
                    val labels = listOf("今天", "日历", "统计", "专注", "设置")
                    mainRoutes.forEachIndexed { index, destination ->
                        NavigationBarItem(selected = route == destination,
                            onClick = { if (route != destination) navController.navigate(destination) {
                                popUpTo("plan_list") { saveState = true }; launchSingleTop = true
                                restoreState = destination != "plan_list"
                            } },
                            icon = { Icon(icons[index], null) }, label = { Text(labels[index], style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }) { padding ->
        NavHost(navController, startDestination = "plan_list", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("plan_list") {
                PlanListScreen(onAddPlan = addPlan, onEditPlan = { navController.navigate("plan_edit/$it") },
                    onAssistPlan = { captureViewModel.refreshConfiguration(); showCapture = true },
                    onAllPlans = { navController.navigate("all_plans") }, onStats = { navController.navigate("stats") },
                    onTravel = { navController.navigate("travel_home") })
            }
            composable("all_plans") {
                PlanListScreen(onAddPlan = addPlan, onEditPlan = { navController.navigate("plan_edit/$it") },
                    showAll = true, onBack = { navController.popBackStack() })
            }
            composable("calendar") { CalendarScreen(onEditPlan = { navController.navigate("plan_edit/$it") },
                onTravel = { navController.navigate("travel_home") }, onOpenTrip = { id, day -> navController.navigate("travel_detail/$id?day=$day") }) }
            composable("travel_home") { TravelHomeScreen(onBack = { navController.popBackStack() },
                onOpenTrip = { navController.navigate("travel_detail/$it") }) }
            composable("travel_detail/{tripId}?day={day}", arguments = listOf(
                navArgument("tripId") { type = NavType.LongType },
                navArgument("day") { type = NavType.IntType; defaultValue = -1 }
            )) { backStack ->
                TravelDestination(navController, backStack.arguments?.getLong("tripId") ?: 0L,
                    backStack.arguments?.getInt("day")?.takeIf { it >= 0 })
            }
            composable("stats") { StatsScreen() }
            composable("settings") { SettingsScreen(onDetailScreenChanged = { settingsDetailScreen = it },
                onAiSettings = { navController.navigate("assist_settings") }) }
            composable("assist_settings") { AssistSettingsScreen(onBack = {
                navController.popBackStack()
                captureViewModel.refreshConfiguration()
                if (returnToCapture) { returnToCapture = false; showCapture = true }
            }) }
            composable("focus_home") { FocusNavGraph(onDetailScreenChanged = { focusDetailScreen = it }) }
            composable("focus_target/{planId}/{stepId}", arguments = listOf(
                navArgument("planId") { type = NavType.LongType },
                navArgument("stepId") { type = NavType.LongType }
            )) { backStack ->
                var consumed by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(backStack.id) {
                    if (com.example.myplan.BuildConfig.DEBUG) android.util.Log.d("FocusTarget",
                        "route plan=${backStack.arguments?.getLong("planId")} step=${backStack.arguments?.getLong("stepId")} consumed=$consumed")
                }
                FocusNavGraph(onDetailScreenChanged = { focusDetailScreen = it },
                    initialPlanId = if (consumed) null else backStack.arguments?.getLong("planId")?.takeIf { it > 0 },
                    initialStepId = if (consumed) null else backStack.arguments?.getLong("stepId")?.takeIf { it > 0 },
                    onInitialFocusConsumed = { consumed = true })
            }
            composable("plan_edit/{planId}", arguments = listOf(
                navArgument("planId") { type = NavType.LongType; defaultValue = 0L }
            )) { backStack ->
                val id = backStack.arguments?.getLong("planId")?.takeIf { it > 0 }
                EditorDestination(navController, id)
            }
            composable("plan_draft?draft={draft}", arguments = listOf(
                navArgument("draft") { type = NavType.StringType; defaultValue = "" }
            )) { backStack ->
                val draftJson = backStack.arguments?.getString("draft").orEmpty()
                val draft = remember(draftJson) { draftJson.takeIf { it.isNotBlank() }?.let {
                    runCatching { PlanDraftCodec.decode(it) }.getOrNull()
                } }
                EditorDestination(navController, null, draft)
            }
        }
    }
    if (showCapture) AssistCaptureSheet(capture,
        onInput = captureViewModel::updateInput, onReply = captureViewModel::updateReply,
        onAnalyze = captureViewModel::analyze, onConfirm = captureViewModel::confirm,
        onEdit = { plan ->
            showCapture = false; captureViewModel.reset()
            navController.navigate("plan_draft?draft=${android.net.Uri.encode(PlanDraftCodec.encode(plan))}")
        },
        onDismiss = { showCapture = false; captureViewModel.reset() },
        onSettings = { showCapture = false; returnToCapture = true; navController.navigate("assist_settings") },
        onVoiceText = captureViewModel::acceptVoice, onVoiceError = captureViewModel::voiceError)
}

@Composable
private fun TravelDestination(navController: NavHostController, tripId: Long, initialDay: Int?) {
    val context = LocalContext.current
    val repository = remember(context) { TravelRepository(TravelDatabase.getInstance(context)) }
    val scope = rememberCoroutineScope()
    TravelDetailScreen(tripId = tripId, initialDay = initialDay, onBack = { navController.popBackStack() },
        onAiSettings = { navController.navigate("assist_settings") },
        onOpenMap = { trip, stop -> TravelActions.openMap(context, trip, stop) },
        onCreateReminder = { _, requestedStop ->
            scope.launch {
                try {
                    val current = repository.getTrip(tripId) ?: error("该行程已删除。")
                    val stop = current.stops.firstOrNull { it.id == requestedStop.id } ?: error("该节点已删除。")
                    val draft = TravelActions.reminderDraft(current.trip, stop)
                    val existing = com.example.myplan.data.repository.PlanRepository(
                        com.example.myplan.data.local.PlanDatabase.getInstance(context)).getByCreationToken(draft.creationToken!!)
                    if (existing != null) {
                        android.widget.Toast.makeText(context, "已创建过提醒，打开原计划。", android.widget.Toast.LENGTH_SHORT).show()
                        navController.navigate("plan_edit/${existing.id}")
                    } else navController.navigate("plan_draft?draft=${android.net.Uri.encode(PlanDraftCodec.encode(draft))}")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.widget.Toast.makeText(context, e.message ?: "暂时无法创建提醒。", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
}

@Composable
private fun EditorDestination(navController: NavHostController, planId: Long?, draft: PlanVoiceData? = null) {
    PlanEditScreen(planId = planId, onBack = { navController.popBackStack() },
        onSaved = { navController.popBackStack() }, initialVoiceDraft = draft,
        onOpenAiSettings = { navController.navigate("assist_settings") },
        onStartFocus = { plan, step ->
            if (com.example.myplan.BuildConfig.DEBUG) android.util.Log.d("FocusTarget", "navigate plan=$plan step=$step")
            navController.navigate("focus_target/$plan/$step") { launchSingleTop = true }
        })
}
