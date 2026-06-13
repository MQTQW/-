package com.example.myplan.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplan.ui.theme.Accent
import com.example.myplan.ui.theme.Complete
import com.example.myplan.ui.theme.Delete

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionCard(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onSwipeLeft(); false }
                SwipeToDismissBoxValue.StartToEnd -> { onSwipeRight(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> Delete
                    SwipeToDismissBoxValue.StartToEnd -> Complete
                    else -> Color.Transparent
                }
            )) {
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Text("🗑", fontSize = 24.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
                } else if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Text("✅", fontSize = 24.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
                }
            }
        },
        modifier = modifier
    ) {
        content()
    }
}
