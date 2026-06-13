package com.example.myplan.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 26.sp,
        color = TextDark
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 17.sp,
        color = TextDark
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 17.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 11.sp
    )
)
