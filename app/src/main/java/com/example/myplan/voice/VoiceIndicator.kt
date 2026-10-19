package com.example.myplan.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VoiceIndicator(phaseFlow: StateFlow<VoicePhase>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val phase by phaseFlow.collectAsStateWithLifecycle()
    OutlinedButton(onClick = { onClick?.invoke() }, modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(Icons.Outlined.MicNone, null, Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(phase.label, style = MaterialTheme.typography.bodySmall)
    }
}
