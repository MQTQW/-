package com.example.myplan.ui.theme

import androidx.compose.ui.graphics.Color

/* ===== 页面背景 ===== */
val Bg = Color(0xFFFFFBF5)

/* ===== 卡片底色 ===== */
val CardYellow = Color(0xFFFFF9E6)
val CardCoral = Color(0xFFFFF0ED)
val CardMint = Color(0xFFEDF8F4)
val CardLavender = Color(0xFFF5F0FA)
val CardBlue = Color(0xFFEDF0F8)
val CardVanilla = Color(0xFFFFFBF6)

/* ===== 分类配色映射 ===== */
data class CategoryColors(val bg: Color, val accent: Color)
val CategoryColorMap = mapOf(
    "日常" to CategoryColors(CardYellow, Color(0xFFE08D5A)),
    "紧急" to CategoryColors(CardCoral, Color(0xFFE8786E)),
    "工作" to CategoryColors(CardBlue, Color(0xFF6B8AC4)),
    "学习" to CategoryColors(CardLavender, Color(0xFF8B7AB8)),
    "健康" to CategoryColors(CardMint, Color(0xFF8CA889)),
    "其他" to CategoryColors(CardVanilla, Color(0xFF9B8579)),
)

/* ===== 文字 ===== */
val TextDark = Color(0xFF4A3530)
val TextSoft = Color(0xFF9B8579)

/* ===== 强调 ===== */
val Accent = Color(0xFFFF7B5C)
val AccentLight = Color(0xFFFFE8E0)

/* ===== 功能色 ===== */
val Complete = Color(0xFF6BBF8A)
val Delete = Color(0xFFE8786E)
val Divider = Color(0xFFF0E8E0)

/* ===== Toggle ===== */
val ToggleOn = Accent
val ToggleOff = Color(0xFFD0C8C0)

/* ===== SnackBar ===== */
val SnackBarBg = Color(0xFF4A3530)

/* ===== 圆角常量 ===== */
const val RadiusCard = 28
const val RadiusDialog = 28
const val RadiusButton = 16
const val RadiusChip = 10
const val RadiusInput = 16
const val RadiusToggle = 13
const val RadiusSnackBar = 16
