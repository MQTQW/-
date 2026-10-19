package com.example.myplan.focus.ui.focusnav

import androidx.compose.runtime.Composable
import com.example.myplan.focus.ui.home.HomeScreen

/** 专注 Tab — 单页，无底部导航 */
@Composable
fun FocusNavGraph(onDetailScreenChanged: (Boolean) -> Unit = {}) {
    HomeScreen(onDetailScreenChanged = onDetailScreenChanged)
}
