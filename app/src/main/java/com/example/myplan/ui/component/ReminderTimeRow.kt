package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

@Composable
fun ReminderTimeRow(time: String, repeatMode: String, enabled: Boolean, onTimeClick: () -> Unit, onRepeatClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(RadiusButton.dp)).background(CardYellow).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Schedule, null, tint = TextDark, modifier = Modifier.size(20.dp))
            Text(time, fontSize = 16.sp, fontWeight = FontWeight.W600, color = TextDark, modifier = Modifier.clickable { onTimeClick() })
            Text(repeatMode, fontSize = 11.sp, fontWeight = FontWeight.W500, color = if (enabled) Accent else TextSoft, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (enabled) AccentLight else Divider).clickable { onRepeatClick() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillToggle(checked = enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) { Icon(Icons.Rounded.Close, "", tint = Color(0xFFD0A090), modifier = Modifier.size(18.dp)) }
        }
    }
}
