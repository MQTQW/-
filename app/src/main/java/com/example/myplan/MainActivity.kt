package com.example.myplan

import android.Manifest
import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import com.example.myplan.ui.theme.MyPlanTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions

class MainActivity : ComponentActivity() {
    private var requestedPlanId by mutableStateOf<Long?>(null)
    private var requestedFocus by mutableStateOf(false)
    private var requestedAiSettings by mutableStateOf(false)
    private var launchRequestKey by mutableIntStateOf(0)

    private fun readLaunchIntent(source: Intent) {
        requestedPlanId = source.getLongExtra("plan_id", -1L).takeIf { it > 0 }
        requestedFocus = source.getBooleanExtra("open_focus", false)
        requestedAiSettings = source.getBooleanExtra("open_ai_settings", false)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.myplan.notification.AlarmDiagnostics.record(this, "ui_created")
        com.example.myplan.notification.AlarmPlaybackService.dismiss(this,
            intent.getStringExtra(com.example.myplan.notification.AlarmPlaybackService.EXTRA_TOKEN))
        enableEdgeToEdge()

        val isFirstLaunch = SettingsManager.isFirstLaunch(this)
        launchRequestKey = savedInstanceState?.getInt("launch_request_key") ?: 0
        readLaunchIntent(intent)

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needsPermission = permissionsToRequest.isNotEmpty()

        setContent {
            MyPlanTheme(context = this@MainActivity) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showSplash by remember { mutableStateOf(true) }
                    var showOnboarding by remember { mutableStateOf(isFirstLaunch && requestedPlanId == null && !requestedAiSettings) }
                    if (showSplash) {
                        SplashScreen(onTimeout = { showSplash = false })
                    } else if (showOnboarding) {
                        OnboardingScreen(onStart = {
                            SettingsManager.setFirstLaunchDone(this@MainActivity)
                            showOnboarding = false
                            if (needsPermission)
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                        })
                    } else {
                        LaunchedEffect(Unit) {
                            if (needsPermission)
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                        }
                        val navController = rememberNavController()
                        MainApp(
                            navController = navController,
                            deepLinkPlanId = requestedPlanId,
                            openFocus = requestedFocus,
                            openAiSettings = requestedAiSettings,
                            launchRequestKey = launchRequestKey
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.example.myplan.notification.AlarmPlaybackService.dismiss(this,
            intent.getStringExtra(com.example.myplan.notification.AlarmPlaybackService.EXTRA_TOKEN))
        readLaunchIntent(intent)
        launchRequestKey++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("launch_request_key", launchRequestKey)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        com.example.myplan.notification.AlarmDiagnostics.record(this, "ui_resumed")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DailyRollover.ensureToday(this@MainActivity)
                val repo = PlanRepository(PlanDatabase.getInstance(this@MainActivity))
                val actions = PlanActions(repo, AlarmScheduler(this@MainActivity))
                actions.restoreAll()
            } catch (e: Exception) { android.util.Log.e("MainActivity", "Alarm recovery failed", e) }
        }
    }

    override fun onStop() {
        com.example.myplan.notification.AlarmDiagnostics.record(this, "ui_stopped")
        super.onStop()
    }

}
