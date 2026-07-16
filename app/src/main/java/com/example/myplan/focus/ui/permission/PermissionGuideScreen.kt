package com.example.myplan.focus.ui.permission

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.ui.theme.Complete
import com.example.myplan.ui.theme.Accent

data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val check: (android.content.Context) -> Boolean,
    val open: (android.content.Context) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(navController: NavController) {
    val context = LocalContext.current

    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @Suppress("UNUSED_EXPRESSION")
    val trigger = refreshKey

    val permissions = remember {
        listOf(
            PermissionItem("usage", "使用情况访问", "用于追踪每个应用的使用时长",
                Icons.Filled.AccessTime,
                check = { PermissionHelper.hasUsageStatsPermission(it) },
                open = { PermissionHelper.openUsageAccessSettings(it) }),
            PermissionItem("accessibility", "无障碍服务", "用于检测应用打开并锁定超时应用",
                Icons.Filled.Accessibility,
                check = { PermissionHelper.hasAccessibilityPermission(it) },
                open = { PermissionHelper.openAccessibilitySettings(it) }),
            PermissionItem("overlay", "悬浮窗权限", "用于显示锁定遮罩提醒页面",
                Icons.Filled.Layers,
                check = { PermissionHelper.hasOverlayPermission(it) },
                open = { PermissionHelper.openOverlaySettings(it) }),
            PermissionItem("battery", "忽略电池优化", "防止系统在后台关闭监控服务",
                Icons.Filled.BatterySaver,
                check = { PermissionHelper.isIgnoringBatteryOptimizations(it) },
                open = { PermissionHelper.openBatteryOptimizationSettings(it) }),
            PermissionItem("notification", "通知权限", "用于在时间快用完时发送预警提醒",
                Icons.Filled.Notifications,
                check = { PermissionHelper.hasNotificationPermission(it) },
                open = { PermissionHelper.openNotificationSettings(it) })
        )
    }

    val allGranted = permissions.all { it.check(context) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (allGranted) Complete.copy(alpha = 0.1f) else Accent.copy(alpha = 0.1f))) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp),
                    color = if (allGranted) Complete.copy(alpha = 0.15f) else Accent.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)) {
                    Icon(if (allGranted) Icons.Filled.CheckCircle else Icons.Filled.Shield, null,
                        tint = if (allGranted) Complete else Accent, modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("权限设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (allGranted) "所有权限已就绪" else "还需开启 ${permissions.count { !it.check(context) }} 项权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allGranted) Complete else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            permissions.forEach { perm ->
                val isGranted = perm.check(context)
                Card(shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isGranted) Complete.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = if (isGranted) Complete.copy(alpha = 0.12f) else Accent.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)) {
                            Icon(perm.icon, null, tint = if (isGranted) Complete else Accent, modifier = Modifier.padding(9.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(perm.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.width(8.dp))
                                if (isGranted) Icon(Icons.Filled.CheckCircle, "已授予", tint = Complete, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(perm.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!isGranted) {
                            FilledTonalButton(onClick = { perm.open(context) }, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(8.dp)) {
                                Text("去设置", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.08f))) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("华为/小米/OPPO/vivo 用户：请在「设置 → 应用」中将 MY PLAN 设为允许自启动，关闭省电策略，以确保后台监控不中断。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
