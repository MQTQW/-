package com.example.myplan.focus.ui.focusnav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myplan.focus.ui.home.HomeScreen
import com.example.myplan.focus.ui.permission.PermissionGuideScreen

sealed class FocusScreen(val route: String, val title: String) {
    object Home : FocusScreen("focus_home", "专注首页")
    object Permission : FocusScreen("focus_permission", "权限引导")
}

private data class FocusNavItem(
    val screen: FocusScreen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
)

private val focusNavItems = listOf(
    FocusNavItem(FocusScreen.Home, Icons.Filled.Home, Icons.Outlined.Home, "概览"),
    FocusNavItem(FocusScreen.Permission, Icons.Filled.Shield, Icons.Outlined.Shield, "权限"),
)

@Composable
fun FocusNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in focusNavItems.map { it.screen.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    focusNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = FocusScreen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(FocusScreen.Home.route) {
                HomeScreen(navController = navController)
            }
            composable(FocusScreen.Permission.route) {
                PermissionGuideScreen(navController = navController)
            }
        }
    }
}
