---
name: MY PLAN 行程 AI 原型
description: 沿用既有暖白 sage/cream Android 视觉的隔离交互原型
colors:
  bg: "#F7F8F2"
  ink: "#344331"
  muted: "#64735E"
  primary: "#65794F"
  primary-hover: "#53663E"
  light: "#E7EDDC"
  surface: "#FFFFFF"
  warm: "#F9EBE3"
  line: "#E0E6D9"
  error: "#A04430"
  pending: "#785632"
  canvas: "#EAECE4"
typography:
  headline:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "25px"
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: "-0.025em"
  title:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.45
  body:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.65
  label:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "13px"
    fontWeight: 600
  helper:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "12px"
    fontWeight: 400
rounded:
  card: "22px"
  button: "14px"
  input: "14px"
  chip: "10px"
  day-tab: "12px"
spacing:
  compact: "8px"
  small: "12px"
  medium: "14px"
  content: "18px"
  inset: "20px"
  section: "24px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.button}"
    padding: "11px 15px"
    width: "100%"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.surface}"
  button-secondary:
    backgroundColor: "{colors.light}"
    textColor: "{colors.ink}"
    rounded: "{rounded.button}"
    padding: "11px 15px"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.input}"
    padding: "10px 12px"
    width: "100%"
  paper:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.card}"
    padding: "17px"
  choice-selected:
    backgroundColor: "{colors.light}"
    textColor: "{colors.ink}"
    rounded: "{rounded.chip}"
    padding: "7px 10px"
  day-tab-selected:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.day-tab}"
    padding: "8px 12px"
---

# Design System: MY PLAN 行程 AI 原型

## Overview

**Creative North Star: "MY PLAN 既有温暖轻巧 Android 视觉"**

本轮沿用已确认的暖白 sage/cream 系统，在既有视觉中比较交互模型。模式为 Operate；原型不是新品牌，也不定义生产 App 的新主题。视觉基准来自 `app/src/main/java/com/example/myplan/ui/theme/Color.kt`，具体浏览器实现以本目录 `base.css` 和 `variants/*.js` 为准。

暖白背景承载内容，低饱和绿色指示主操作与选中状态，白色卡片承载输入与依据。信息明确、中文可读，先让用户判断地点、时间与来源。SVG 仅用于一致的线性图标和有明确说明的几何地点分布；没有新图片资产或外部字体依赖。

**Key Characteristics:**

- 既有暖白背景、鼠尾草绿强调与圆角表面。
- 手机内容内部滚动，底部主操作持续可达。
- 三方向共享颜色与基础组件，信息顺序随交互模型变化。
- 待确认、估计和演示状态均有文字说明。

前置 YAML 记录当前复用值，不是对 Android 主题的回写。原先约定的对话框圆角来自 App 的 `RadiusDialog`（28px），当前编辑状态使用完整手机内容页，没有实现底部编辑面板。

## Colors

### Primary

- **鼠尾草绿（primary）**：主按钮、当前日期和已选来源；hover 使用 primary-hover。
- **浅鼠尾草绿（light）**：次按钮、已选偏好、进度完成提示与温和强调。

### Neutral

- **暖白（bg）**：手机页面与常驻底部操作。
- **白色（surface）**：输入区域、来源卡和内容卡。
- **深绿墨色（ink）**：标题与主要文字；muted 用于说明、时间与出处。
- **浅灰绿（line）**：分隔线与基础卡片边界。
- **外壳灰绿（canvas）**：浏览器工作台背景，属于原型外壳。

### Status

- **浅暖杏色（warm）**：不确定信息与恢复提示背景。
- **错误色（error）**：输入与冲突反馈；pending 用于待确认状态。状态同时使用文字与结构表达。

bg、ink、muted、primary、light、surface、warm、line 对应 App 的现有颜色。primary-hover、error、pending、canvas 是本原型实际使用的补充值；其中 error 不等于 App 的 Delete 色，不将这些值升级为全局 Android 主题。

**The Existing Palette Rule.** 三种方向复用既有配色，通过操作入口和信息顺序区分。

## Typography

系统中文 sans 优先使用 Microsoft YaHei、PingFang SC，再回退 system-ui。没有显示字体或装饰字体。前置字体层级记录共享手机页面；三方向的主要标题使用（24px），分步为（650 / 1.35），攻略为（700 / 1.38），对话欢迎标题为（650 / 1.4），对话窄屏（不超过 360px）降至（23px）。不将方向内的局部字号差异变成新字体系统。

标题建立页面层级，正文解释下一步，较小的辅助文字承载来源和演示说明。节点标题为（15px），时间与出处常用（11–12px）；时间使用等宽数字。主按钮为（14px / 600）。内容不依靠大字号口号建立情绪。

**The Clear Chinese Rule.** 文案直接说明动作、结果和不确定性，沿用已确认措辞。

## Layout

手机容器按可用高度适配，标题与底部操作不随正文滚走；正文是独立滚动区。共享正文左右内边距为（20px），窄屏为（18px）；主按钮最小高度（46px），图标按钮与偏好选项的最终触控尺寸至少（44px）。静态标记和辅助出处不统一扩成大按钮。

桌面工作台最大宽度（1250px），三列依次为页面目录、手机、方向与异常说明，列间距（40px），顶部留出（88px）。中间手机列范围（340–390px），容器圆角（30px），高度为 `clamp(560px, calc(100svh - 115px), 844px)`。

视口不超过（1050px）时说明面板移至下方；不超过（680px）时手机排首位，目录与场景控制放在下方，均可通过外层滚动到达。窄屏手机宽度为 `min(390px, 100%)`，高度 `calc(100svh - 110px)`、最小高度（540px）、圆角（24px）。通过目录跳页或切换方向时将外层滚动归零；浏览器滚动恢复设为 manual。局部窄屏调整还使用（360px）断点。

Prototype Picker 固定在顶部（24px）；下方常显演示说明。Picker 使用 prototype skill 原样 CSS/JS，与手机 App 视觉分开记录。双字标签「分步 / 对话 / 攻略」对应全名；全名、取舍和异常控制放在界面外，避免把评审工具带入 App 用户流程。

## Elevation & Depth

手机内部以白色、浅绿表面和细边界分层，不给每个卡片增加阴影。手机外壳使用轻阴影 `0 12px 34px rgba(45,60,36,.09)`，仅帮助在工作台中识别设备边界。Picker 自带深色半透明、模糊与阴影，按原规范保留，不推广到 App 表面。

共享页面切换使用（180ms ease-out）的淡入和（4px）向上归位；进度条使用（220ms ease-out）的 transform 过渡。启用 `prefers-reduced-motion: reduce` 时移除动画与过渡。截图需等待入场动画结束、`.flow-enter` 清理且正文 opacity 为 1。

## Shapes

内容卡使用既有卡片圆角，主按钮和字段使用既有中等圆角，偏好选项与日期标签用更小圆角。边框通常为（1px），用于划分可交互区域和内容层级；选择后的填色、边框与文字同时变化。线性图标的共享笔画为（1.8px），端点和连接均为圆角。

## Components

### Buttons

主按钮承载当前一步的主要动作，次按钮承载调整、返回或备用路径。共享样式为全宽、最小高度（46px），按下轻缩放至（0.985），禁用态 opacity（0.45）。一般焦点环为（3px，#879B72，offset 3px）；方向内字段容器使用现有（2px）绿色焦点环。无有效来源、需求或可保存节点时，动作禁用或给出具体错误。

### Inputs / Fields

共享白色字段有（1px，#C9D2BE）边框、最小高度（46px），正文（15px）。错误字段与内联反馈关联，不能用颜色替代原因。目的地、对话输入、日期、节点名称和时间使用真实表单控件；在输入框、文本域或选择器内输入时，不触发原型切换快捷键。

### Chips / Navigation

偏好使用可重复切换的选项，选中态以 `aria-pressed` 表达。日期导航使用 tablist 与 `aria-selected`，选中日期填充主色。原型目录使用 `aria-current="page"`。来源链接保留 S1/S2 与具体出处关系，不只显示一个泛化的「AI 推荐」。

### Cards / Containers

共享内容卡的内边距为（17px），来源卡为（15px），方向内关键输入卡多使用（20px）。来源卡保留标题、作者、时间、摘要与查看入口；可选模式增加选择按钮，不把资料阅读与开始规划合并成一个无反馈动作。

### Timeline / Confirmation / Distribution

时间线按「时间 / 节点」双列组织，节点保留停留时长、备注与来源。编辑保留出处，将受影响交通标为待更新。地点确认提供具体候选、暂不安排与暂跳路径；处理结果会改变草稿。地点分布使用序号与图例对照，并始终标注「示意，不代表实际道路」。

三方向的具体顺序与共同状态见 [PRODUCT.md](PRODUCT.md)。所有保存与恢复反馈仅表达原型内存状态。

## Do's and Don'ts

### Do:

- **Do** 沿用 App 的暖白、绿色与既有圆角，扩展前核对主题源与当前原型。
- **Do** 保持底部主操作可达，并允许正文和工作台说明各自滚动。
- **Do** 同时展示来源、不确定性和恢复路径，使用可读中文说明。
- **Do** 保留三种完整可点击交互，展示后等待用户选择。

### Don't:

- **Don't** 为本原型另立品牌或以三套配色代替交互比较。
- **Don't** 使用此前被否定的「今天轻轻开始」文案。
- **Don't** 把几何分布画成可用于导航的真实地图。
- **Don't** 将评审 Picker、演示外壳或内存保存误写为生产 App 能力。
- **Don't** 在用户选择前把原型方向整合进生产界面。
