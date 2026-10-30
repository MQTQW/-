---
name: MY PLAN 行程交互原型 V2
description: 继承暖白与鼠尾草绿，以图文概览和可编辑行程组织旅行安排
colors:
  primary: "#65794f"
  on-primary: "#fff"
  bg: "#f7f8f2"
  ink: "#344331"
  muted: "#64735e"
  light: "#e7eddc"
  surface: "#fff"
  line: "#e0e6d9"
  warning: "#885b29"
  warning-bg: "#f4eee2"
  map-bg: "#ebefdf"
  map-marker: "#65794f"
  dark-primary: "#bed09f"
  dark-on-primary: "#20301b"
  dark-bg: "#20271f"
  dark-ink: "#edf1e5"
  dark-muted: "#b6c2a9"
  dark-light: "#37432d"
  dark-surface: "#2c3529"
  dark-line: "#46523f"
  dark-warning: "#e5c691"
  dark-warning-bg: "#3c3527"
  dark-map-bg: "#303c2e"
  dark-map-marker: "#8fa278"
  focus: "#829861"
  workbench: "#e9ece4"
typography:
  headline:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "25px"
    fontWeight: 700
    lineHeight: 1.45
    letterSpacing: "-0.5px"
  screen-title:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "23px"
    fontWeight: 700
    lineHeight: 1.5
    letterSpacing: "-0.45px"
  title:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 650
    lineHeight: 1.5
  body:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.6
  reading:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.8
  label:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.8
  field:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.6
rounded:
  card: "18px"
  button: "14px"
  field: "12px"
  chip: "9px"
  map: "16px"
  sheet: "26px"
spacing:
  content: "20px"
  compact-content: "16px"
  sheet-content: "22px"
  card-gap: "12px"
  control-gap: "8px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.button}"
    padding: "12px 18px"
    width: "100%"
  button-secondary:
    backgroundColor: "{colors.light}"
    textColor: "{colors.ink}"
    rounded: "{rounded.button}"
    padding: "12px 18px"
    width: "100%"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.field}"
    rounded: "{rounded.field}"
    padding: "10px 12px"
  photo-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.card}"
  pending-panel:
    backgroundColor: "{colors.warning-bg}"
    textColor: "{colors.warning}"
    rounded: "{rounded.field}"
    padding: "11px 13px 2px"
---

# Design System: MY PLAN 行程交互原型 V2

## Overview

这是 `design/travel-result-mobile` 视觉系统的普通延伸，依据本目录已实现的样式与组件提取。暖白背景、鼠尾草绿操作与实景照片保持原有品牌；文字、细分隔线和明确状态组织信息，便于逐项核对与修改。

概览以照片和每日速览帮助阅读；行程以时间、地点、来源和编辑入口帮助操作。待确认、已手改、固定时间、保存失败等状态用可读文字表达，颜色只提供辅助。照片承担目的地内容，界面不叠加装饰纹理或大面积品牌图形。

## Colors

`primary` 用于主要动作、当前标签底线、选中标点与路线；`light` 用于选中日期、固定标记和次要动作。`bg`、`surface`、`line` 形成页面、内容面和分隔层级，`ink` 与 `muted` 区分正文和补充信息。

`warning` 与 `warning-bg` 用于待定地点、输入错误及保存失败；错误仍有文字说明。地图使用 `map-bg` 和独立的 `map-marker`，当前标点再切换至主色；当前地点分布图没有绘制真实水域。

深色由同名 `dark-*` 值替换手机界面内的语义角色；工作台背景保持原样，照片不加深色滤镜。焦点使用独立 `focus` 色，不能用选中状态替代键盘焦点。

## Typography

统一使用 frontmatter 的系统中文无衬线栈，不加载在线字体。标题以字重和适度紧凑字距建立层级；正文保持疏朗行距，时间和数字在相应控件中使用等宽数字。

- `headline` 用于概览行程名，小于等于（350px）时缩为（23px）。
- `screen-title` 用于首页、创建和进度标题，小屏缩为（22px）；当天行程标题为（22px）。
- `title` 用于章节和站点名称；照片标题为（19px），面板标题为（19px）。
- `body` 是基础文字；`reading` 用于站点备注和照片说明，较长面板说明使用（1.9）行高。
- `label` 用于来源、位置、时长与状态。重要操作保持（13–16px），输入文字使用 `field`，不以缩小输入文字压缩表单。

## Layout

桌面工作台为说明、（414px）手机预览、设计说明三列，V2 列间距（36px）；手机壳高度（895px），受视口高度约束。宽度（1120px）以下转两列，（760px）以下单列；（680px）以下手机界面占满（100dvh），去除外壳边框、圆角和阴影，工作台控制与署名说明排在其下。

手机内容左右留白使用 `content`，小于等于（350px）改为 `compact-content`；面板正文使用 `sheet-content`。页头、保存状态、概览／行程标签与底部动作位于正文滚动区外，正文和面板分别纵向滚动。`env(safe-area-inset-*)` 与动态视口高度处理设备边缘和可见区域。

行程内先按天选择，再切换列表／地图；日期较多时横向滚动，不压缩成无法阅读的按钮。照片轨道使用原生横滑与 scroll-snap，卡间距为 `card-gap`，露出下一张提示可继续浏览。切换阅读视图保留各自滚动位置，地图与列表共享选中站点。

## Elevation & Depth

内容主要以表面色、细边框和留白分层。桌面手机壳使用轻阴影（`0 12px 44px #3c4d2a0c`），底部面板使用上抬阴影（`0 -7px 30px #14200f12`）；遮罩（`#10190f72`）聚焦当前操作。普通照片卡、日期和来源行不增加浮起阴影。

选中时间线节点使用主色实心点与淡绿外环（`0 0 0 3px var(--light)`）；地图选中标点通过尺寸、轮廓和颜色同时区分。

## Shapes

V2 照片卡与首页草稿卡使用 `card`，主要按钮使用 `button`，输入和提示面使用 `field`。照片卡沿用原有形态，圆角以 V2 实际规则为准。地图用 `map`，底部面板仅上方两角采用 `sheet`。

描边统一保持轻薄，选中时替换为主色。图标为内联细线 SVG；主要图标操作区为（44px），图标本身不承担全部点击面积。

## Components

### 按钮与字段

主要按钮全宽，最小高度（50px），次要按钮使用淡绿表面。文本按钮和图标按钮通常提供至少（44px）操作区；保存状态栏的低频重试入口保留紧凑布局。键盘焦点为（3px）轮廓、偏移（3px）；按下反馈为亮度（0.92）。hover 样式只用于支持精确指针的设备。

字段有独立标签、白色／深色表面和细描边；时间与时长并排展示，备注保持足够行数。错误在当前面板内说明并保留输入。文本较长时换行，不让站点名称或来源撑出手机边界。

### 导航、标签与状态

顶部保留概览和行程两个标签，选中项用主色底线和字重；方向键、Home 与 End 可切换。日期用描边按钮及淡绿选中面，列表／地图用分段按钮。固定时间与手改标记不混作同一含义；待确认使用暖棕提示及明确入口。

### 照片与时间线

照片卡保留名称、当天时间和停留时长；左右箭头与横滑并用，没有自动轮播。亮点卡仅展示与当前实际节点主题匹配的照片；其他站点使用相关配图时，在详情内说明。署名许可和攻略依据分别保留。

时间线以时间、可点击站名、备注、地点状态和来源构成。编辑与更多操作可换行排布，避免挤压名称。未知位置保持待定，交通行写明待核对；照片或示意线路不能代替已核实的地点与交通信息。

### 地图与底部面板

地点分布图采用独立背景和编号标点，默认高度（275px），极窄屏为（260px）；选中点内圆从（27px）增至（33px），外部操作区保持（44px）。未知位置仍出现在下方可选列表；虚线只连接连续已知节点。

面板承载逐站编辑、地点确认、来源、AI 预览和辅助工具。最高占手机界面（93%），手机宽度下为（95%）；头部关闭和底部动作固定，正文滚动。打开时背景不可操作并锁滚，焦点留在面板内；关闭回到触发点，来源嵌套返回保留原编辑上下文。减少动态效果偏好关闭动画、过渡和平滑滚动。

## Do's and Don'ts

- **Do** 使用现有语义色和对应深色值，让状态在两种主题中含义一致。
- **Do** 保留文字状态、来源与许可；待定信息允许继续留在草稿中。
- **Do** 为手机长标题、五天日期、双按钮和长备注提供换行或滚动空间。
- **Do** 以提交、放弃、预览、应用与撤销等明确动作解释当前状态。
- **Don't** 用通用风景图冒充未对应地点实拍，或把固定样例写成实时查询结果。
- **Don't** 为示意地图增加未经计算的道路、距离或交通耗时。
- **Don't** 将浏览器响应式截图描述为真机原生验收。
