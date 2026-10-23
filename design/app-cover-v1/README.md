# MY PLAN 启动封面

用途：应用打开后的品牌封面。原有桌面图标、本地数据和功能入口保持原样。

沿用「柔和陪伴」方向：暖白、鼠尾草绿、柔和圆角与克制留白。主视觉由计划卡片、勾选、提醒铃铛和叶片组成，透明背景适配现有浅色 / 深色主题。文字由 Compose 原生绘制，内容为「MY PLAN」「计划 · 提醒 · 专注」，不把文案烘焙进图片。

源资产：`cover-illustration.png`。应用资源：`app/src/main/res/drawable-nodpi/cover_companion_v1.png`，与源资产一致。仅按设备可用尺寸缩放显示，不裁剪。启动页保留既有 1.5 秒跳转行为。Android 12 及以上的系统启动窗口使用同一主视觉与系统浅深色背景，避免在封面前出现默认图标。

## 实际效果

`cover-light.png` 和 `cover-dark.png` 为 Android 16 模拟器中最终 APK 的实际启动页截图；`cover-small.png` 为 360×640 dp、字体缩放 1.3 倍场景。已核对主视觉和文字未遮挡或裁切，封面结束后正常进入首页。系统启动窗口跟随系统主题，应用封面跟随应用主题设置。

生成方式：内置 `image_gen`，透明背景；未使用 CLI / 外部 API 密钥。下方记录完整提示词，便于后续保持视觉方向。

```text
Use case: illustration-story. Asset type: central hero illustration for the MY PLAN Android app launch cover, also legible at small mobile sizes. Primary request: a refined, warm and gentle illustration consistent with a minimalist personal reminder and planning app. Subject: a softly rounded ivory paper planning card, a single confident sage-green checkmark on the card and two very short quiet checklist strokes; a small rounded sage reminder bell nestled against the lower side; one tiny two-leaf sprout growing naturally beside the card. Keep these as one cohesive compact composition, not scattered floating objects. Style: premium contemporary editorial app illustration, softly sculpted paper and matte clay mixed with clean flat shapes, delicate depth and extremely restrained shadows, friendly but mature, no anthropomorphic face. Palette: muted sage #65794F, pale sage #E7EDDC, warm ivory #F7F8F2, dark olive #344331, limited soft cream highlights. Composition: square canvas, central object group occupies about 75 percent, generous transparent breathing room, straight-on view with a slight tasteful perspective, clean crisp edges. Lighting: soft diffused natural studio light. Background: genuinely transparent alpha, no colored rectangle, no floor or backdrop. Text: absolutely no text, numbers, letters, logos, signature or watermark. Avoid: clocks with numbers, interface screenshots, phone mockups, ornate decoration, glitter, confetti, neon, photorealism, heavy glossy 3D, unrelated animals, badges, harsh outlines. The image must feel calm, useful, distinctive and beautifully balanced on both warm white and dark olive app backgrounds.
```
