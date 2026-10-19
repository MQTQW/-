package com.example.myplan.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myplan.data.local.relation.PlanWithReminders

val CategoryIcons: Map<String, ImageVector> = mapOf(
    "日常" to Icons.Rounded.MenuBook, "紧急" to Icons.Rounded.NotificationsActive,
    "工作" to Icons.Rounded.BusinessCenter, "学习" to Icons.Rounded.School,
    "健康" to Icons.Rounded.FavoriteBorder, "其他" to Icons.Rounded.BookmarkBorder
)

fun planRepeatLabel(plan: PlanWithReminders): String = when (plan.plan.repeatMode) {
    "ONCE" -> "仅这一次"
    "WEEKDAYS" -> "周一至周五"
    "CUSTOM" -> {
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        "每周" + plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }.distinct().sorted().joinToString("、") { labels[it - 1] }
    }
    else -> "每天"
}

fun planTimeLabel(plan: PlanWithReminders): String = if (plan.plan.repeatMode == "ONCE") {
    plan.plan.oneTimeAt?.let { com.example.myplan.quickadd.QuickCaptureNotifications.formatTime(it) } ?: "未设置日期"
} else plan.reminders.filter { it.isEnabled }
    .sortedWith(compareBy({ it.hour }, { it.minute })).joinToString("、") { "%02d:%02d".format(it.hour, it.minute) }
    .ifBlank { "未开启提醒时间" }

@Composable
fun PlanCard(
    plan: PlanWithReminders,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onComplete: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CompanionCard(modifier = modifier.clickable(onClick = onClick), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(plan.plan.name, style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (plan.plan.isCompleted) TextDecoration.LineThrough else null,
                    color = if (plan.plan.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (!plan.plan.description.isNullOrBlank()) Text(plan.plan.description,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = plan.plan.isEnabled, onCheckedChange = onToggle,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "${plan.plan.name}：${if (plan.plan.isEnabled) "暂停提醒" else "开启提醒"}" })
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompanionTag(plan.plan.category)
            Text(if (plan.plan.isEnabled) planRepeatLabel(plan) else "提醒已暂停",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Checkbox(checked = plan.plan.isCompleted, onCheckedChange = onComplete,
                modifier = Modifier.semantics { contentDescription = "${plan.plan.name}：${if (plan.plan.isCompleted) "取消今天完成" else "完成今天"}" })
        }
        Text(planTimeLabel(plan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (plan.plan.repeatMode == "ONCE") {
                if (plan.plan.isCompleted) "已完成" else "仅在指定日期提醒一次"
            } else if (plan.plan.isCompleted) "今天已完成 · 重复提醒继续保留" else "完成今天与暂停提醒可分别设置",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}
