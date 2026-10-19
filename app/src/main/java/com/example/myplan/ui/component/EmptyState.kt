package com.example.myplan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EmptyState(onCreateClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(42.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.Spa, null, Modifier.padding(28.dp).size(50.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("还没有计划", style = MaterialTheme.typography.titleLarge)
        Text("从一件想记住的小事开始。\n一杯温水，一页书，都很好。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        CompanionButton("创建第一个计划", onCreateClick)
    }
}

@Composable
fun DecorationDot(modifier: Modifier = Modifier) {
    Box(modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
}
