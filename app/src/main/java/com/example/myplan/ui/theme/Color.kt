package com.example.myplan.ui.theme

import androidx.compose.ui.graphics.Color

/* ===== 页面背景 ===== */
val Bg = Color(0xFFF7F8F2)

/* ===== 卡片底色 ===== */
val CardYellow = Color(0xFFF0F3E8)
val CardCoral = Color(0xFFF9EBE3)
val CardMint = Color(0xFFE7EDDC)
val CardLavender = Color(0xFFF0F3E8)
val CardBlue = Color(0xFFE3EDF0)
val CardVanilla = Color(0xFFFFFFFF)

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
val TextDark = Color(0xFF344331)
val TextSoft = Color(0xFF64735E)

/* ===== 强调 ===== */
val Accent = Color(0xFF65794F)
val AccentLight = Color(0xFFE7EDDC)

/* ===== 功能色 ===== */
val Complete = Accent
val Delete = Color(0xFFA95038)
val Divider = Color(0xFFE0E6D9)

/* ===== Toggle ===== */
val ToggleOn = Accent
val ToggleOff = Color(0xFFE0E6D9)

/* ===== SnackBar ===== */
val SnackBarBg = TextDark

/* ===== 圆角常量 ===== */
const val RadiusCard = 22
const val RadiusDialog = 28
const val RadiusButton = 14
const val RadiusChip = 10
const val RadiusInput = 14
const val RadiusToggle = 13
const val RadiusSnackBar = 16
