package com.example.myplan.ui.screen.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.data.SettingsManager
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.ui.theme.*

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var reminderMode by remember { mutableStateOf(SettingsManager.getReminderMode(context)) }
    var alarmRingtone by remember { mutableStateOf(SettingsManager.getAlarmRingtone(context)) }

    val modeLabel = if (reminderMode == "alarm") "通知 + 闹铃" else "通知 + 响铃"

    val ringtoneLabel = when {
        alarmRingtone == "default" -> "系统默认"
        alarmRingtone.startsWith("content://") -> {
            try { RingtoneManager.getRingtone(context, Uri.parse(alarmRingtone)).getTitle(context) }
            catch (_: Exception) { "已选择" }
        }
        else -> alarmRingtone
    }

    val alarmRingtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val value = uri?.toString() ?: "default"
            alarmRingtone = value
            SettingsManager.setAlarmRingtone(context, value)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text("⚙️ 设置", fontSize = 20.sp, fontWeight = FontWeight.W700, color = TextDark)
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 提醒方式
            SettingsRow("🔔", "提醒方式", modeLabel) {
                reminderMode = if (reminderMode == "alarm") "ring" else "alarm"
                SettingsManager.setReminderMode(context, reminderMode)
            }

            // 闹铃铃声（仅闹铃模式可见）
            AnimatedVisibility(visible = reminderMode == "alarm") {
                SettingsRow("🔊", "闹铃铃声", ringtoneLabel) {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹铃铃声")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    }
                    alarmRingtoneLauncher.launch(intent)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = Divider)

        Text(
            "🔧 权限检查",
            fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PermGuideRow("🔄", "自启动", "保持闹钟在后台运行") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
            PermGuideRow("🔋", "电池优化", "防止被省电模式拦截") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
            PermGuideRow("🔔", "悬浮通知", "通知弹出提醒") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            }
        }

        // 专注权限
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Divider)

        Text(
            "🎯 专注权限",
            fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val usageGranted = PermissionHelper.hasUsageStatsPermission(context)
            PermGuideRow("📊", "使用情况访问", if (usageGranted) "已开启" else "未开启") {
                PermissionHelper.openUsageAccessSettings(context)
            }

            val accessibilityGranted = PermissionHelper.hasAccessibilityPermission(context)
            PermGuideRow("♿", "无障碍服务", if (accessibilityGranted) "已开启" else "未开启") {
                PermissionHelper.openAccessibilitySettings(context)
            }

            val overlayGranted = PermissionHelper.hasOverlayPermission(context)
            PermGuideRow("🪟", "悬浮窗权限", if (overlayGranted) "已开启" else "未开启") {
                PermissionHelper.openOverlaySettings(context)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun SettingsRow(emoji: String, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 18.sp); Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.W500, color = TextDark)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) { Text(value, fontSize = 14.sp, color = TextSoft); Spacer(Modifier.width(4.dp)) }
            Text("›", fontSize = 18.sp, color = TextSoft)
        }
    }
}

@Composable
fun PermGuideRow(emoji: String, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AccentLight),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 18.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.W500, color = TextDark)
            Text(desc, fontSize = 12.sp, fontWeight = FontWeight.W400, color = TextSoft)
        }
        Text("›", fontSize = 18.sp, color = TextSoft)
    }
}
