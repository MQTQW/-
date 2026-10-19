package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class RepeatModeOption(val key: String, val emoji: String, val label: String)

val RepeatModes = listOf(
    RepeatModeOption("ONCE", "", "仅这一次"),
    RepeatModeOption("EVERY_DAY", "📆", "每天"),
    RepeatModeOption("WEEKDAYS", "💼", "周一至周五"),
    RepeatModeOption("CUSTOM", "⚙️", "自定义"),
)

@Composable
fun RepeatModeSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RepeatModes.chunked(2).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
           row.forEach { mode ->
            val isSelected = selected == mode.key
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(mode.key) })
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(mode.label, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
           }
          }
        }
    }
}
