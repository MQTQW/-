# README 视觉素材

`cover.png`、`screens-today.png` 和 `screens-organize.png` 由本目录的 `source.html` 排版导出。暖白与绿色沿用应用主题；封面的叶片图形为展示素材，不修改应用图标。

`today.png`、`create.png`、`calendar.png`、`plans.png`、`stats.png`、`focus.png`、`dark.png` 和 `quick-capture.png` 是应用 1.0.1 在 Android 15 模拟器上运行的原始截图，使用专门准备的演示数据。系统状态栏使用 Android 自带的演示模式隐藏通知图标；应用画面未重绘。截图不代表所有手机上的系统通知样式。

修改文案、排版或替换截图后，可重新生成展示图：

```bash
node docs/assets/readme/render.cjs
```

脚本需要 Playwright 与 Chrome；设置 `CHROME_PATH` 可以指定 Chrome 的位置。导出在离线模式下运行，输出尺寸为源布局的 1.5 倍，便于 GitHub 缩放显示。`source.html` 可直接在浏览器打开查看。

更新应用界面时应重新截图，不应只修改展示图中的界面文字。
