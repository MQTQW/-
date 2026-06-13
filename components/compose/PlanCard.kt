package com.example.myplan.ui.component

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.ui.theme.*

/**
 * 计划卡片 — 可复用。含分类标签、完成状态、toggle、提醒信息。
 * 3 状态变体：进行中 / 已完成 / 已禁用
 * 6 分类配色变体：日常 / 紧急 / 工作 / 学习 / 健康 / 其他
 *
 * Pencil source: pencil-jihua.pen → PlanCard (reusable, Psm0x)
 */
@Composable
fun PlanCard(
    plan: PlanWithReminders,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onComplete: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = CategoryColorMap[plan.plan.category] ?: CategoryColorMap["其他"]!!
    val isCompleted = plan.plan.isCompleted
    val isEnabled = plan.plan.isEnabled
    val nextReminder = plan.reminders
        .filter { it.isEnabled }
        .minByOrNull { it.time }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .coloredShadow(cornerRadius = RadiusCard.dp),
        shape = RoundedCornerShape(RadiusCard.dp),
        colors = CardDefaults.cardColors(containerColor = colors.bg),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp, 18.dp)) {
            // ── 顶部：分类标签 + 爱心 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryPillInternal(category = plan.plan.category, colors = colors)
                IconButton(
                    onClick = { onComplete(!isCompleted) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Rounded.Favorite
                                      else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isCompleted) "已完成" else "标记完成",
                        tint = if (isCompleted) Color(0xFFE8786E) else TextSoft,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 中部：标题 + 描述 + Toggle ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.plan.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                        color = if (isCompleted) TextSoft else TextDark,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough
                                       else TextDecoration.None
                    )
                    if (!plan.plan.description.isNullOrBlank()) {
                        Text(
                            text = plan.plan.description,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W400,
                            color = TextSoft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillToggle(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }

            // ── 底部：提醒行 ──
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = when {
                        isCompleted -> Complete
                        isEnabled -> colors.accent
                        else -> TextSoft
                    },
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        isCompleted -> "已完成"
                        !isEnabled -> "提醒已关闭"
                        nextReminder != null -> {
                            val h = nextReminder.time / 3600
                            val m = (nextReminder.time % 3600) / 60
                            "下次提醒: %02d:%02d".format(h, m)
                        }
                        else -> "未设置提醒"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W400,
                    color = when {
                        isCompleted -> Complete
                        else -> TextSoft
                    }
                )
            }
        }
    }
}

/** 内部分类标签，不对外暴露 */
@Composable
private fun CategoryPillInternal(category: String, colors: CategoryColors) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(RadiusChip.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = when (category) {
                "日常" -> Icons.Rounded.MenuBook
                "紧急" -> Icons.Rounded.PriorityHigh
                "工作" -> Icons.Rounded.Work
                "学习" -> Icons.Rounded.School
                "健康" -> Icons.Rounded.Favorite
                else -> Icons.Rounded.PushPin
            },
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = category,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            color = colors.accent
        )
    }
}
