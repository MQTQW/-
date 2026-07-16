package com.example.myplan.voice

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

private val ColorDormant = Color(0xFF9B8579)
private val ColorActive = Color(0xFFFF7B5C)
private val ColorSurface = Color(0xFFFFFFFF)
private val ColorTextDim = Color(0xFF9B8579)

@Composable
fun VoiceIndicator(
    phaseFlow: StateFlow<VoicePhase>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val phase by phaseFlow.collectAsState()

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        val isActive = phase != VoicePhase.IDLE

        Row(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isActive) Color(0xFFFFF0ED) else ColorSurface)
                .then(
                    if (!isActive) Modifier.border(1.dp, Color(0xFFF0E8E0), RoundedCornerShape(16.dp))
                    else Modifier
                )
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) ColorActive else ColorDormant)
            )

            // Wave bars (active states only)
            if (phase == VoicePhase.LISTENING || phase == VoicePhase.PROCESSING) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height((8 + i * 4).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(ColorActive)
                    )
                }
            }

            // Label
            Text(
                text = phase.label,
                fontSize = 11.sp,
                color = if (isActive) ColorActive else ColorTextDim,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
