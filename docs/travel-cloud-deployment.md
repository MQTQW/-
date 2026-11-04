# 旅行 AI 云端部署（个人自用）

目标是手机通过移动网络或任意 Wi-Fi 完成「检索攻略 → 自己选择 → AI 排程」，个人电脑可以关机。手机仍保存自己的行程；小红书检索与模型调用在持续运行的 Linux 主机上完成。

**当前状态：提供部署代码和检查工具，尚未购买服务器／域名，未部署到公网。** 本机没有 Docker 引擎，容器构建、Linux Chromium 沙箱、域名证书和手机跨网访问仍需在选定服务器上验收。代码测试或交叉编译不替代这些验证。

Android 云端接入源码版本为 **1.0.9（10）**。新安装不预填不存在的服务地址；部署完成后在 App 填入自己的 HTTPS 地址和个人访问码即可，不需要 USB 转发。已配置的本机开发地址继续保留为调试选项。

## 购买参考

以下为 2026-11-04 核对的官网参考信息，并非已下单或保证可获得的结算价；域名、模型调用和超额流量另计。

| 路线 | 配置参考 | 官方价格信息 |
| --- | --- | --- |
| 大陆年付活动 | Linux 4 核／4 GB | 腾讯云活动页列示符合条件的新用户首年 109 元；新用户资格、库存和续费价需在购买页核对。[活动说明](https://cloud.tencent.com/act/pro/lhsale) |
| 香港按月验证 | Linux 2 核／4 GB／70 GB SSD | 腾讯云 2025-09-15 调价公告列示刊例 90 元／月，可作为比较参考；实际在售套餐与结算价需重新核对。[价格公告](https://cloud.tencent.com.cn/announce/detail/2131) |

服务商对中国大陆源站的域名接入有备案要求，香港源站没有该项接入要求，见[腾讯云接入规则](https://cloud.tencent.com.cn/document/product/243/18908)。这影响首次上线时间；不能只比较促销首年价格。个人验证可先比较按月方案，实际云端检索、沙箱和手机移动网络验收成功后再决定长期购买。

本轮已通过部署配置脚本 3 项测试、标准浏览器策略 3 项 Go 测试，以及 Compose YAML／seccomp JSON 解析。另从固定 MCP 基线应用本仓库补丁，使用 Go 1.24.13 在 Windows 上成功交叉编译出 Linux amd64 的 ELF64 文件；检查了文件架构，尚未在 Linux 执行。Dockerfile 使用的 Go 1.26.10 镜像构建仍待实际验证，不能把该交叉编译结果记成 Docker 验收通过。

## 组成与资源

`deploy/travel/compose.yaml` 启动三个服务。Caddy 接受 80／443 并申请 HTTPS 证书，只转发 `/api/native/*`；API 使用独立手机访问码、Cookie 和 CSRF。MCP 与 API 共用私有网络命名空间，MCP 只监听其内部 `127.0.0.1:18062`；MCP、API 端口都不映射到服务器公网。手机不会直接连接小红书 MCP。

个人验证建议选择 **x86_64 Linux、至少 2 vCPU／4 GB 内存、约 40 GB 磁盘**。这是容器配置的起点，尚无云主机压测结论；当前配额为 API 768 MB、MCP 2 GB、Caddy 128 MB，还要留系统和 Docker 余量。不要选 Windows 或 ARM 主机直接套用本文件：MCP 构建目标固定 linux/amd64。网络需能实际访问小红书、DeepSeek、高德和镜像／源码仓库。

主机要支持非 root Chromium 的用户命名空间沙箱。Compose 使用来源可追溯的默认拒绝 seccomp 配置来允许它需要的命名空间系统调用，不启用 `privileged`、`SYS_ADMIN` 或 `--no-sandbox`。Ubuntu 24.04 等主机还可能有 AppArmor 用户命名空间限制；购买／部署验收时必须实测。如果沙箱无法启动，先停止并检查主机兼容性，不自动修改内核安全设置或关闭沙箱。

## 准备与配置

先有自己管理的域名，把旅行子域名的 A 记录指向服务器。没有 IPv6 时不要配置错误的 AAAA。服务器安全组只开放管理所需的 SSH 与 Caddy 的 TCP 80／443，不开放 8794／18062。Docker Engine 与 Compose v2 按 [Docker 官方安装说明](https://docs.docker.com/engine/install/)准备。

将当前源码复制或克隆到 Linux 主机。下面都从仓库根目录执行，域名和邮箱换成自己的。需要主机已有 Node 22+ 来运行一次配置生成器；也可在本机用 Node 生成后，通过已认证的 SSH／SCP 传到服务器。

```bash
node deploy/travel/setup.mjs --domain travel.your-domain.com --email you@your-domain.com
```

生成器只写 `deploy/travel/private/`，不会打印访问码；目录由本目录 `.gitignore` 忽略，根 Docker 构建上下文也排除它。API 与 MCP 的访问码分别随机生成，互不通用。再次运行会拒绝覆盖既有配置，避免无意断开已连接手机。

在服务器私下编辑 `deploy/travel/private/.env.local`，填写 `DEEPSEEK_API_KEY`、可选 `DEEPSEEK_MODEL` 与 `AMAP_WEB_SERVICE_KEY`。这些文件通过只读 secrets 挂载进入容器，不写入镜像、APK 或 BuildConfig。`private/native.json` 是手机访问码；`private/mcp.json` 仅供内部服务使用，不能填到 App 中。

容器统一使用 UID／GID 1000 读取私有配置。Linux 上应以 root 或 UID 1000 运行生成器；root 执行时，生成器仅将本次私有目录与文件改为 1000:1000、目录 0700／文件 0600。如果以其他 UID 生成、从 Windows 复制，部署前用 `chown -R 1000:1000 deploy/travel/private` 和对应权限调整这一个明确目录，勿扩大到其他路径。使用自定义容器 UID 时需同步修改镜像、Compose 与挂载文件归属。

## 检查和启动

```bash
node deploy/travel/validate.mjs
cd deploy/travel
docker compose --env-file private/compose.env config --quiet
docker compose --env-file private/compose.env build --pull
docker compose --env-file private/compose.env up -d
docker compose --env-file private/compose.env ps
```

`validate.mjs` 会检查私有配置以及 Compose 语法，不输出配置值。仅做源码检查可用 `node deploy/travel/validate.mjs --static`，其成功不等于容器已构建或运行。API 健康检查发送未授权请求并要求 401，不触发模型调用或登录检查；MCP 健康检查也不启动真实检索。

在正式扫码之前验证 Chromium 沙箱可运行：

```bash
docker compose --env-file private/compose.env exec -T mcp timeout 30s chromium --headless --dump-dom about:blank
```

应看到空白页 HTML 且命令成功退出。出现 `No usable sandbox` 或超时应先排查；不要加入关闭沙箱的参数。Caddy 的域名自动 HTTPS 需要 DNS 和 80／443 可达，见 [Caddy HTTPS 说明](https://caddyserver.com/docs/automatic-https)。

## 首次小红书登录

首次部署在云端建立这套 MCP 的独立登录状态，不迁移旧电脑浏览器 Cookie。管理员通过 SSH，在服务器源码的 `deploy/travel` 目录执行：

```bash
docker compose --env-file private/compose.env exec -T api node deploy/travel/admin-login.mjs qr
docker compose --env-file private/compose.env cp api:/var/lib/myplan/login-qr.png private/login-qr.png
```

若工具提示实际格式为 `.jpg`，复制对应文件名；若已经登录则不会生成新图。通过已认证的 SCP 将二维码图片传到自己的电脑，打开后用本人手机小红书扫码并确认。二维码只短期有效，过期重新生成；不要发布图片、Cookie 或访问码。整个过程没有对外开放 MCP 管理接口。

确认状态：

```bash
docker compose --env-file private/compose.env exec -T api node deploy/travel/admin-login.mjs status
```

结果应为 `{"loggedIn":true}`。MCP 的 cookie 文件保存在命名卷 `mcp_state`，服务重启后保留；平台仍可能使登录失效或要求验证。**不承诺永不过期，也不承诺小红书可从同一手机相册识别登录二维码。** 正常出门使用只需要手机；首次部署或重新授权可能需要另一块屏幕来展示二维码。遇到平台要求的人工验证由本人处理。

## 手机接入与验收

在支持云端地址的测试 APK 中，进入「旅行行程 → 找旅行灵感 → 服务连接设置」，填写 `https://travel.你的域名` 和 `private/native.json` 中的手机访问码，只配置一次即可；不再填写电脑的 localhost 地址，也不需要 USB 调试转发。供应商 Key 留在服务端。

关闭个人电脑、关闭手机 Wi-Fi，使用移动网络依次检查：连接状态 → 检索城市 → 查看配图和原帖 → 选择／必选 → 设置日期 → AI 排程 → 确认保存 → 重启 App 后查看保存行程。另测网络中断、资料过期、无图、登录失效、额度不足；不要以固定样例替代失败响应。实际服务器域名、证书、访问码和跨网测试未完成前，不能宣称已上线或已脱离电脑实测通过。

## 配额、持久化与维护

服务器默认限制每小时新建研究 6 次、排程 12 次；相同运行中请求复用，不重复扣开始次数。计数按滚动小时记录到 `api_state` 卷的 `/var/lib/myplan/usage-budget.json`，重启保留；文件损坏或无法写入时拒绝新的付费任务。配额限制任务开始次数，不等同于模型 token、人民币或平台实际账单上限，仍需查看供应商控制台的用量。

本套配置只运行 **一个 API 实例**；持久计数文件不是分布式锁，不要横向扩容或让多个进程同时写它。服务端任务／会话仍是约 4 小时的内存数据，API 重启会丢失运行任务；手机已保存的 Room 行程不依赖这个缓存。

更新时保留 `private/` 和命名卷，重新构建并重建 API／MCP 后再验证登录与移动网络。日常停止用 `docker compose --env-file private/compose.env down`，不加 `-v`；带 `-v` 会删除登录、预算与证书数据。配置和命名卷备份属于私有资料，保存在自己的加密备份中，不上传 GitHub。

浏览器模块、MCP 固定基线、版本化镜像和 seccomp 来源见 [部署来源说明](../deploy/travel/mcp/PROVENANCE.md)。实际主机上的镜像 digest、Chromium 版本和沙箱测试结果应记入部署验收记录，之后定期构建更新；本轮还没有这些云端实测记录。

## 本轮验证记录

Node 研究、实时接口、前端模型与部署脚本合计 224 项自动测试通过；另有 3 项浏览器策略测试通过，固定 MCP 源码成功交叉编译为 Linux amd64 ELF64。Android 全套 266 项测试在独立重跑后通过，Lint 为 0 个错误；首次与 Lint 并发运行时，既有计划助手用例出现一次 8 秒等待超时，独立重跑未复现。交付目录保留最终日志及交叉编译证据。没有把交叉编译称为容器运行或手机实测。
