package com.example.myplan.ui.screen.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myplan.data.PlanBackup
import com.example.myplan.data.SettingsManager
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onDetailScreenChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionRevision by remember { mutableIntStateOf(0) }
    var permissionsPage by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var reminderMode by remember { mutableStateOf(SettingsManager.getReminderMode(context)) }
    var alarmRingtone by remember { mutableStateOf(SettingsManager.getAlarmRingtone(context)) }
    var notificationRingtone by remember { mutableStateOf(SettingsManager.getNotificationRingtone(context)) }
    var vibration by remember { mutableStateOf(SettingsManager.getVibrationMode(context)) }
    var darkMode by remember { mutableStateOf(SettingsManager.isDarkMode(context)) }
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    LaunchedEffect(permissionsPage) {
        scrollState.scrollTo(0)
        onDetailScreenChanged(permissionsPage)
    }
    DisposableEffect(Unit) {
        onDispose { onDetailScreenChanged(false) }
    }

    BackHandler(enabled = permissionsPage) { permissionsPage = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationGranted = remember(permissionRevision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val alarmGranted = remember(permissionRevision) { AlarmScheduler(context).canScheduleExactAlarms() }
    val batteryGranted = remember(permissionRevision) { PermissionHelper.isIgnoringBatteryOptimizations(context) }
    val usageGranted = remember(permissionRevision) { PermissionHelper.hasUsageStatsPermission(context) }
    val accessibilityGranted = remember(permissionRevision) { PermissionHelper.hasAccessibilityPermission(context) }
    val overlayGranted = remember(permissionRevision) { PermissionHelper.hasOverlayPermission(context) }
    val missingPermissions = listOf(notificationGranted, alarmGranted, batteryGranted, usageGranted, accessibilityGranted, overlayGranted).count { !it }

    fun openSettings(action: () -> Unit) {
        try { action() }
        catch (_: Exception) { Toast.makeText(context, "暂时无法打开，请到系统设置中检查", Toast.LENGTH_LONG).show() }
    }
    fun ringtoneLabel(value: String): String = when {
        value == "default" -> "系统默认"
        value == "silent" -> "静音"
        value.startsWith("content://") -> try {
            RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context) ?: "已选择"
        } catch (_: Exception) { "已选择" }
        else -> value
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val json = PlanBackup.export(PlanDatabase.getInstance(context))
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(context, "计划备份已导出", Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "导出失败，请重试", Toast.LENGTH_LONG).show() }
            finally { exporting = false }
        }
    }
    // Capture the selected mode when opening the Android picker so its result updates the same setting.
    var choosingAlarmRingtone by rememberSaveable { mutableStateOf(true) }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val value = uri?.toString() ?: "silent"
            if (choosingAlarmRingtone) {
                alarmRingtone = value
                SettingsManager.setAlarmRingtone(context, value)
            } else {
                notificationRingtone = value
                SettingsManager.setNotificationRingtone(context, value)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.background)
            .verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (permissionsPage) {
            CompanionHeader("让提醒更安心", "权限检查", onBack = { permissionsPage = false }, contentPadding = PaddingValues(vertical = 8.dp))
            Text("提醒需要系统的支持，专注权限可在使用时按需开启。", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
            CompanionSectionTitle("提醒权限")
            CompanionCard(contentPadding = 14.dp) {
                PermissionRow(Icons.Outlined.Notifications, "通知", "让计划出现在通知栏", notificationGranted) {
                    openSettings { PermissionHelper.openNotificationSettings(context) }
                }
                HorizontalDivider(color = colors.outlineVariant)
                PermissionRow(Icons.Outlined.Alarm, "闹钟和提醒", "允许安排准确的提醒时间", alarmGranted) {
                    openSettings {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}")))
                        else PermissionHelper.openAppSettings(context)
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                PermissionRow(Icons.Outlined.BatteryChargingFull, "后台运行", "减少后台任务被系统暂停的情况", batteryGranted) {
                    openSettings { PermissionHelper.openBatteryOptimizationSettings(context) }
                }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.RestartAlt, "自启动设置", "按需检查") {
                    openSettings { PermissionHelper.openAppSettings(context) }
                }
            }
            CompanionSectionTitle("专注权限")
            CompanionCard(contentPadding = 14.dp) {
                PermissionRow(Icons.Outlined.BarChart, "使用情况访问", "查看使用时长，计算每日限额", usageGranted) {
                    openSettings { PermissionHelper.openUsageAccessSettings(context) }
                }
                HorizontalDivider(color = colors.outlineVariant)
                PermissionRow(Icons.Outlined.AccessibilityNew, "无障碍服务", "识别受限应用并显示休息提醒", accessibilityGranted) {
                    openSettings { PermissionHelper.openAccessibilitySettings(context) }
                }
                HorizontalDivider(color = colors.outlineVariant)
                PermissionRow(Icons.Outlined.Layers, "悬浮窗", "在受限应用上显示休息提醒", overlayGranted) {
                    openSettings { PermissionHelper.openOverlaySettings(context) }
                }
            }
            CompanionNote("不同手机的后台设置会有差异，可先用「测试提醒」检查锁屏后的效果。")
            CompanionButton("重新检查", onClick = { permissionRevision++ }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Refresh)
        } else {
            CompanionHeader("更合你的习惯", "设置", contentPadding = PaddingValues(vertical = 8.dp), trailing = {
                Icon(Icons.Outlined.Tune, null, tint = colors.primary, modifier = Modifier.size(28.dp))
            })
            CompanionSectionTitle("提醒与声音")
            CompanionCard(contentPadding = 14.dp) {
                SettingsItem(Icons.Outlined.NotificationsActive, "提醒方式", if (reminderMode == "alarm") "通知＋闹铃" else "通知＋响铃") { dialog = "mode" }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.MusicNote, if (reminderMode == "alarm") "闹铃铃声" else "通知铃声",
                    ringtoneLabel(if (reminderMode == "alarm") alarmRingtone else notificationRingtone)) {
                    choosingAlarmRingtone = reminderMode == "alarm"
                    val value = if (choosingAlarmRingtone) alarmRingtone else notificationRingtone
                    val type = if (choosingAlarmRingtone) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                    val currentUri = when (value) {
                        "silent" -> null
                        "default" -> RingtoneManager.getDefaultUri(type)
                        else -> Uri.parse(value)
                    }
                    openSettings {
                        ringtoneLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                        })
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.Vibration, "震动", when (vibration) {
                    "off", "关闭" -> "关闭"
                    "short", "短震动" -> "短震动"
                    else -> "长震动"
                }) { dialog = "vibration" }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.Schedule, "测试提醒", "1 分钟后") {
                    try {
                        check(notificationGranted) { "请先开启通知权限" }
                        AlarmScheduler(context).scheduleTestAlarm()
                        Toast.makeText(context, "将在 1 分钟后提醒，可锁屏测试", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { Toast.makeText(context, e.message ?: "暂时无法安排提醒", Toast.LENGTH_LONG).show() }
                }
            }
            CompanionSectionTitle("安心使用")
            CompanionCard(contentPadding = 14.dp) {
                SettingsItem(Icons.Outlined.VerifiedUser, "权限检查", if (missingPermissions == 0) "已开启" else "$missingPermissions 项待检查") { permissionsPage = true }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.FileDownload, "导出计划备份", if (exporting) "正在导出…" else "计划与打卡记录", enabled = !exporting) { dialog = "backup" }
            }
            CompanionSectionTitle("外观与帮助")
            CompanionCard(contentPadding = 14.dp) {
                SettingsItem(Icons.Outlined.DarkMode, "显示风格", when (darkMode) {
                    "开启" -> "深色"
                    "关闭" -> "浅色"
                    else -> "跟随系统"
                }) { dialog = "appearance" }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsItem(Icons.Outlined.HelpOutline, "使用小贴士", "") { dialog = "help" }
            }
            Text("MY PLAN\n把重要的小事，轻轻记住", Modifier.fillMaxWidth().padding(vertical = 10.dp),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
    }

    when (dialog) {
        "mode" -> ChoiceDialog("提醒方式", listOf("alarm" to "通知＋闹铃", "ring" to "通知＋响铃"), reminderMode,
            onDismiss = { dialog = null }) { value -> reminderMode = value; SettingsManager.setReminderMode(context, value); dialog = null }
        "vibration" -> ChoiceDialog("提醒震动", listOf("long" to "长震动", "short" to "短震动", "off" to "关闭"),
            when (vibration) { "关闭" -> "off"; "短震动" -> "short"; "长震动" -> "long"; else -> vibration }, onDismiss = { dialog = null }) {
            vibration = it; SettingsManager.setVibrationMode(context, it); dialog = null
        }
        "appearance" -> ChoiceDialog("显示风格", listOf("system" to "跟随系统", "关闭" to "浅色", "开启" to "深色"), darkMode,
            onDismiss = { dialog = null }) { darkMode = it; SettingsManager.setDarkMode(context, it); dialog = null }
        "backup" -> AlertDialog(onDismissRequest = { dialog = null }, icon = { Icon(Icons.Outlined.FileDownload, null) },
            title = { Text("导出计划备份") }, text = { Text("将计划、提醒时间和打卡历史保存为 JSON 文件，接下来请选择保存位置。") },
            confirmButton = { TextButton(onClick = { dialog = null; exportLauncher.launch("MY_PLAN-${java.time.LocalDate.now()}.json") }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        "help" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("留一点从容给自己") },
            text = { Text("• 在首页新建计划，设置每天、周一至周五或自定义星期提醒。\n\n• 一项计划可以添加多个提醒时间。\n\n• 完成计划可获得积分，用于解锁受限应用。\n\n• 如果锁屏后没有提醒，请检查通知、闹钟和后台运行设置。") },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } })
    }
}

@Composable
private fun SettingsItem(icon: ImageVector, title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = colors.primary)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 116.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, description: String, granted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 76.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        CompanionTag(if (granted) "已开启" else "去设置", warning = !granted)
    }
}

@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, selected: String,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            choices.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == value, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
