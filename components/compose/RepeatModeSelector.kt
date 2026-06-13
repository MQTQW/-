package com.example.myplan.ui.component

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 重复模式选择器 — 编辑页提醒行用。三个选项：每天/仅一次/每周
 *
 * Pencil source: pencil-jihua.pen → Repeat Mode Dropdown (T4RCW)
 */
data class RepeatMode(val mode: String, val label: String, val emoji: String)

val RepeatModes = listOf(
    RepeatMode("DAILY", "每天", "🔄"),
    RepeatMode("NONE", "仅一次", "▶️"),
    RepeatMode("WEEKLY", "每周", "📅"),
)

@Composable
fun RepeatModeDropdown(
    selected: RepeatMode,
    onSelect: (RepeatMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(selected.label, fontSize = 13.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            RepeatModes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${mode.emoji} ${mode.label}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.W500
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}
