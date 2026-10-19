package com.example.myplan.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CategoryItem(val name: String, val emoji: String, val icon: ImageVector)

val Categories = listOf(
    CategoryItem("日常", "📋", Icons.Rounded.MenuBook),
    CategoryItem("工作", "💼", Icons.Rounded.BusinessCenter),
    CategoryItem("学习", "📖", Icons.Rounded.School),
    CategoryItem("健康", "💪", Icons.Rounded.FavoriteBorder),
    CategoryItem("紧急", "🔥", Icons.Rounded.NotificationsActive),
    CategoryItem("其他", "📌", Icons.Rounded.BookmarkBorder),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    selected: CategoryItem,
    onSelect: (CategoryItem) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Categories.forEach { category ->
                val isSelected = category == selected
                Surface(
                    onClick = { onSelect(category) },
                    enabled = enabled,
                    modifier = Modifier.semantics { this.selected = isSelected; role = Role.RadioButton },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(category.name, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}
