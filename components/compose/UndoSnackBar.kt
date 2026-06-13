package com.example.myplan.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.Accent
import com.example.myplan.ui.theme.RadiusSnackBar
import com.example.myplan.ui.theme.SnackBarBg
import kotlinx.coroutines.delay

/**
 * 撤销 SnackBar — 删除计划后弹出，5 秒自动消失。
 *
 * Pencil source: pencil-jihua.pen → SnackBar Undo (hM5Uy)
 */
@Composable
fun UndoSnackBar(
    planName: String,
    onUndo: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        delay(5000)
        onTimeout()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(RadiusSnackBar.dp))
            .background(SnackBarBg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "已删除「$planName」",
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = Color.White
        )
        TextButton(onClick = onUndo) {
            Text(
                text = "撤销",
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = Accent
            )
        }
    }
}
