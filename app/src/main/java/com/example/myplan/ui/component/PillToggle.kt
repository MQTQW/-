package com.example.myplan.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact visual switch with a full touch target and native switch semantics. */
@Composable
fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 26.dp,
    knobSize: Dp = 20.dp,
    padding: Dp = 3.dp,
    enabled: Boolean = true,
) {
    val background by animateColorAsState(targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, animationSpec = tween(180), label = "switch background")
    val progress by animateFloatAsState(targetValue = if (checked) 1f else 0f, animationSpec = tween(180), label = "switch position")
    val knobLeft = padding + (width - knobSize - padding * 2) * progress

    Box(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(width).height(height).clip(RoundedCornerShape(50)).background(background)) {
            Box(Modifier.offset(x = knobLeft, y = padding).size(knobSize).clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface))
        }
    }
}
