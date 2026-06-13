package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

data class CategoryItem(val name: String, val emoji: String, val icon: ImageVector)

val Categories = listOf(
    CategoryItem("日常", "📋", Icons.Rounded.MenuBook),
    CategoryItem("紧急", "🔥", Icons.Rounded.NotificationsActive),
    CategoryItem("工作", "💼", Icons.Rounded.BusinessCenter),
    CategoryItem("学习", "📖", Icons.Rounded.School),
    CategoryItem("健康", "💪", Icons.Rounded.FavoriteBorder),
    CategoryItem("其他", "📌", Icons.Rounded.BookmarkBorder),
)

@Composable
fun CategorySelector(selected: CategoryItem, onSelect: (CategoryItem) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类", fontSize = 14.sp, fontWeight = FontWeight.W600, color = TextSoft)
        Box {
            Row(modifier = Modifier.clickable { expanded = true }.clip(RoundedCornerShape(RadiusButton.dp)).background(AccentLight).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(selected.icon, null, tint = Accent, modifier = Modifier.size(16.dp))
                Text(selected.name, fontSize = 14.sp, fontWeight = FontWeight.W600, color = Accent)
                Icon(Icons.Rounded.ArrowDropDown, null, tint = Accent, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Categories.forEach { cat ->
                    DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text(cat.emoji, fontSize = 16.sp); Text(cat.name, fontSize = 15.sp, fontWeight = FontWeight.W500) } }, onClick = { onSelect(cat); expanded = false })
                }
            }
        }
    }
}
