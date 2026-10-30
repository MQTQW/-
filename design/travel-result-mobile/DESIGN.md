---
name: MY PLAN 手机旅行方案
description: 延续暖白与鼠尾草绿的图文旅行结果原型
colors:
  primary: "#65794f"
  on-primary: "#ffffff"
  bg: "#f7f8f2"
  ink: "#344331"
  muted: "#64735e"
  light: "#e7eddc"
  surface: "#ffffff"
  line: "#e0e6d9"
  warning: "#885b29"
  warning-bg: "#f4eee2"
  map-bg: "#ebefdf"
  water: "#cbdcda"
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
  dark-water: "#3a5450"
  dark-map-marker: "#8fa278"
typography:
  headline:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "25px"
    fontWeight: 700
    lineHeight: 1.45
    letterSpacing: "-0.5px"
  title:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 650
    lineHeight: 1.6
  body:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.6
rounded:
  card: "22px"
  button: "14px"
  field: "12px"
spacing:
  content: "20px"
  compact-content: "16px"
  card-gap: "12px"
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
---

# 旅行详情的手机适配

## Overview

本文件记录这个隔离原型已经落地的 MY PLAN 延伸，视觉依据是本目录 `base.css` 与 `auxiliary.js`。暖白、鼠尾草绿和轻巧圆角延续既有品牌；照片承担旅行内容，界面负责清楚地组织阅读与操作。

同一份完整方案按阅读任务分层：概览提供图文亮点、路线、预算和天气摘要与每日速览；每日用于核对和编辑；地图按天查看地点分布；更多承载完整辅助页、关联、来源与导出。低频信息有摘要入口，不因收纳而消失。

## Colors

主色用于主要操作、当前标签、选择状态和路线。背景、内容表面与淡绿选中面通过语义色区分；正文和次要文字保持各自层级。细分隔线组织列表，待确认状态使用暖棕提示及浅暖底，不用颜色独自表达含义。

深色切换采用对应的 `dark-*` 值，保持同一内容和语义角色。地图底、水域、普通标点与选中标点分开赋色；普通标点使用 `map-marker`，选中标点使用 `primary`。照片本身不加深色滤镜。

## Typography

使用系统中文无衬线栈，不依赖在线字体。概览标题采用 `headline`；小于等于 350px 时缩为（23px）。Appbar 采用 `title`，照片标题为（18–19px）；辅助页正文为（15px），主要说明为（13–14px），时间与出处等元信息为（11–12px）。表单字为（16px），时间、金额与气温使用等宽数字。

标题依赖字重和间距建立层级，不以大段装饰字体挤占手机阅读空间。

## Layout

Appbar 是返回／旅行详情／编辑，下面只有概览、每日、地图、更多四个页面内标签，不叠放 App 全局底部导航。底部主要动作与正文分离，正文独立纵向滚动；切换分区保留各自滚动位置，每日与地图共享日期和选中地点。

桌面工作台以（414px）手机壳居中，两侧放预览控制和说明；（1120px）以下转为两列，（760px）以下转为单列。小于等于（680px）时手机界面占满（100dvh），去掉外壳边框和阴影，预览控制与署名说明放到手机界面下方。内容左右留白使用 `content`，小于等于（350px）改用 `compact-content`。

安全区通过 `env(safe-area-inset-*)` 处理；底部面板与正文各自滚动。照片横向轨道使用浏览器原生 scroll-snap，卡片之间为 `card-gap`，完整展示单卡并露出下一张，纵向页面仍可正常阅读。

## Elevation & Depth

内容卡片主要用表面色和细边框分层。阴影仅用于桌面手机外壳（`0 12px 44px #3c4d2a0c`）和底部面板（`0 -7px 30px #14200f12`）；手机宽度去掉外壳阴影。面板遮罩（`#10190f72`）帮助集中注意力。

## Shapes

照片、预算总额与主要辅助卡片使用 `card` 圆角；主要动作使用 `button` 圆角；输入框采用 `field` 圆角。底部面板仅上角为（26px），保持从底部出现的连续表面。图标使用内联细线 SVG，常规图标按钮提供（44px）点击区。

## Components

- **导航**：当前标签加主色底线和字重；日期与关联视角使用带明确选择状态的按钮。主标签支持方向键、Home 与 End。
- **照片卡**：名称、日次、时间和停留时长始终在卡内可读。没有自动轮播或 3D 遮挡；支持原生横滑与（44px）左右箭头，点击进入照片介绍、出处和对应日期。
- **时间线与地图**：待确认门店在概览、时间线和地图列表保持一致，未知位置暂不标点。虚线仅连接相邻且已知位置的安排。雷峰塔景区与登塔是同站及可选游览内容。编辑保留出处，所在日交通转待更新。
- **预算与天气**：概览有摘要，完整信息在辅助页。预算按分类编辑，合计即时更新；未核对的零元门票不表达免费。天气区分远期准备状态与三日展示示例。
- **底部面板**：用于地点确认、逐站编辑、详情、准备清单、保存回执和导出。面板最高（91%），背景锁滚且不可交互，焦点限制在面板内；Esc 或关闭按钮退出，同页关闭后回到触发点。编辑错误在面板内说明并保留输入。
- **按钮与动效**：主要动作最小高度（50px），文本动作至少（44px）；hover 仅对精确指针生效，按下有亮度反馈。面板入场为（220ms）的短距离上移与淡入，标签切换无频繁动画；减少动态效果偏好会关闭动画、过渡和平滑滚动。

## Do's and Don'ts

- **Do** 延续现有暖白／鼠尾草绿语义色和圆角，使用真实配图表达目的地。
- **Do** 把攻略依据和照片许可分别保留；修改安排仍能追溯来源。
- **Do** 让待确认项留在草稿里，并在相关视图明确显示状态。
- **Don't** 给未知路段添加假精确耗时，或让地图示意承担道路导航含义。
- **Don't** 将固定天气、费用或候选门店包装成实时查询结果。
- **Don't** 将浏览器尺寸检查描述成真机原生验收；触摸惯性、软键盘与设备安全区仍需手机体验。
