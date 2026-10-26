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
import com.example.myplan.voice.VoiceRunner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions

class MainActivity : ComponentActivity() {

    private var voiceRunner: VoiceRunner? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceRunner?.startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.myplan.notification.AlarmDiagnostics.record(this, "ui_created")
        com.example.myplan.notification.AlarmPlaybackService.dismiss(this,
            intent.getStringExtra(com.example.myplan.notification.AlarmPlaybackService.EXTRA_TOKEN))
        enableEdgeToEdge()

        val isFirstLaunch = SettingsManager.isFirstLaunch(this)
        val deepLinkPlanId = intent.getLongExtra("plan_id", -1L).takeIf { it > 0 }
        val openFocus = intent.getBooleanExtra("open_focus", false)

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
                    var showOnboarding by remember { mutableStateOf(isFirstLaunch && deepLinkPlanId == null) }
                    val runner = remember { VoiceRunner(this@MainActivity).also { voiceRunner = it } }

                    DisposableEffect(Unit) {
                        onDispose { runner.stop() }
                    }

                    if (showSplash) {
                        SplashScreen(onTimeout = { showSplash = false })
                    } else if (showOnboarding) {
                        OnboardingScreen(onStart = {
                            SettingsManager.setFirstLaunchDone(this@MainActivity)
                            showOnboarding = false
                            if (needsPermission)
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            runner.start()
                        })
                    } else {
                        LaunchedEffect(Unit) {
                            if (needsPermission)
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            runner.start()
                        }
                        val navController = rememberNavController()
                        MainApp(
                            navController = navController,
                            deepLinkPlanId = deepLinkPlanId,
                            openFocus = openFocus,
                            onVoiceNewPlan = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                    runner.startListening()
                                else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            voicePhase = runner.phase,
                            voiceUi = runner.ui,
                            onVoiceCancel = { runner.cancelFromUi() },
                            onVoiceConfirm = { runner.confirmPlan() }
                        )
                    }
                }
            }
        }
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
        voiceRunner?.stop()
        super.onStop()
    }

}
