# COMPONENT_MAP — Pencil ↔ Compose 对照表

> 最后更新：2026-07-15 | ✅ 全部组件已生成代码

| # | 组件名 | Pencil ID | Pencil 名称 | Compose 文件 | Compose 函数 | 状态 |
|---|--------|-----------|------------|-------------|-------------|------|
| 1 | DesignTokens | - | variables | DesignTokens.kt | 色值/间距/圆角常量 | ✅ |
| 2 | ColoredShadow | - | shadow effect | ColoredShadow.kt | `Modifier.coloredShadow()` | ✅ |
| 3 | PillToggle | - | Toggle 组件 | PillToggle.kt | `PillToggle()` | ✅ |
| 4 | PlanCard | Psm0x | PlanCard (reusable) | PlanCard.kt | `PlanCard()` | ✅ |
| 5 | GreetingBar | iqBXC | Greeting Area | GreetingBar.kt | `GreetingBar()` | ✅ |
| 6 | EmptyState | yveR2 | Plan List - Empty | EmptyState.kt | `EmptyState()` | ✅ |
| 7 | ReminderTimeRow | - | Rem 行 (inside Edit) | ReminderTimeRow.kt | `ReminderTimeRow()` | ✅ |
| 8 | CategorySelector | n0rDSh | Category Dropdown | CategoryChip.kt | `CategorySelector()`, `CategoryPill()` | ✅ |
| 9 | FilterDropdown | Q8DK6 | Filter Dropdown | FilterDropdown.kt | `FilterDropdown()` | ✅ |
| 10 | TimePicker | B7UuHn | TimePicker Dialog | TimePickerSheet.kt | `TimePickerDialog()` | ✅ |
| 11 | RepeatModeSelector | T4RCW | Repeat Mode Dropdown | RepeatModeSelector.kt | `RepeatModeDropdown()` | ✅ |
| 12 | DeleteDialog | TuQ34 | Delete Confirmation | DeleteDialog.kt | `DeleteConfirmationDialog()` | ✅ |
| 13 | UndoSnackBar | hM5Uy | SnackBar Undo | UndoSnackBar.kt | `UndoSnackBar()` | ✅ |

## 文件结构

```
e:\计划软件\components\compose\
├── DesignTokens.kt         ← 颜色/间距/圆角/阴影 常量
├── ColoredShadow.kt        ← Modifier.coloredShadow() 扩展
├── PillToggle.kt           ← 自定义 Pill Toggle 开关
├── VoiceIndicator.kt       ← 语音悬浮胶囊（休眠/激活/成功三态）
├── VoiceConversationManager.kt ← 对话状态机（sealed class 7状态）
├── SlotFiller.kt           ← NLU规则引擎（时间/重复/分类提取）
├── SpeechRecognizerWrapper.kt ← Android STT封装（休眠+激活双模式）
├── TextToSpeechWrapper.kt  ← Android TTS封装 + UtteranceProgressListener
├── VoiceApiService.kt      ← DeepSeek Function Calling（V1.5）
├── VoiceAssistantService.kt ← 前台服务，管理完整语音对话生命周期
├── PlanCard.kt             ← 计划卡片（3 状态 × 6 分类）
├── GreetingBar.kt          ← 问候栏（3 时段自适应）
├── EmptyState.kt           ← 空状态（🌱 种子插图）
├── ReminderTimeRow.kt      ← 提醒时间行（2 开关 × 3 重复）
├── CategoryChip.kt         ← 分类下拉 + 小标签
├── FilterDropdown.kt       ← 筛选下拉（全部/进行中/已完成）
├── TimePickerSheet.kt      ← 时间选择器弹窗
├── RepeatModeSelector.kt   ← 重复模式下拉
├── DeleteDialog.kt         ← 删除确认弹窗
└── UndoSnackBar.kt         ← 撤销 SnackBar
```
