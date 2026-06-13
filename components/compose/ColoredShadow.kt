package com.example.myplan.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 彩色阴影扩展 — Compose 原生 shadow() 只支持黑色，
 * 此函数用 drawBehind 实现设计稿要求的暖色投影。
 *
 * Pencil source: pencil-jihua.pen → PlanCard effect.shadow
 */
fun Modifier.coloredShadow(
    color: Color = Color(0xFFE8D5C8),
    offsetY: Dp = 3.dp,
    blur: Dp = 12.dp,
    cornerRadius: Dp = 28.dp,
    spread: Dp = 0.dp
): Modifier = this.drawBehind {
    val blurPx = blur.toPx()
    val offsetYPx = offsetY.toPx()
    val spreadPx = spread.toPx()
    val cornerPx = cornerRadius.toPx()

    drawRoundRect(
        color = color,
        topLeft = Offset(0f, offsetYPx),
        size = Size(
            size.width + spreadPx * 2,
            size.height + spreadPx * 2
        ),
        cornerRadius = CornerRadius(cornerPx, cornerPx),
        blurRadius = blurPx
    )
}
