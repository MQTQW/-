package com.example.myplan.focus.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.service.UsageMonitorService
import com.example.myplan.focus.util.FocusConstants
import com.example.myplan.ui.component.CompanionButton
import com.example.myplan.ui.component.CompanionCard
import com.example.myplan.ui.theme.MyPlanTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

class LockOverlayActivity : ComponentActivity() {
    private var lockedPackageName: String = ""
    private var lockedAppName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Leave through the explicit actions below. */ }
        })
        lockedPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        lockedAppName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        setContent {
            MyPlanTheme(this) {
              Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LockOverlayScreen(
                    appName = lockedAppName,
                    onGoHome = {
                        startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        finish()
                    },
                    onDoPlan = {
                        startActivity(Intent(this, com.example.myplan.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                        finish()
                    },
                    onUnlockWithCredit = {
                        val app = applicationContext as MyPlanApp
                        lifecycleScope.launch {
                            if (app.consumeCredit(lockedPackageName))
                                UsageMonitorService.unlockApp(this@LockOverlayActivity, lockedPackageName)
                            finish()
                        }
                    },
                    unlockCredits = (applicationContext as MyPlanApp).unlockCredits
                )
              }
            }
        }
    }

    override fun onDestroy() {
        sendBroadcast(Intent(FocusConstants.ACTION_USAGE_UPDATED).apply {
            putExtra("overlay_dismissed", true)
            setPackage(applicationContext.packageName)
        })
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"

        fun show(context: Context, packageName: String, appName: String) {
            context.startActivity(Intent(context, LockOverlayActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }
}

@Composable
fun LockOverlayScreen(
    appName: String,
    onGoHome: () -> Unit,
    onDoPlan: () -> Unit,
    onUnlockWithCredit: () -> Unit,
    unlockCredits: StateFlow<Int>
) {
    val credits by unlockCredits.collectAsState()
    var unlocking by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(60_000)
        }
    }
    val remaining = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
    val resetText = if (remaining.toHours() > 0) "${remaining.toHours()} 小时 ${remaining.toMinutes() % 60} 分钟后重置"
        else "${remaining.toMinutes().coerceAtLeast(1)} 分钟后重置"

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(112.dp)) {
            Icon(Icons.Rounded.HourglassEmpty, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(30.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("今天先到这里吧", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("把一点时间，留给想做的事。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        CompanionCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.Pause, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appName.ifBlank { "这个应用" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("已达到今日使用限额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().height(5.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
            Spacer(Modifier.height(10.dp))
            Text("新的一天自动重置 · $resetText", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompanionCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("可用积分", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$credits", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text("完成今天的计划，可以获得解锁积分。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CompanionButton("去完成今日计划", onClick = onDoPlan, modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.TaskAlt, enabled = !unlocking)
            FilledTonalButton(
                onClick = { if (!unlocking) { unlocking = true; onUnlockWithCredit() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp),
                enabled = credits > 0 && !unlocking
            ) {
                Icon(Icons.Rounded.LockOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (unlocking) "正在解锁…" else if (credits > 0) "使用 1 积分解锁" else "完成计划后获得解锁积分", fontWeight = FontWeight.Medium)
            }
            OutlinedButton(onClick = onGoHome, enabled = !unlocking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.Home, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("返回桌面", fontWeight = FontWeight.Medium)
            }
        }
    }
}
