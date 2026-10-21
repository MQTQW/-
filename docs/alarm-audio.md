# 闹铃播放与排查

一加 ACE 2 上曾出现“提醒通知送达，但没有声音”。旧实现依靠通知渠道与 `FLAG_INSISTENT` 发声，不能单凭通知送达就确认铃声播放成功。

## 1.0.1：后台冷启动

在关闭专注监控、无障碍等常驻组件并结束应用后台进程后，Android 15 模拟器复现了另一个无声路径：系统已启动闹铃前台服务，但音频焦点请求被 `HardeningEnforcer` 拒绝，原播放器随即结束。此前只验证锁屏播放时，应用进程或其他前台服务仍在运行，覆盖不足。

现在媒体焦点被拒绝、且不处于通话音频模式时，改用公开的 `Ringtone` 闹铃接口，保持 `USAGE_ALARM`、用户闹钟音量和系统勿扰规则。停止和一分钟超时同样终止这条后备路径，不降低 target SDK，也不修改系统 app-ops、音量或勿扰设置。同时将短时可压低音量的焦点事件改为降低音量继续播放，避免不必要地暂停闹铃。

「设置 → 安心使用 → 响铃排查记录」可以查看与导出触发记录，包含收到系统闹钟、前台服务建立、焦点结果、播放器准备/开始/停止和页面进入时间。记录只含计划内部编号，不含计划正文、铃声文件 URI、账号或 API 密钥。报告也列出应用版本、机型、系统和权限状态，便于将真机问题与模拟器结果区分。

## 1.0.2：直接服务交付与延迟诊断

Android 16 真机反馈出现了另一类情况：第一次进入应用处理闹钟回调已经晚于预定时间，而后续焦点与播放器准备很快完成。这不能归因于先前发现的焦点拒绝，也不能仅凭 `audio_started` 判断扬声器实际已经出声。

闹铃模式的 `setAlarmClock` 现在使用显式的 `PendingIntent.getForegroundService`，由系统直接启动 `AlarmPlaybackService`。服务先建立短时的无声核对通知并持有有时限的唤醒锁，然后通过共享的 `PlanActions.fire` 校验计划、提醒与调度版本；只有通过校验才开始播放。核对期间不启动首页。暂停、删除和修改前的过时提醒会被忽略；一次性计划沿用消费版本防重规则，重复计划继续调度下一次。

通知模式保留广播交付。取消或重建闹钟会同时取消旧广播与新服务的 PendingIntent，避免升级和切换模式后重复触发；一分钟测试提醒在闹铃模式下使用相同的直接服务入口。前台核对阶段最多十秒，播放阶段仍最多一分钟，不通过常驻保活、额外轮询闹钟、降低 target SDK 或自动抬高系统音量来补救。

诊断记录扩展到最近 160 条，增加调度登记、服务收到事件、应用进程创建、页面创建、焦点暂停/恢复，以及触发当时的交互、休眠、省电和闹钟音量状态。Android 11 以上附带最近三次进程退出的系统原因码，不包含异常正文。导出时的当前状态与事件发生时的状态分别展示，防止把事后音量或权限当作触发当时的证据。

设置主页面和权限检查子页面均可打开排查记录，支持长按选择、复制与导出。「后台运行」原来只查询 `isIgnoringBatteryOptimizations`，现更名为「电池优化豁免」，明确不能代表 ColorOS 等厂商的全部后台活动与自启动规则。专注权限不计入提醒权限待检查数量。

直接服务交付旨在减少广播到服务的交接，需要在目标厂商系统上复测；Android 模拟器无法证明 ColorOS 对应用冻结、后台启动与声音输出的全部规则已经满足。系统 API 依据：[精确闹钟与前台服务例外](https://developer.android.com/develop/background-work/services/alarms)、[后台启动前台服务的例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[PendingIntent.getForegroundService](https://developer.android.com/reference/android/app/PendingIntent#getForegroundService(android.content.Context,int,android.content.Intent,int))。

现在「通知＋闹铃」在到点后启动短时 `mediaPlayback` 前台服务，通过 `MediaPlayer` 和 `USAGE_ALARM` 播放，使用系统的闹钟音量。播放最多持续一分钟，可通过通知中的「停止响铃」、点击通知、完成或暂停计划、删除或编辑该计划结束。播放器、音频焦点、震动与唤醒锁随播放结束一起释放，服务不会自动重启响铃。

自选铃声失效时，依次尝试系统闹铃与应用内置铃声。内置 WAV 是自行合成的音效，不依赖网络或第三方文件。应用内明确选择「静音」时不会启动音频播放。「通知＋响铃」模式保留系统通知渠道的提醒方式。

在「设置 → 提醒与声音」中：

- 「闹钟音量」显示当前系统音量，并可打开系统声音设置。
- 「立即试听闹铃」运行同一条播放链路，五秒后自动结束，也可以提前停止。
- 「测试提醒」仍在一分钟后触发，可用于锁屏测试。
- 「勿扰提醒例外」打开系统的勿扰访问授权页面；授权后闹铃通知渠道请求允许优先提醒。只有优先勿扰模式且闹钟被排除时，使用这个已授权渠道发声，避免同时播放两份音频。

普通铃声静音与闹钟音量分别管理。勿扰模式允许闹钟时，独立闹铃可以继续播放；禁止闹钟的优先勿扰需要用户授予例外权限。完全静音、闹钟音量为零、被用户关闭的渠道及厂商额外限制仍可能阻止声音，界面会提示可检测到的状态。应用不擅自修改全局勿扰模式或调高音量。

验证时需要同时检查实际音频输出、服务生命周期和通知停止动作，不能只检查通知记录。Android 15 的模拟器可通过 SDK 自带、经过认证的音频捕获接口记录 PCM，再检查非零样本；这能验证系统输出音频，但不能代替一加真机扬声器与系统策略测试。

Android 规则依据：[后台前台服务的精确闹钟例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[音频焦点](https://developer.android.com/media/optimize/audio-focus)、[勿扰渠道例外](https://developer.android.com/reference/android/app/NotificationChannel#setBypassDnd(boolean))。

后台焦点判断的实现可参考 [AOSP HardeningEnforcer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/audio/HardeningEnforcer.java)；本项目的复现和音频采样保存在对应本地交付目录。
