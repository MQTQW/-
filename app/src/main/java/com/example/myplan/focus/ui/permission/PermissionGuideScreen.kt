package com.example.myplan.focus.ui.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.component.*

data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val check: (Context) -> Boolean,
    val open: (Context) -> Unit
)

@Composable
fun PermissionGuideScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissions = remember {
        listOf(
            PermissionItem("notification", "通知", "让计划出现在通知栏", Icons.Rounded.NotificationsNone,
                check = PermissionHelper::hasNotificationPermission, open = PermissionHelper::openNotificationSettings),
            PermissionItem("alarm", "闹钟和提醒", "允许应用安排准确的提醒时间", Icons.Rounded.Alarm,
                check = { AlarmScheduler(it).canScheduleExactAlarms() },
                open = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        it.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}")))
                    else PermissionHelper.openAppSettings(it)
                }),
            PermissionItem("battery", "后台运行", "减少后台任务被系统暂停的情况", Icons.Rounded.BatteryStd,
                check = PermissionHelper::isIgnoringBatteryOptimizations, open = PermissionHelper::openBatteryOptimizationSettings),
            PermissionItem("usage", "使用情况访问", "查看应用使用时长，计算每日限额", Icons.Rounded.BarChart,
                check = PermissionHelper::hasUsageStatsPermission, open = PermissionHelper::openUsageAccessSettings),
            PermissionItem("accessibility", "无障碍服务", "识别已达限额的应用并显示限制页", Icons.Rounded.AccessibilityNew,
                check = PermissionHelper::hasAccessibilityPermission, open = PermissionHelper::openAccessibilitySettings),
            PermissionItem("overlay", "悬浮窗（可选）", "本版专注拦截无需开启，可按系统兼容情况设置", Icons.Rounded.Layers,
                check = PermissionHelper::hasOverlayPermission, open = PermissionHelper::openOverlaySettings)
        )
    }
    val granted = remember(refreshKey, context) { permissions.associate { it.key to it.check(context) } }
    val corePermissions = permissions.filterNot { it.key in setOf("battery", "overlay") }
    val grantedCount = corePermissions.count { granted[it.key] == true }
    val openPermission: (PermissionItem) -> Unit = { permission ->
        try {
            permission.open(context)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "这个系统没有对应的设置页面，请从应用设置中检查。", Toast.LENGTH_LONG).show()
            PermissionHelper.openAppSettings(context)
        } catch (_: SecurityException) {
            Toast.makeText(context, "请从系统设置中检查这项权限。", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompanionHeader("让提醒更安心", onBack = onBack ?: { navController.popBackStack(); Unit })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("按需要开启，返回后会重新检查状态。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompanionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(46.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(if (grantedCount == corePermissions.size) Icons.Rounded.VerifiedUser else Icons.Rounded.Shield,
                            null, modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (grantedCount == corePermissions.size) "核心权限已就绪" else "已开启 $grantedCount 项，共 ${corePermissions.size} 项核心权限",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text("根据使用的功能选择开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            CompanionSectionTitle("提醒权限")
            PermissionGroup(permissions.take(3), granted, openPermission)
            CompanionSectionTitle("专注权限")
            PermissionGroup(permissions.drop(3), granted, openPermission)
            CompanionNote("专注拦截需要使用情况访问和 MY PLAN 无障碍服务，悬浮窗非必需。部分手机还需按系统情况检查自启动和后台省电设置。")
            CompanionButton("重新检查", onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PermissionGroup(permissions: List<PermissionItem>, granted: Map<String, Boolean>, onOpen: (PermissionItem) -> Unit) {
    CompanionCard(contentPadding = 0.dp) {
        permissions.forEachIndexed { index, permission ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(permission.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(permission.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(permission.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                if (granted[permission.key] == true) {
                    Text("已开启", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp))
                } else {
                    TextButton(onClick = { onOpen(permission) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去设置") }
                }
            }
        }
    }
}
