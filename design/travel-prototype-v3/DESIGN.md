---
name: MY PLAN 先选内容，再编排行程 V3
description: 沿用暖白与鼠尾草绿，让用户先选择具体旅行内容，再查看可编辑的安排
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
  text-selection: "#d9e5c8"
  photo-label: "#20301bd9"
  workbench: "#e9ece4"
  workbench-row-line: "#d2d9ca"
  workbench-theme-line: "#cdd6c3"
  workbench-select-line: "#ccd4c3"
  workbench-credits-line: "#cfd7c7"
  workbench-hover: "#465f2d"
  device-line: "#cfd8c4"
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
  day-title:
    fontSize: "22px"
    lineHeight: 1.5
    letterSpacing: "-0.35px"
  candidate-title:
    fontSize: "19px"
    fontWeight: 650
    lineHeight: 1.6
  section-title:
    fontSize: "17px"
    fontWeight: 650
    lineHeight: 1.6
  body:
    fontFamily: "Microsoft YaHei UI, PingFang SC, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.6
  reading:
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.8
  description:
    fontSize: "13px"
    lineHeight: 1.9
  label:
    fontSize: "12px"
    lineHeight: 1.8
  source:
    fontSize: "11px"
    lineHeight: 1.7
  field:
    fontSize: "16px"
    lineHeight: 1.6
  workbench-title:
    fontSize: "29px"
    lineHeight: 1.5
    letterSpacing: "-0.7px"
rounded:
  utility: "4px"
  badge: "5px"
  checkbox: "6px"
  photo-label: "7px"
  segment: "8px"
  chip: "9px"
  day: "10px"
  field: "12px"
  button: "14px"
  map: "16px"
  result-card: "18px"
  inherited-map: "20px"
  candidate: "22px"
  sheet: "26px"
  device: "32px"
  circle: "50%"
spacing:
  control-gap: "8px"
  paired-gap: "12px"
  candidate-content: "16px"
  candidate-gap: "18px"
  content: "20px"
  sheet-content: "22px"
  section: "24px"
  workbench-gap: "36px"
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
  candidate:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.candidate}"
  selected-wish:
    backgroundColor: "{colors.light}"
    textColor: "{colors.ink}"
    rounded: "{rounded.button}"
    padding: "8px 13px"
  photo-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.result-card}"
---

# Design System: MY PLAN V3

## Overview

这是 `design/travel-result-mobile` 品牌在 V2 基础上的普通延伸，以当前五份 CSS 和已实现组件为准。暖白、鼠尾草绿、轻描边与实景照片保持原有气质；V3 新增城市发现、候选与想去清单，沿用原来的字段、底部面板、结果与保存状态，不另起视觉主题。

候选提供具体名称、区域、停留参考与来源，选择动作清楚可见；出行条件放在清单之后。待定、手动补充、补充灵感、必选、固定时间与保存失败均有文字，不只靠颜色区分。本文件记录当前规范，不要求把继承的工作台或局部视觉修正机械地压成更窄的 token 集。

## Colors

手机界面使用 `.device` 的语义变量：`primary` 承担主要操作、当前下划线和选中标点；`light` 承担次要操作与选择底色；`bg`、`surface`、`line` 区分页面、内容面和细分隔。`ink` 用于正文，`muted` 用于来源、区域、时长及说明。`warning`／`warning-bg` 用于待定、错误和保存失败，同时保留可读文案。

深色由同名 `dark-*` 替换手机内语义角色；地图使用独立 `map-bg`／`map-marker`，当前标点切换到主色。照片不加深色滤镜，当前地图也不绘制真实水域。基础样式中保留的水色变量不是 V3 地图内容规范。

`focus` 是键盘焦点轮廓；全局选中文字使用 `text-selection` 与 `dark-on-primary`，结果内选择文字改用当前主题的 `light`／`ink`。`photo-label` 是照片时间角标底色。桌面工作台保持浅色，使用 frontmatter 中的 `workbench-*` 边线与 hover 值，以及 `device-line` 外壳边线；这些继承的原型工具值是合法上下文，不应套用手机深色角色。

## Typography

全局系统中文无衬线栈为 `Microsoft YaHei UI, PingFang SC, system-ui, sans-serif`，无在线字体。frontmatter 的局部角色继承该字体；字重和行距根据阅读密度调整，没有固定比例的字号级数。时间、天数和计数在相关控件中使用等宽数字。

| 使用层级 | 当前工作字号与用法 |
| --- | --- |
| 主要标题 | 25px 行程名；23px 城市、清单、条件标题；22px 当天标题。窄屏标题分别降至 23px／22px。 |
| 卡片与面板 | 19px 候选、照片、面板标题；极窄屏常为 18px。17px 章节和站点，窄屏站点为 16px。 |
| 正文与操作 | 15px 基础文字／清单名；14px 阅读正文／备注；13px 候选描述与文字操作；输入和主要按钮为 16px。 |
| 辅助文字 | 12px 区域、时长、帮助；11px 来源与状态；10px 用于继承的紧凑保存栏、地图北向和部分工作台／窄屏辅助值。小字号不承担长篇正文。 |
| 继承的局部标题 | 20px 基础地图标题、21px 来源／完成标题、24px 窄工作台标题、29px 桌面工作台标题，继续按对应组件使用。 |

当前完整字号集合为 10、11、12、13、14、15、16、17、18、19、20、21、22、23、24、25、29px。正文行高主要为 1.6–1.9；长说明常为 1.8／1.9。局部紧凑值用于数字、状态或工具面板，不是新的全局正文样式。

## Layout

桌面工作台为说明、414px 手机预览、设计说明三列，列间距 36px；手机壳高 895px，受视口高度限制。1120px 以下转两列，760px 以下单列；680px 以下手机占满 `100dvh`，去掉外壳边框、圆角和阴影，预览控制与署名排到界面下方。保留 `env(safe-area-inset-*)` 与动态视口处理；浏览器截图不等同于真机验证。

手机正文左右 20px，350px 以下为 16px；底部面板正文 22px，极窄屏为 18px。页头、保存栏和底部主动作处于正文滚动区外；正文与面板各自纵向滚动。常用间距见 frontmatter，继承样式仍保留 3–7px 的图标对齐和其他光学调整，不强制成单一四倍数网格。

候选固定为一列，卡间距 18px；有图卡使用顶部照片与下方信息，没有匹配照片的卡保留完整文字结构。照片高 160px，350px 以下为 164px。分类按钮和结果日期在手机宽度内保持可读，五天日期可横滚。结果亮点用原生横滑、scroll-snap 和下一张露出提示，卡间距 12px；列表／地图共享选中站，切换阅读视图保留滚动位置。

## Elevation & Depth

内容以表面色、描边和留白分层，候选和普通结果卡不加浮起阴影。桌面手机壳使用 `0 12px 44px #3c4d2a0c`；底部面板使用 `0 -7px 30px #14200f12`，遮罩为 `#10190f72`。选中时间线点用主色实心与 `0 0 0 3px var(--light)` 外环，地图选中点同时改变大小、轮廓和颜色。

面板入场为 220ms、`cubic-bezier(.2,.8,.2,1)`，位移 32px 并淡入；不自动轮播照片。减少动态效果偏好关闭动画、过渡和平滑滚动。

## Shapes

角半径按组件角色使用，而非所有卡片统一：V3 候选 22px，结果照片／首页草稿卡 18px，主按钮和选择／必选按钮 14px，字段与提示面 12px，地图 16px，面板仅上方两角 26px，桌面手机壳 32px。基础地图 20px 的继承规则仍保留，当前结果地图由 16px 覆盖。

4–10px 的小半径分别服务于手柄、徽标、复选框、照片角标、分段控件、地图标签和日期；圆形用于图标操作区、进度数字和地图标点。frontmatter 记录这些工作值，不把细小标记与主卡片混为同一形状。描边通常 1px，当前主标签底线为 3px；图标使用内联细线 SVG。

## Components

### 按钮、字段与选择状态

主要按钮全宽、最小高 50px；次要按钮使用淡绿。候选「加入」和清单必选按钮最小高 44px，14px 圆角，选中后使用主色描边、淡绿底和可读文字。普通文字／图标入口通常提供至少 44px 操作区；保存栏重试是保留的紧凑工具入口。键盘焦点为 3px 轮廓、偏移 3px，按下亮度 0.92；hover 只在精确指针设备上生效。同屏状态更新保留对应控件的键盘焦点，换页或控件被移除时回到页面标题。

字段使用当前主题表面、细描边、16px 输入文字和独立标签。目的地与筛选字段为 12px 圆角、最小高 50px；筛选展开后为紧凑的 46px。错误紧邻当前操作，以暖棕提示说明并保留输入。长名称和备注允许换行，不挤出手机边界。

### 城市、候选与想去清单

城市页先展示城市字段与三步流程；整理进度明确写「固定资料的本地流程演示」。发现页以景点、美食、体验三个分类及数量组织内容，筛选折叠收起；资料是依据入口，用户选择对象是具体内容。

每张候选保留名称、区域、说明、停留参考、来源和加入动作；已加入同时显示勾选文字与边框。清单按三类排列，支持独立移除与必选切换。空清单解释下一步，主动作禁用；手动补充与作者灵感有各自来源文字。必选含义为内容保留，结果中与「固定时间」使用不同标签文字。

### 照片、时间线与地图

仅雷峰塔、灵隐寺、拱宸桥有对应主题的候选／结果照片；没有匹配照片的断桥、九溪、食物和体验不借景配图。西湖全景可用于首页草稿封面。保留四张照片的作者与许可，攻略依据与照片署名分开表达。

时间线先呈现时间、站名，再呈现时长、位置状态、备注与来源；编辑和更多动作可换行。未知位置不标点、仍可在下方列表选择。地图高 275px，350px 以下 260px；标点内部从 27px 选中为 33px，外部点击区保持 44px。虚线仅连接相邻已知位置，交通行保持「待核对」，不补造道路、距离或耗时。

### 底部面板与结果操作

候选详情、来源、手动补充、逐站编辑、体验地点和 AI 预览共用面板。面板最大为手机高度的 93%，手机宽度下 95%；头部和底部操作固定，正文滚动。背景锁滚且不可操作，焦点留在面板内，关闭后回到触发点；嵌套来源返回原来的编辑上下文。

预览、应用、放弃、提交和撤销使用明确动作。菜品待选餐馆，体验只接受用户的地点文字并继续提示坐标待核对；不把体验替换成无关 POI。保存失败保留内容说明和重试入口，不以成功色掩盖未落盘状态。

## Do's and Don'ts

- **Do** 复用当前语义色及深色对应值，保持候选、清单、条件和结果属于同一品牌。
- **Do** 为选择、必选、固定、手改、来源、待定和保存状态保留准确文字。
- **Do** 沿用实际字号／圆角工作集合，并区分手机产品界面与原型工作台的合法局部值。
- **Do** 给长标题、来源、多天日期和成组操作提供换行或滚动空间。
- **Don't** 用风景照片冒充未匹配地点实拍，或为手动内容补造出处、坐标。
- **Don't** 把固定资料整理、示意地图或页面计时器写成实时检索、导航或后台服务。
- **Don't** 把必选和固定时间混为一谈，或用颜色代替含义不同的标签。
