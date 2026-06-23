package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

data class RepeatModeOption(
    val key: String,
    val emoji: String,
    val label: String,
)

val RepeatModes = listOf(
    RepeatModeOption("EVERY_DAY", "📆", "每天"),
    RepeatModeOption("WEEKDAYS", "💼", "工作日"),
    RepeatModeOption("CUSTOM", "⚙️", "自定义"),
)

@Composable
fun RepeatModeSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RepeatModes.forEach { mode ->
            val isSelected = selected == mode.key
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Accent else Divider)
                    .clickable { onSelect(mode.key) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mode.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    mode.label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W400,
                    color = if (isSelected) Color.White else TextSoft,
                )
            }
        }
    }
}
