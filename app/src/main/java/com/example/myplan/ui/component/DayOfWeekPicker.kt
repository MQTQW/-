package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun DayOfWeekPicker(
    selectedDays: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        DayLabels.forEachIndexed { index, label ->
            val day = index + 1
            val isSelected = day in selectedDays
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "星期$label" }
                    .toggleable(value = isSelected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(day) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
