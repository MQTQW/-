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
import com.example.myplan.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MainApp(
    navController: NavHostController,
    deepLinkPlanId: Long? = null,
    openFocus: Boolean = false,
    onVoiceNewPlan: () -> Unit = {},
    voicePhase: StateFlow<VoicePhase> = MutableStateFlow(VoicePhase.IDLE),
    voiceUi: StateFlow<VoiceUiState> = MutableStateFlow(VoiceUiState()),
    onVoiceCancel: () -> Unit = {},
    onVoiceConfirm: () -> Unit = {}
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val phase by voicePhase.collectAsStateWithLifecycle()
    val voice by voiceUi.collectAsStateWithLifecycle()
    var voiceDraftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var focusDetailScreen by remember { mutableStateOf(false) }
    var settingsDetailScreen by remember { mutableStateOf(false) }
    val draft = remember(voiceDraftJson) { voiceDraftJson?.let(::decodeDraft) }
    val addPlan = { voiceDraftJson = null; navController.navigate("plan_edit/0") }
    val mainRoutes = listOf("plan_list", "calendar", "stats", "focus_home", "settings")

    LaunchedEffect(deepLinkPlanId, openFocus) {
        if (deepLinkPlanId != null && deepLinkPlanId > 0) navController.navigate("plan_edit/$deepLinkPlanId")
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
                                popUpTo("plan_list") { saveState = true }; launchSingleTop = true; restoreState = true
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
                    onVoiceNewPlan = onVoiceNewPlan, voicePhase = voicePhase,
                    onAllPlans = { navController.navigate("all_plans") }, onStats = { navController.navigate("stats") })
            }
            composable("all_plans") {
                PlanListScreen(onAddPlan = addPlan, onEditPlan = { navController.navigate("plan_edit/$it") },
                    showAll = true, onBack = { navController.popBackStack() })
            }
            composable("calendar") { CalendarScreen(onEditPlan = { navController.navigate("plan_edit/$it") }) }
            composable("stats") { StatsScreen() }
            composable("settings") { SettingsScreen(onDetailScreenChanged = { settingsDetailScreen = it }) }
            composable("focus_home") { FocusNavGraph(onDetailScreenChanged = { focusDetailScreen = it }) }
            composable("plan_edit/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType; defaultValue = 0L })) { backStack ->
                val id = backStack.arguments?.getLong("planId")?.takeIf { it > 0 }
                PlanEditScreen(planId = id,
                    onBack = { voiceDraftJson = null; navController.popBackStack() },
                    onSaved = { voiceDraftJson = null; navController.popBackStack() },
                    initialVoiceDraft = if (id == null) draft else null)
            }
        }
    }
    VoiceAssistantSheet(phase, voice, onVoiceCancel, onVoiceConfirm, onEdit = { plan ->
        voiceDraftJson = encodeDraft(plan)
        onVoiceCancel()
        navController.navigate("plan_edit/0")
    })
}

private fun encodeDraft(plan: PlanVoiceData): String = JSONObject().apply {
    put("name", plan.name); put("category", plan.category); put("repeat", plan.repeatMode)
    put("times", JSONArray(plan.reminderTimes)); put("days", JSONArray(plan.customDays.sorted()))
}.toString()

private fun decodeDraft(text: String): PlanVoiceData = JSONObject(text).let { data ->
    val times = data.getJSONArray("times")
    val days = data.getJSONArray("days")
    PlanVoiceData(name = data.getString("name"), category = data.getString("category"), repeatMode = data.getString("repeat"),
        reminderTimes = (0 until times.length()).map { times.getString(it) }, customDays = (0 until days.length()).map { days.getInt(it) }.toSet())
}
