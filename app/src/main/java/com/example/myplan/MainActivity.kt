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
import com.example.myplan.ui.theme.Bg
import com.example.myplan.ui.theme.MyPlanTheme
import com.example.myplan.voice.VoiceRunner

class MainActivity : ComponentActivity() {

    private var voiceRunner: VoiceRunner? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isFirstLaunch = SettingsManager.isFirstLaunch(this)
        val deepLinkPlanId = intent.getLongExtra("plan_id", -1L).takeIf { it > 0 }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        val needsPermission = permissionsToRequest.isNotEmpty()

        setContent {
            MyPlanTheme(context = this@MainActivity) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
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
                            runner.startListening()
                        })
                    } else {
                        LaunchedEffect(Unit) {
                            if (needsPermission)
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            runner.start()
                            runner.startListening()
                        }
                        val navController = rememberNavController()
                        MainApp(
                            navController = navController,
                            deepLinkPlanId = deepLinkPlanId,
                            onVoiceNewPlan = { runner.startListening() },
                            voicePhase = runner.phase
                        )
                    }
                }
            }
        }
    }

}
