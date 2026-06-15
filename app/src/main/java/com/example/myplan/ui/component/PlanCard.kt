package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.ui.theme.*

val CategoryIcons: Map<String, ImageVector> = mapOf(
    "日常" to Icons.Rounded.MenuBook,
    "紧急" to Icons.Rounded.NotificationsActive,
    "工作" to Icons.Rounded.BusinessCenter,
    "学习" to Icons.Rounded.School,
    "健康" to Icons.Rounded.FavoriteBorder,
    "其他" to Icons.Rounded.BookmarkBorder,
)

@Composable
fun PlanCard(plan: PlanWithReminders, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onComplete: (Boolean) -> Unit, onDelete: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val colors = CategoryColorMap[plan.plan.category] ?: CategoryColorMap["其他"]!!
    val isCompleted = plan.plan.isCompleted; val isEnabled = plan.plan.isEnabled
    val nextReminder = plan.reminders.filter { it.isEnabled }.minByOrNull { it.time }
    val catIcon = CategoryIcons[plan.plan.category] ?: Icons.Rounded.BookmarkBorder

    Card(modifier = modifier.fillMaxWidth().coloredShadow(cornerRadius = RadiusCard.dp), shape = RoundedCornerShape(RadiusCard.dp), colors = CardDefaults.cardColors(containerColor = colors.bg), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp, 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.clip(RoundedCornerShape(RadiusChip.dp)).background(colors.accent.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(catIcon, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(plan.plan.category, fontSize = 11.sp, fontWeight = FontWeight.W600, color = colors.accent)
                }
                IconButton(onClick = { onComplete(!isCompleted) }, modifier = Modifier.size(24.dp)) {
                    Icon(if (isCompleted) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (isCompleted) Color(0xFFE8786E) else TextSoft, modifier = Modifier.size(20.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(plan.plan.name, fontSize = 17.sp, fontWeight = FontWeight.W600, color = if (isCompleted) TextSoft else TextDark, textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None)
                    if (!plan.plan.description.isNullOrBlank()) Text(plan.plan.description, fontSize = 13.sp, fontWeight = FontWeight.W400, color = TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(8.dp)); PillToggle(checked = isEnabled, onCheckedChange = onToggle)
            }
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = when { isCompleted -> Complete; isEnabled -> colors.accent; else -> TextSoft }, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(when {
                    isCompleted -> "已完成"; !isEnabled -> "提醒已关闭"
                    nextReminder != null -> { val h = (nextReminder.time / 3600000).toInt() % 24; val m = ((nextReminder.time % 3600000) / 60000).toInt(); "下次提醒: %02d:%02d".format(h, m) }
                    else -> "未设置提醒"
                }, fontSize = 11.sp, fontWeight = FontWeight.W400, color = when { isCompleted -> Complete; else -> TextSoft })
                Spacer(Modifier.weight(1f))
                // Delete button (Pencil design: trash icon, 14sp, #D0A090)
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Delete, "删除", tint = Color(0xFFD0A090), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
