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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

/**
 * 提醒时间行 — 时钟图标 + 时间 + 重复模式 + Toggle + 删除。
 * 2 开关态 × 3 重复模式变体
 *
 * Pencil source: pencil-jihua.pen → ReminderTimeRow 实例 (inside Plan Edit Screen)
 */
@Composable
fun ReminderTimeRow(
    time: String,              // "08:30"
    repeatMode: String,        // "每天" / "仅一次" / "每周"
    enabled: Boolean,
    onTimeClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusButton.dp))
            .background(CardYellow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 左侧：时钟 + 时间 + 重复标签 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = TextDark,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = time,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                color = TextDark,
                modifier = Modifier.clickable { onTimeClick() }
            )
            RepeatBadge(mode = repeatMode, enabled = enabled) {
                onRepeatClick()
            }
        }

        // ── 右侧：Toggle + 删除 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PillToggle(checked = enabled, onCheckedChange = onToggle)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "删除提醒",
                    tint = Color(0xFFD0A090),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RepeatBadge(mode: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = mode,
        fontSize = 11.sp,
        fontWeight = FontWeight.W500,
        color = if (enabled) Accent else TextSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) AccentLight else Divider)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
