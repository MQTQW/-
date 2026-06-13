package com.example.myplan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.myplan.data.SettingsManager
import com.example.myplan.ui.navigation.MainApp
import com.example.myplan.ui.screen.onboarding.OnboardingScreen
import com.example.myplan.ui.screen.splash.SplashScreen
import com.example.myplan.ui.theme.Bg
import com.example.myplan.ui.theme.MyPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isFirstLaunch = SettingsManager.isFirstLaunch(this)
        val deepLinkPlanId = intent.getLongExtra("plan_id", -1L).takeIf { it > 0 }

        setContent {
            MyPlanTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    var showSplash by remember { mutableStateOf(true) }
                    var showOnboarding by remember { mutableStateOf(isFirstLaunch && deepLinkPlanId == null) }

                    if (showSplash) {
                        SplashScreen(onTimeout = { showSplash = false })
                    } else if (showOnboarding) {
                        OnboardingScreen(onStart = {
                            SettingsManager.setFirstLaunchDone(this@MainActivity)
                            showOnboarding = false
                        })
                    } else {
                        val navController = rememberNavController()
                        MainApp(navController = navController, deepLinkPlanId = deepLinkPlanId)
                    }
                }
            }
        }
    }
}
