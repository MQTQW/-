package com.example.myplan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.myplan.data.SettingsManager
import com.example.myplan.ui.navigation.MainApp
import com.example.myplan.ui.screen.onboarding.OnboardingScreen
import com.example.myplan.ui.screen.splash.SplashScreen
import com.example.myplan.ui.theme.Bg
import com.example.myplan.ui.theme.MyPlanTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不管用户允许还是拒绝，继续进入主页 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isFirstLaunch = SettingsManager.isFirstLaunch(this)
        val deepLinkPlanId = intent.getLongExtra("plan_id", -1L).takeIf { it > 0 }

        // 检查是否需要请求通知权限 (Android 13+)
        val needsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        } else false

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
                            // 引导结束后请求通知权限
                            if (needsPermission) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
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
