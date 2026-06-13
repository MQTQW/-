package com.example.myplan.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.myplan.ui.screen.planedit.PlanEditScreen
import com.example.myplan.ui.screen.planlist.PlanListScreen

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = "plan_list"
    ) {
        composable("plan_list") {
            PlanListScreen(
                onAddPlan = {
                    navController.navigate("plan_edit/0")
                },
                onEditPlan = { id ->
                    navController.navigate("plan_edit/$id")
                }
            )
        }

        composable(
            route = "plan_edit/{planId}",
            arguments = listOf(
                navArgument("planId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { backStackEntry ->
            val planId = backStackEntry.arguments?.getLong("planId") ?: 0L
            PlanEditScreen(
                planId = if (planId == 0L) null else planId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
    }
}
