package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

/** 分类数据 */
data class Category(
    val name: String,
    val emoji: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

val Categories = listOf(
    Category("日常", "📋", Icons.Rounded.MenuBook),
    Category("紧急", "🔥", Icons.Rounded.PriorityHigh),
    Category("工作", "💼", Icons.Rounded.Work),
    Category("学习", "📖", Icons.Rounded.School),
    Category("健康", "💪", Icons.Rounded.Favorite),
    Category("其他", "📌", Icons.Rounded.PushPin),
)

/**
 * 分类下拉选择器 — 编辑页用。6 个分类选项。
 *
 * Pencil source: pencil-jihua.pen → Category Dropdown (n0rDSh)
 */
@Composable
fun CategorySelector(
    selected: Category,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "分类",
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            color = TextSoft
        )

        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(RadiusButton.dp))
                    .background(AccentLight)
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = selected.icon,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = selected.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                    color = Accent
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Categories.forEach { cat ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(cat.emoji, fontSize = 16.sp)
                                Text(cat.name, fontSize = 15.sp, fontWeight = FontWeight.W500)
                            }
                        },
                        onClick = {
                            onSelect(cat)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 分类小标签 — 用在 PlanCard 顶部，只读不可点击
 *
 * Pencil source: pencil-jihua.pen → Category Pill (RVHIb)
 */
@Composable
fun CategoryPill(category: Category, modifier: Modifier = Modifier) {
    val colors = CategoryColorMap[category.name] ?: CategoryColorMap["其他"]!!

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusChip.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = category.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            color = colors.accent
        )
    }
}
