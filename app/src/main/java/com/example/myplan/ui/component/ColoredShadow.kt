package com.example.myplan.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.coloredShadow(
    color: Color = Color(0xFFE8D5C8),
    offsetY: Dp = 3.dp,
    blur: Dp = 12.dp,
    cornerRadius: Dp = 28.dp
): Modifier = this.drawBehind {
    val blurPx = blur.toPx()
    val cornerPx = cornerRadius.toPx()

    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            this.color = color
            asFrameworkPaint().apply {
                isAntiAlias = true
                maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawRoundRect(
            0f, offsetY.toPx(),
            size.width, size.height,
            cornerPx, cornerPx,
            paint
        )
    }
}
