# MCP Windows 标准 Chrome 适配

上游：https://github.com/xpzouying/xiaohongshu-mcp

基线：a5c8f7799980ba1fdd501999843eb2d17e4c9a9f

在该基线应用 compat.patch，再复制 files/ 下的新增源码到相同相对路径，按 go.mod 使用对应 Go 工具链构建。此适配保留浏览器 sandbox，不启用指纹伪装或 stealth。Windows 便携包已附本机构建的 x64 可执行文件；不含小红书 Cookie 或访问令牌。

运行时用 POC_STANDARD_CHROME_BIN 指定已安装的 Chrome，COOKIES_PATH 指向本机私有目录，AUTH_TOKEN 由启动器每台机器独立生成。许可见 LICENSE。
