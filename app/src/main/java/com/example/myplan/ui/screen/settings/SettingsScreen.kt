package com.example.myplan.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.data.SettingsManager
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ringtone by remember { mutableStateOf(SettingsManager.getNotificationRingtone(context)) }
    var vibration by remember { mutableStateOf(SettingsManager.getVibrationMode(context)) }
    var darkMode by remember { mutableStateOf(SettingsManager.isDarkMode(context)) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Ringtone picker launcher
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let {
                ringtone = it.toString()
                SettingsManager.setNotificationRingtone(context, it.toString())
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text("⚙️ 设置", fontSize = 20.sp, fontWeight = FontWeight.W700, color = TextDark)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsRow("🔔", "通知铃声", "选择铃声") {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择通知铃声")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                }
                ringtoneLauncher.launch(intent)
            }
            SettingsRow("📳", "震动模式", vibration) {
                vibration = when (vibration) { "长震动" -> "短震动"; "短震动" -> "关闭"; else -> "长震动" }
                SettingsManager.setVibrationMode(context, vibration)
            }
            SettingsRow("🌙", "深色模式", darkMode) {
                darkMode = when (darkMode) { "跟随系统" -> "开启"; "开启" -> "关闭"; else -> "跟随系统" }
                SettingsManager.setDarkMode(context, darkMode)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Divider)
            SettingsRow("🗑", "清除所有数据", "") { showClearDialog = true }
            SettingsRow("ℹ️", "关于 MY PLAN", "v1.0") {}
        }
    }

    if (showClearDialog) {
        AlertDialog(onDismissRequest = { showClearDialog = false }, title = { Text("确定清除所有数据？") }, text = { Text("此操作不可撤销") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { PlanDatabase.getInstance(context).clearAllTables() }; showClearDialog = false
                }) { Text("清除", color = Delete) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SettingsRow(emoji: String, label: String, value: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
