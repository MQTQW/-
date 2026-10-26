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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.FocusEnforcement
import com.example.myplan.focus.FocusLockReason
import com.example.myplan.focus.session.AndroidFocusClock
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.session.TimedUnlockResult
import com.example.myplan.focus.ui.home.focusCountdown
import com.example.myplan.focus.util.FocusConstants
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.ui.component.CompanionButton
import com.example.myplan.ui.component.CompanionCard
import com.example.myplan.ui.theme.MyPlanTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class LockOverlayActivity : ComponentActivity() {
    private var lockedPackageName by mutableStateOf("")
    private var lockedAppName by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Leave through the explicit actions below. */ }
        })
        readTarget(intent)
        setContent {
            MyPlanTheme(this) {
                val app = applicationContext as MyPlanApp
                val credits by app.unlockCredits.collectAsState()
                val scope = rememberCoroutineScope()
                val pkg = lockedPackageName
                var requestId by rememberSaveable(pkg) { mutableStateOf(UUID.randomUUID().toString()) }
                var reason by remember(pkg) { mutableStateOf<FocusLockReason?>(null) }
                var session by remember(pkg) { mutableStateOf<FocusSessionEntity?>(null) }
                var remaining by remember(pkg) { mutableLongStateOf(0) }
                var checking by remember(pkg) { mutableStateOf(true) }
                var busy by remember(pkg) { mutableStateOf(false) }
                var message by remember(pkg) { mutableStateOf<String?>(null) }
                var permissionReady by remember(pkg) { mutableStateOf(false) }
                LaunchedEffect(pkg) {
                    val clock = AndroidFocusClock(app)
                    while (true) {
                        try {
                            val snapshot = withContext(Dispatchers.IO) { FocusEnforcement(app).snapshot() }
                            reason = snapshot.reason(pkg)
                            session = withContext(Dispatchers.IO) { app.focusSessionRepository.current() }
                            remaining = session?.remainingAt(clock.now()) ?: 0
                            permissionReady = PermissionHelper.hasUsageStatsPermission(app) &&
                                PermissionHelper.hasAccessibilityPermission(app)
                            checking = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            message = "暂时无法读取限制状态，请返回专注页面检查。"
                            checking = false
                        }
                        delay(1000)
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LockOverlayScreen(
                        appName = lockedAppName,
                        reason = reason,
                        sessionName = (session?.stepTitle ?: session?.planName).orEmpty(),
                        remainingMillis = remaining,
                        checking = checking,
                        permissionReady = permissionReady,
                        credits = credits,
                        busy = busy,
                        message = message,
                        onGoHome = {
                            startActivity(Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            finish()
                        },
                        onDoPlan = {
                            startActivity(Intent(this, com.example.myplan.MainActivity::class.java).apply {
                                putExtra("open_focus", true)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            })
                            finish()
                        },
                        onUnlockWithCredit = {
                            if (!busy) {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        check(PermissionHelper.hasUsageStatsPermission(app) &&
                                            PermissionHelper.hasAccessibilityPermission(app)) {
                                            "请先在专注页面开启拦截所需权限。"
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            val snapshot = FocusEnforcement(app).snapshot()
                                            check(snapshot.reason(pkg) != FocusLockReason.SESSION) {
                                                "该应用正在专注限制中，请先暂停或结束专注。"
                                            }
                                            check(snapshot.reason(pkg) == FocusLockReason.DAILY_LIMIT ||
                                                pkg in snapshot.timedUnlockedPackages) { "该应用的限制状态已变化，请返回专注页面。" }
                                            app.timedUnlockRepository.redeem(pkg, requestId)
                                        }
                                        when (result) {
                                            is TimedUnlockResult.Granted -> {
                                                if (result.lease.remainingAt(AndroidFocusClock(app).now()) > 0) {
                                                    sendBroadcast(Intent(FocusConstants.ACTION_USAGE_UPDATED).setPackage(packageName))
                                                    finish()
                                                } else {
                                                    message = "这次兑换已过期，未重复扣分。再次点击可重新兑换。"
                                                    requestId = UUID.randomUUID().toString()
                                                }
                                            }
                                            TimedUnlockResult.InsufficientCredits -> message = "积分不足，完成计划后再来兑换。"
                                            TimedUnlockResult.BlockedByFocus -> message = "该应用正在专注限制中，请先暂停或结束专注。"
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (e: Exception) { message = e.message?.takeIf { it.length < 100 } ?: "暂时没能兑换，请再试一次。" }
                                    finally { busy = false }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
    }

    private fun readTarget(intent: Intent) {
        lockedPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        lockedAppName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
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
    reason: FocusLockReason?,
    sessionName: String,
    remainingMillis: Long,
    checking: Boolean,
    permissionReady: Boolean,
    credits: Int,
    busy: Boolean,
    message: String?,
    onGoHome: () -> Unit,
    onDoPlan: () -> Unit,
    onUnlockWithCredit: () -> Unit
) {
    val isSession = reason == FocusLockReason.SESSION
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(104.dp)) {
            Icon(if (isSession) Icons.Rounded.CenterFocusStrong else Icons.Rounded.HourglassEmpty,
                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(28.dp))
        }
        Text(if (checking) "正在确认限制" else if (isSession) "先专注这一件事" else if (reason == null) "限制状态已更新" else "今天先到这里吧",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        CompanionCard {
            Text(appName.ifBlank { "这个应用" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(if (checking) "正在读取当前状态…" else if (isSession) "专注期间暂时限制使用" else if (reason == null) "返回专注页面，查看最新规则。" else "已达到今日使用限额",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isSession) {
                Spacer(Modifier.height(18.dp))
                Text(sessionName, style = MaterialTheme.typography.titleMedium)
                Text(focusCountdown(remainingMillis), fontSize = 38.sp, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text("暂停或结束专注后恢复原有限额；专注期间不能用积分绕过限制。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (reason == FocusLockReason.DAILY_LIMIT) {
                Spacer(Modifier.height(12.dp))
                Text("明天自动重置，也可以用积分兑换一段使用时间。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!isSession && reason == FocusLockReason.DAILY_LIMIT) CompanionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("可用积分", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$credits", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text("1 积分兑换 10 分钟，立即开始计时；离开应用也会继续计时，到期恢复原有限额。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!permissionReady && !checking) Text("拦截权限未就绪，请在专注页面检查。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CompanionButton(if (isSession) "返回专注" else "去完成计划", onClick = onDoPlan,
                modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.TaskAlt, enabled = !busy)
            if (!isSession && reason == FocusLockReason.DAILY_LIMIT) FilledTonalButton(
                onClick = onUnlockWithCredit, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(14.dp), enabled = credits > 0 && permissionReady && !checking && !busy) {
                Icon(Icons.Rounded.LockOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "正在兑换…" else if (credits > 0) "使用 1 积分解锁 10 分钟" else "完成计划后获得积分")
            }
            OutlinedButton(onClick = onGoHome, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.Home, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("返回桌面")
            }
        }
    }
}
