package com.example.myplan.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.theme.Accent
import com.example.myplan.ui.theme.ToggleOff
import com.example.myplan.ui.theme.ToggleOn

/**
 * Pill 形 Toggle 开关 — Compose 原生 Switch 样式不匹配 Pencil 设计稿，
 * 此组件用 Box + animateFloatAsState 手写实现。
 *
 * Pencil source: pencil-jihua.pen → Toggle (inside PlanCard & ReminderTimeRow)
 */
@Composable
fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 26.dp,
    knobSize: Dp = 20.dp,
    padding: Dp = 3.dp
) {
    val bgColor by animateColorAsState(
        targetValue = if (checked) ToggleOn else ToggleOff,
        animationSpec = tween(250)
    )
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(250)
    )

    val maxOffset = width - knobSize - padding * 2
    val knobLeft = padding + maxOffset * knobOffset

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobLeft, y = padding)
                .size(knobSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
