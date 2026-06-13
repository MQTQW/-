package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

@Composable
fun FilterDropdown(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(RadiusButton.dp)).background(AccentLight).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(selected, fontSize = 12.sp, fontWeight = FontWeight.W700, color = Accent)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = Accent, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("📋 全部", "🔄 进行中", "✅ 已完成").forEach { label ->
                DropdownMenuItem(text = { Text(label, fontSize = 15.sp) }, onClick = { onSelect(label.substringAfter(" ")); expanded = false })
            }
        }
    }
}
