package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myplan.ui.theme.*

@Composable
fun DeleteConfirmationDialog(
    planName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(300.dp), shape = RoundedCornerShape(RadiusDialog.dp), color = Color.White, shadowElevation = 24.dp) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Delete, null, tint = TextSoft, modifier = Modifier.size(40.dp))
                Text("确定删除？", fontSize = 18.sp, fontWeight = FontWeight.W700, color = TextDark)
                Text("「$planName」及其所有\n提醒时间将被永久删除", fontSize = 14.sp, fontWeight = FontWeight.W400, color = TextSoft, textAlign = TextAlign.Center, lineHeight = 21.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Divider)) { Text("取消", color = TextSoft, fontWeight = FontWeight.W600) }
                    TextButton(onClick = onConfirm, modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Delete)) { Text("删除", color = Color.White, fontWeight = FontWeight.W600) }
                }
            }
        }
    }
}
