package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.*

@Composable
fun EmptyState(onCreateClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(AccentLight), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Spa, "种子", tint = Accent, modifier = Modifier.size(52.dp))
            }
            Text("还没有计划哦", fontSize = 18.sp, fontWeight = FontWeight.W700, color = TextDark)
            Text("点击 ＋ 创建你的第一个计划\n种下一颗种子吧", fontSize = 14.sp, fontWeight = FontWeight.W400, color = TextSoft, textAlign = TextAlign.Center, lineHeight = 22.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DecorationDot(); DecorationDot(); DecorationDot()
                Text("今天也要加油哦", fontSize = 12.sp, fontWeight = FontWeight.W500, color = TextSoft)
                DecorationDot(); DecorationDot(); DecorationDot()
            }
        }
    }
}

@Composable
fun DecorationDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(4.dp).clip(CircleShape).background(Divider))
}
