# MCP 标准 Chrome 与搜索适配

固定上游基线为 `a5c8f7799980ba1fdd501999843eb2d17e4c9a9f`，许可见本目录 LICENSE。[上游仓库](https://github.com/xpzouying/xiaohongshu-mcp)。

在该基线应用 `compat.patch`，再将 `files/.` 复制到源码树的相同相对路径。该覆盖包含标准浏览器配置、替换后的 `xiaohongshu/search.go` 和可选集成测试。Windows 源码构建采用此顺序，常规容器构建自动执行这两步。cached-source 与 vendor-offline 构建在校验、解包后复制同一覆盖。Windows 启动器使用预先构建的运行程序，更新源码后需重新构建对应程序。小源码包仍保留原 SHA-256 `96cfedb5c3888e9952944bb3b9fb08330b91ec53c0dac6bea3b72037121e9563`。

运行时用 `POC_STANDARD_CHROME_BIN` 指定已安装的 Chrome，用 `COOKIES_PATH` 指向私有状态目录。Windows 启动器为本机生成连接访问码，云端入口从私有配置读取并以 `AUTH_TOKEN` 传给 MCP。标准模式保留浏览器沙箱和自动化标识，使用独立浏览器配置目录。

首屏稳定性等待使用 5 秒子上下文，后续读取继续沿用父级 60 秒搜索上下文。无筛选请求若耗尽短等待，会在父上下文内等首批非空 `feeds.value` 或 `feeds._value` 数据。已稳定页面的空数组继续正常返回空结果。诊断仅记录阶段耗时、枚举路由和就绪状态、布尔值及数组计数。

2026-11-05 的最终 `search.go` SHA-256 为 `eae20abce4843ab7f5e7037807c2852ae657a71ac45f30a8929bc119e4ce7f17`，集成测试源码为 `6c5ff00a94ba9cd437832e4131c79db6f3e3338d5e4710cb3cfe5dd124a56c7d`。集成测试通过 `integration` 标签启用，使用显式标准 Chrome、隔离临时配置和本地页面拦截。Windows 首轮曾在浏览器清理阶段耗尽 90 秒，测试工厂增加自有进程的有界清理后，四项测试与四个字段子例在 35.745 秒通过。Linux 在最终云端运行环境也执行全部用例并通过，跳过数为 0。

云端运行镜像 ID 为 `sha256:c05deb41e3a613af90b474f08aa0729a3c4a2ac7f8048e494133540dfb9863fe`。私有检索实际返回 20 条结果，读取 1 篇详情，正文长度 619、图片目录 12 项，并实际取回 1 张 576460 字节的 WebP。2026-11-06 的原生 HTTP 接口另已通过真实 AI／高德闭环，包括 12 个带出处候选和 5 项所选内容的两日排程。备案、HTTPS 与手机移动网络验收继续推进。
