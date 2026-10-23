<p align="center">
  <img src="docs/assets/readme/cover.png" alt="MY PLAN — 把重要的事，安排好。Android 个人计划、提醒与专注应用。" width="100%">
</p>

<h1 align="center">MY PLAN</h1>

<p align="center">
  把计划记下来，把时间留给生活。<br>
  一款采用原生 Jetpack Compose 构建的 Android 个人计划提醒应用。
</p>

<p align="center">
  <strong>Android 10+</strong> &nbsp; · &nbsp; Kotlin &nbsp; · &nbsp; Jetpack Compose &nbsp; · &nbsp; <strong>v1.0.4</strong>
</p>

<p align="center">
  <a href="#界面预览">界面预览</a> &nbsp; / &nbsp;
  <a href="#开始使用">开始使用</a> &nbsp; / &nbsp;
  <a href="#本地构建">本地构建</a> &nbsp; / &nbsp;
  <a href="https://github.com/MQTQW/-/issues">问题反馈</a>
</p>

<br>

## 让每一天，更有条理

临时要办的事、每天重复的小习惯，以及想专心完成的计划，都可以放在这里。首页先呈现下一条提醒与今天待办；暖白和鼠尾草绿的界面，也有对应的深色主题。

| 记下计划 | 留意时间 | 留给专注 |
| :--- | :--- | :--- |
| **一句话快记**<br>从通知栏输入「十分钟后喝水」，不用先打开首页。常用时间表达在本地解析。 | **多时段与重复提醒**<br>支持一次性、每天、工作日和自选星期；同一计划可以安排多个提醒时段。 | **应用使用限额**<br>查看用量，按应用或分类设置限制；完成计划获得的积分可用于解锁。 |
| **今天待办**<br>查看下一条提醒、完成今日计划，也可以搜索、暂停和撤销删除。 | **日历与统计**<br>按日期查看安排与完成记录，让计划和进度都看得见。 | **可选语音创建**<br>说出计划，经确认后保存。语音识别与对话需要另行配置云服务。 |

## 界面预览

以下为 Android 15 模拟器中的实际应用截图，使用演示数据。点击图片可查看大图。

<p align="center">
  <img src="docs/assets/readme/screens-today.png" alt="今天首页、创建计划和日历：查看下一条提醒，编辑时段，按日期浏览安排。" width="100%">
</p>

<p align="center">
  <img src="docs/assets/readme/screens-organize.png" alt="全部计划、完成统计和专注：搜索筛选计划，查看打卡，管理应用使用限额。" width="100%">
</p>

<details>
<summary><strong>再看看深色主题与通知栏快记</strong></summary>

<p align="center">
  <img src="docs/assets/readme/dark.png" alt="MY PLAN 深色主题首页" width="32%">
  &nbsp;&nbsp;
  <img src="docs/assets/readme/quick-capture.png" alt="通知栏直接输入事项并创建一次性提醒" width="32%">
</p>

通知栏快记由系统 RemoteInput 提供输入入口，通知的外观随 Android 版本与手机系统而变化。

</details>

## 开始使用

当前仓库提供源码，可按下方步骤构建调试 APK。安装后可以这样开始：

1. **创建第一条计划**：填写事项、时间和重复方式，按需增加提醒时段。
2. **检查提醒权限**：在「设置 → 安心使用」中检查通知与精确闹钟权限，再用「测试提醒」进行一次锁屏测试。
3. **开启通知栏快记**：前往「设置 → 快捷创建 → 通知栏快记」，之后拉下通知栏即可记事。
4. **添加桌面小组件**：长按桌面空白处，在小组件列表找到「MY PLAN · 日常卡片」。查看下一条提醒和今日进度，直接完成、撤销，或点「＋」打开快速创建小窗。

| 想记什么 | 可以这样输入 |
| :--- | :--- |
| 临时的小事 | `十分钟后喝水` |
| 明天的安排 | `明天晚上六点取快递` |
| 固定的小习惯 | `每天晚上九点读书` |
| 工作日的计划 | `工作日早上八点开会` |

快记默认创建「仅这一次」的计划；只有明确说出重复规则时才会周期提醒。缺少时间或表达存在歧义时，会先请你确认。创建结果支持修改和撤销。详见 [通知栏快记说明](docs/notification-quick-capture.md)。

### 关于提醒声音

「通知＋闹铃」模式使用系统闹钟音量，支持试听、停止响铃和最长一分钟的播放时限。首次使用建议在锁屏、离开应用后分别测试。

手机的通知权限、闹钟音量、勿扰规则与厂商后台管理会影响实际提醒。遇到「通知到了但没声音」，可在「设置 → 安心使用 → 响铃排查记录」中查看并导出记录。详细行为见 [闹铃与后台排查说明](docs/alarm-audio.md)。

### 数据与可选权限

- **计划保存在本机**：计划、提醒和完成记录由 Room 保存；通知栏快记的时间解析不依赖网络。
- **语音按需开启**：点击语音入口并授权录音后才开始采集；云端语音识别、合成和对话需要网络与服务配置，离开应用后停止录音。
- **专注功能按需授权**：用量统计与应用限制需要使用情况访问、无障碍等相应权限；普通计划功能不需要开启专注权限。
- **支持导出 JSON 备份**：包含计划、提醒和完成历史；当前尚未提供应用内导入界面。

## 本地构建

需要 **JDK 17**、**Android SDK Platform 35** 和 **Build Tools 35.0.0**。也可以用 Android Studio 打开项目，由 Gradle Wrapper 构建。

```bash
git clone https://github.com/MQTQW/-.git my-plan
cd my-plan
```

将 [`local.properties.example`](local.properties.example) 复制为 `local.properties`，填写本机 `sdk.dir`。构建普通计划和提醒功能无需真实 API 密钥；不使用语音时，将两项服务密钥留空。

**Windows / PowerShell**

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

**macOS / Linux**

```bash
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
  testDebugUnitTest lintDebug assembleDebug
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`。

语音服务配置、Windows 环境排查、数据库迁移和测试范围见 [开发说明](docs/development.md)。`local.properties` 已被 Git 忽略；正式分发前应将长期服务密钥迁至受认证的服务端。

## 项目结构

```text
app/src/main/java/com/example/myplan/
├── data/           计划、完成记录、设置与 Room 数据库
├── notification/   闹钟调度、通知、声音播放与排查记录
├── quickadd/       通知栏快记、本地时间解析与确认小窗
├── widget/         桌面日常卡片、完成与撤销
├── focus/          使用统计、应用限额与解锁积分
├── ui/             Compose 页面、导航、共享组件与主题
└── voice/          语音识别、对话确认与语音合成

app/src/test/       本地回归测试
app/schemas/        Room 数据库结构
design/            界面设计源文件与设计说明
docs/              功能说明与开发文档
```

界面采用 **Kotlin + Jetpack Compose / Material 3**，状态由 **ViewModel、Flow 与 Coroutines** 管理，数据使用 **Room**，定时提醒使用 **AlarmManager**，网络请求使用 **OkHttp**。当前验证范围与已知限制记录在 [开发说明](docs/development.md#测试与验收) 中。

## 反馈与参与

欢迎通过 [Issues](https://github.com/MQTQW/-/issues) 提交问题或建议，也欢迎提交 Pull Request。反馈提醒问题时，请附上应用版本、机型、Android / ColorOS 版本、复现步骤，以及可用的响铃排查记录；发布前请检查并移除个人信息。

本页排版参考了 [AppFlowy](https://github.com/AppFlowy-IO/AppFlowy) 的项目导览和 [Material Files](https://github.com/zhanghai/MaterialFiles) 的界面展示方式；封面与展示图为本项目制作，应用界面来自实际运行截图。

---

<p align="center">
  <strong>MY PLAN</strong><br>
  计划、提醒与专注，放在一起。
</p>
