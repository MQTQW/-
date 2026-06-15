package com.example.myplan.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.example.myplan.ui.screen.calendar.CalendarScreen
import com.example.myplan.ui.screen.planedit.PlanEditScreen
import com.example.myplan.ui.screen.planlist.PlanListScreen
import com.example.myplan.ui.screen.settings.SettingsScreen
import com.example.myplan.ui.screen.stats.StatsScreen
import com.example.myplan.ui.theme.*

@Composable
fun MainApp(navController: NavHostController, deepLinkPlanId: Long? = null) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(deepLinkPlanId) {
        deepLinkPlanId?.let { id ->
            if (id > 0) navController.navigate("plan_edit/$id")
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (currentRoute in listOf("plan_list", "calendar", "stats", "settings")) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    val items = listOf("plan_list" to "📋", "calendar" to "🗓", "stats" to "📊", "settings" to "⚙️")
                    val labels = listOf("计划", "日历", "统计", "设置")
                    items.forEachIndexed { i, (route, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = { if (currentRoute != route) navController.navigate(route) { popUpTo("plan_list") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(labels[i], fontSize = 10.sp, fontWeight = if (currentRoute == route) FontWeight.W600 else FontWeight.W400, color = if (currentRoute == route) Accent else TextSoft) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = AccentLight)
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(navController = navController, startDestination = "plan_list", modifier = Modifier.padding(padding)) {
            composable("plan_list") {
                PlanListScreen(
                    onAddPlan = { navController.navigate("plan_edit/0") },
                    onEditPlan = { id -> navController.navigate("plan_edit/$id") }
                )
            }
            composable("calendar") { CalendarScreen(onEditPlan = { id -> navController.navigate("plan_edit/$id") }) }
            composable("stats") { StatsScreen() }
            composable("settings") { SettingsScreen() }
            composable(route = "plan_edit/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType; defaultValue = 0L })) { entry ->
                val planId = entry.arguments?.getLong("planId") ?: 0L
                PlanEditScreen(planId = if (planId == 0L) null else planId, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
        }
    }
}
