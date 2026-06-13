package com.example.myplan.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design Tokens — 来源: pencil-jihua.pen variables
 * 活泼可爱风 / Cute Playful Style
 * 所有 UI 组件必须引用此文件，禁止硬编码色值
 */

/* ===== 页面背景 ===== */
val Bg = Color(0xFFFFFBF5)

/* ===== 卡片底色（按分类） ===== */
val CardYellow = Color(0xFFFFF9E6)
val CardCoral = Color(0xFFFFF0ED)
val CardMint = Color(0xFFEDF8F4)
val CardLavender = Color(0xFFF5F0FA)
val CardBlue = Color(0xFFEDF0F8)
val CardVanilla = Color(0xFFFFFBF6)

/* ===== 分类配色映射 (底色, 强调色) ===== */
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

/* ===== 分割线 ===== */
val Divider = Color(0xFFF0E8E0)

/* ===== 阴影 ===== */
val ShadowLight = Color(0xFFE8D5C8)
val ShadowDark = Color(0x00000000)
val ShadowDialog = Color(0x18000000)
val ShadowFab = Color(0x40FF7B5C)

/* ===== SnackBar ===== */
val SnackBarBg = Color(0xFF4A3530)

/* ===== Toggle ===== */
val ToggleOn = Accent
val ToggleOff = Color(0xFFD0C8C0)

/* ===== 圆角 (dp) ===== */
val RadiusCard = 28
val RadiusDialog = 28
val RadiusButton = 16
val RadiusChip = 10
val RadiusInput = 16
val RadiusToggle = 13
val RadiusSnackBar = 16
val RadiusFAB = 30

/* ===== 间距 (dp) ===== */
val SpacingPageH = 24
val SpacingCardPad = 16
val SpacingCardGap = 14
val SpacingFormGap = 16
val SpacingReminderGap = 8

/* ===== FAB ===== */
val FabSize = 60
val FabIconSize = 28
