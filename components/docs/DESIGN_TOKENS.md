# DESIGN_TOKENS — 设计 Token

> 来源：`pencil-jihua.pen` variables（活泼可爱风）

## 颜色

```kotlin
// DesignTokens.kt
object Colors {
    // 页面
    val Bg = Color(0xFFFFFBF5)
    
    // 卡片
    val CardYellow = Color(0xFFFFF9E6)
    val CardCoral = Color(0xFFFFF0ED)
    val CardMint = Color(0xFFEDF8F4)
    val CardLavender = Color(0xFFF5F0FA)
    val CardVanilla = Color(0xFFFFFBF6)
    val CardRed = Color(0xFFFFF0ED)      // 紧急-暂用珊瑚
    val CardBlue = Color(0xFFEDF0F8)     // 工作
    
    // 文字
    val TextDark = Color(0xFF4A3530)
    val TextSoft = Color(0xFF9B8579)
    
    // 强调
    val Accent = Color(0xFFFF7B5C)       // 珊瑚橙
    val AccentLight = Color(0xFFFFE8E0)
    
    // 功能色
    val Complete = Color(0xFF6BBF8A)     // 完成绿
    val Delete = Color(0xFFE8786E)       // 删除红
    
    // 线条
    val Divider = Color(0xFFF0E8E0)
    
    // 投影
    val Shadow = Color(0xFFE8D5C8)
    val ShadowDark = Color(0x00000000)   // 暗色模式透明
    
    // SnackBar
    val SnackBarBg = Color(0xFF4A3530)
}

// 分类配色映射
val CategoryColors = mapOf(
    "日常" to CardYellow to Color(0xFFE08D5A),
    "紧急" to CardRed to Color(0xFFE8786E),
    "工作" to CardBlue to Color(0xFF6B8AC4),
    "学习" to CardLavender to Color(0xFF8B7AB8),
    "健康" to CardMint to Color(0xFF8CA889),
    "其他" to CardVanilla to Color(0xFF9B8579),
)
```

## 圆角

```kotlin
object Corners {
    val Card = 28.dp
    val Dialog = 28.dp
    val Button = 16.dp
    val Chip = 10.dp
    val Input = 16.dp
    val Toggle = 13.dp
    val SnackBar = 16.dp
}
```

## 间距

```kotlin
object Spacing {
    val PageHorizontal = 24.dp
    val CardPadding = 16.dp
    val CardGap = 14.dp
    val FormGap = 16.dp
    val ReminderGap = 8.dp
}
```

## 阴影

```kotlin
object Shadows {
    val Card = ShadowSpec(offsetY = 3f, blur = 12f, color = Colors.Shadow)
    val FAB = ShadowSpec(offsetY = 4f, blur = 16f, color = Color(0x40FF7B5C))
    val Dialog = ShadowSpec(offsetY = 4f, blur = 24f, color = Color(0x18000000))
    val SnackBar = ShadowSpec(offsetY = 2f, blur = 12f, color = Color(0x20000000))
}
```
