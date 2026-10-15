package com.example.myplan.focus.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.util.FocusConstants
import com.example.myplan.ui.theme.Accent
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback

class LockOverlayActivity : ComponentActivity() {

    private var packageName: String = ""
    private var appName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Use the explicit unlock or home buttons. */ }
        })

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: ""

        setContent {
            LockOverlayScreen(
                appName = appName,
                onGoHome = {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                    finish()
                },
                onDoPlan = {
                    // Navigate to MY PLAN to complete plans and earn credits
                    val appIntent = Intent(this, com.example.myplan.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(appIntent)
                    finish()
                },
                onUnlockWithCredit = {
                    val app = applicationContext as MyPlanApp
                    lifecycleScope.launch {
                        if (app.consumeCredit(packageName)) {
                            UsageMonitorService.unlockApp(this@LockOverlayActivity, packageName)
                        }
                        finish()
                    }
                },
                unlockCredits = (applicationContext as MyPlanApp).unlockCredits
            )
        }
    }

    override fun onDestroy() {
        val intent = Intent(FocusConstants.ACTION_USAGE_UPDATED).apply {
            putExtra("overlay_dismissed", true)
        }
        sendBroadcast(intent.setPackage(applicationContext.packageName))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"

        fun show(context: Context, packageName: String, appName: String) {
            val intent = Intent(context, LockOverlayActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun LockOverlayScreen(
    appName: String,
    onGoHome: () -> Unit,
    onDoPlan: () -> Unit,
    onUnlockWithCredit: () -> Unit,
    unlockCredits: kotlinx.coroutines.flow.StateFlow<Int>
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val pulseScale by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )

    val now = Calendar.getInstance()
    val midnight = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    val remainingMs = midnight.timeInMillis - now.timeInMillis
    val remainingHours = remainingMs / (1000 * 60 * 60)
    val remainingMinutes = (remainingMs % (1000 * 60 * 60)) / (1000 * 60)

    val quotes = remember {
        listOf(
            "自律是自由的第一步。",
            "每一次克制，都是对更好的自己投票。",
            "别让算法偷走你的时间。",
            "你是手机的主人，不是仆人。",
            "专注当下，就是最好的行动。",
            "今天的克制，是为了明天的自由。",
            "比刷视频更快乐的，是掌控人生。"
        )
    }
    val quote = remember { quotes[Random().nextInt(quotes.size)] }

    val credits by unlockCredits.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF3D1C2E),
                        Color(0xFF1A1124),
                        Color(0xFF121218)
                    )
                )
            )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(400)) +
                    scaleIn(initialScale = 0.9f, animationSpec = tween(400, easing = EaseOutBack))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Lock Icon
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "已锁定",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("时间到", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "「$appName」今日使用时长已用完",
                    fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Credits display
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("今日积分: $credits", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(16.dp))
                        Icon(Icons.Filled.Schedule, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${remainingHours}h${remainingMinutes}m后重置", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    "「$quote」", fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Complete Plan button (primary)
                Button(
                    onClick = onDoPlan,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Filled.TaskAlt, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("完成今日计划", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Unlock with credit button
                Button(
                    onClick = onUnlockWithCredit,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    ),
                    enabled = credits > 0
                ) {
                    Icon(Icons.Filled.LockOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (credits > 0) "使用 1 积分解锁" else "积分不足，请先完成计划",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Go Home
                OutlinedButton(
                    onClick = onGoHome,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Home, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("返回桌面", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// Helper: UsageMonitorService reference for unlock from overlay
private object UsageMonitorService {
    fun unlockApp(context: Context, packageName: String) {
        com.example.myplan.focus.service.UsageMonitorService.unlockApp(context, packageName)
    }
}
