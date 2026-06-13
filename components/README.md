# MY PLAN 组件库

> Pencil 设计 ↔ Compose 代码的桥梁

## 目录

| 目录 | 内容 |
|------|------|
| `pencil/` | Pencil 导出的组件截图 |
| `compose/` | Kotlin Compose 代码 |
| `docs/` | 组件文档 |

## 使用方式

对 Claude 说：
- `/sync-components` — 全量同步 Pencil → 组件库
- "更新 PlanCard 组件" — 单个组件同步
- "画 GreetingBar 的晚上变体" — 指定变体设计

## 组件清单

| 组件 | Pencil 节点 | Compose 文件 | 变体数 |
|------|------------|-------------|--------|
| PlanCard | Psm0x | PlanCard.kt | 3 状态 × 6 分类 = 18 |
| ReminderTimeRow | - | ReminderTimeRow.kt | 2 开关 × 3 重复 = 6 |
| PillToggle | - | PillToggle.kt | 2 (on/off) |
| CategoryChip | - | CategoryChip.kt | 6 分类 |
| GreetingBar | iqBXC | GreetingBar.kt | 3 时段 |
| EmptyState | yveR2 | EmptyState.kt | 1 |
| FilterDropdown | Q8DK6 | FilterDropdown.kt | 3 选项 |
| TimePickerSheet | B7UuHn | TimePickerSheet.kt | 1 |
| RepeatModeSelector | T4RCW | RepeatModeSelector.kt | 3 选项 |
| DeleteDialog | TuQ34 | DeleteDialog.kt | 1 |
| UndoSnackBar | hM5Uy | UndoSnackBar.kt | 1 |
| PlanNotification | m3F9t | PlanNotification.kt | 1 |

## 设计 Token 来源

所有颜色、间距、圆角从 Pencil variables 导出，不硬编码。
