package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myplan.ui.theme.*

/**
 * 时间选择器 — 小时:分钟 + 取消/确定
 */
@Composable
fun TimePickerDialog(
    initialHour: Int = 8,
    initialMinute: Int = 30,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember { mutableStateOf(initialHour.toString().padStart(2, '0')) }
    var minute by remember { mutableStateOf(initialMinute.toString().padStart(2, '0')) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(280.dp),
            shape = RoundedCornerShape(RadiusDialog.dp),
            color = Color.White,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("⏰ 选择提醒时间", fontSize = 16.sp, fontWeight = FontWeight.W700, color = TextDark)

                // Time input
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardYellow)
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicTextField(
                        value = hour,
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(2)
                            if (n.isEmpty() || n.toIntOrNull()?.let { it in 0..23 } == true) hour = n
                        },
                        textStyle = TextStyle(
                            fontSize = 40.sp,
                            fontWeight = FontWeight.W800,
                            color = TextDark,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.width(56.dp),
                        singleLine = true,
                    )
                    Text(":", fontSize = 40.sp, fontWeight = FontWeight.W800, color = Accent)
                    BasicTextField(
                        value = minute,
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(2)
                            if (n.isEmpty() || n.toIntOrNull()?.let { it in 0..59 } == true) minute = n
                        },
                        textStyle = TextStyle(
                            fontSize = 40.sp,
                            fontWeight = FontWeight.W800,
                            color = TextDark,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.width(56.dp),
                        singleLine = true,
                    )
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Divider),
                    ) {
                        Text("取消", color = TextSoft, fontWeight = FontWeight.W600)
                    }
                    TextButton(
                        onClick = {
                            val h = hour.padStart(2, '0').toIntOrNull() ?: 8
                            val m = minute.padStart(2, '0').toIntOrNull() ?: 0
                            onConfirm(h, m)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Accent),
                    ) {
                        Text("确定", color = Color.White, fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}
