# 旅行 AI 云端部署（个人自用）

目标是手机通过移动网络或任意 Wi-Fi 完成「检索攻略 → 自己选择 → AI 排程」，个人电脑可以关机。手机保存自己的行程，小红书检索与模型调用在持续运行的 Linux 主机上完成。

**云端已验证小红书登录、重启持久化、真实攻略和图片读取。** API／MCP 镜像、Chromium 沙箱及搜索回归也已通过。AI／高德密钥尚未传入云端，备案、HTTPS 和手机跨网验收仍待完成。

Android 云端接入源码版本为 **1.0.9（10）**，新安装的默认服务地址留空。部署完成后，在 App 填入自己的 HTTPS 地址和个人访问码。开发调试支持本机服务地址。

## 购买参考

以下为 2026-11-04 核对的官网采购参考，实际结算价取决于账号资格与在售套餐。域名、模型调用和超额流量另计。

| 路线 | 配置参考 | 官方价格信息 |
| --- | --- | --- |
| 大陆年付活动 | Linux 4 核／4 GB | 活动页列示符合条件的新用户首年 109 元，资格、库存和续费价需在购买页核对。[活动说明](https://cloud.tencent.com/act/pro/lhsale) |
| 香港按月验证 | Linux 2 核／4 GB／70 GB SSD | 2025-09-15 调价公告列示刊例 90 元／月，实际在售套餐与结算价需重新核对。[价格公告](https://cloud.tencent.com.cn/announce/detail/2131) |

服务商对中国大陆源站的域名接入有备案要求。选择源站时，应同时核对备案周期、实际检索可用性、续费和流量费用。[腾讯云接入规则](https://cloud.tencent.com.cn/document/product/243/18908)

前序本地静态检查、策略测试和 Windows Go 1.24.13 交叉编译作为历史记录保留。2026-11-04 至 11-05 在 Debian 13.2 amd64 云主机完成了以下验证。

| 阶段 | 已验证结果 |
| --- | --- |
| 基础环境 | Docker 29.9.3、Compose 5.7.0 及版本化 Node／Go／Caddy 基础镜像已准备 |
| API 镜像与隔离检查 | 实际构建成功。无网络、只读根文件系统、零 capability 和 `no-new-privileges` 下，未授权接口 401、授权 session 200、缺 CSRF 的 POST 403、部署模式根路径 404 均符合预期。会话 Cookie 带 Secure／HttpOnly，`provider_calls=0` |
| MCP 构建 | 依赖下载、`go mod verify`、Go 1.26.10 断网编译和完整镜像构建均成功，运行用户为 1000:1000 |
| Chromium 隔离运行 | Chromium 156.0.8078.79 在只读根文件系统、可写 HOME／配置／tmp tmpfs、无网络、零 capability 和明确修改的 seccomp 下，保留沙箱并成功输出空白页 HTML |
| 私有连接器 | `GET /health` 返回 200，未授权登录状态请求返回 401。当前容器 UID 1000、`privileged=false`、`cap_drop ALL`、`ports={}` |
| 登录持久化 | 登录状态依次通过登录、重启、更新后的检查，均为 `loggedIn:true`。登录文件归属 UID 1000、权限 0600 |
| 搜索回归 | Windows 完整重跑四项及四个子例通过，耗时 35.745 秒。最终 Linux 在同一 runtime 上四项及四个子例全部通过，未跳过 |
| 真实攻略与配图 | 正式客户端、`normalizeNote` 和 media store 搜索得到 20 条结果，读取 1 篇详情。标题长度 21、正文长度 619，图片目录及地址各 12 项。实际取回 1 张 WebP，576460 字节，`imageRead=true` |

当前持续运行的是私有 MCP，真实读取验证通过内部连接完成。模型／高德接入、备案、HTTPS 证书和手机移动网络验收列为后续步骤。服务器身份信息和授权材料保存在私有交接记录。

## 组成与资源

`deploy/travel/compose.yaml` 定义 Caddy、API 和 MCP 三个服务，完整公网业务栈待配置。部署后 Caddy 接受 80／443、申请 HTTPS 证书并转发 `/api/native/*`。API 使用独立手机访问码、会话 Cookie 和 CSRF。MCP 与 API 共用私有网络命名空间，MCP 监听内部 `127.0.0.1:18062`，手机通过公开 API 访问旅行功能。

构建目标固定为 **linux/amd64**，个人验证的配置起点为至少 2 vCPU／4 GB 内存、约 40 GB 磁盘。当前容器限额为 API 768 MB、MCP 2 GB、Caddy 128 MB，还需留出系统和 Docker 余量。容量评估仍需结合实际负载。网络需能访问小红书、DeepSeek、高德和镜像／源码仓库。

主机需要支持非 root Chromium 用户命名空间沙箱。seccomp 基于 Microsoft Playwright 的默认拒绝配置，为零 capability 场景增加 `chroot` syscall 许可，让 Chromium 执行自己的 `DropFileSystemAccess` 隔离步骤。运行时采用 `cap_drop ALL`、`no-new-privileges`、默认拒绝和 Chromium 沙箱。更换主机或镜像后应复验。[策略来源说明](../deploy/travel/mcp/PROVENANCE.md)

4GB 主机采用串行镜像构建，MCP 阶段另有限制 Go 编译并行度。运行容器的 `mem_limit` 与构建资源限制分别配置，构建前后应检查内存和磁盘余量。

## 准备与配置

先有自己管理的域名，把旅行子域名的 A 记录指向服务器。没有 IPv6 时不要配置错误的 AAAA。服务器安全组只开放管理所需的 SSH 与 Caddy 的 TCP 80／443，不开放 8794／18062。Docker Engine 与 Compose v2 按 [Docker 官方安装说明](https://docs.docker.com/engine/install/)准备。

将当前源码复制或克隆到 Linux 主机。以下命令从仓库根目录执行，域名和邮箱换成自己的。配置生成器需要 Node 22+，也可在本机生成后通过已认证的 SSH／SCP 传到服务器。

```bash
node deploy/travel/setup.mjs --domain travel.your-domain.com --email you@your-domain.com
```

生成器在 `deploy/travel/private/` 创建独立的 API 与 MCP 随机访问码。该目录由 Git 和 Docker 构建上下文忽略。已有配置需沿用原目录，生成器会拒绝覆盖。

在服务器私下编辑 `deploy/travel/private/.env.local`，填写 `DEEPSEEK_API_KEY`、可选 `DEEPSEEK_MODEL` 与 `AMAP_WEB_SERVICE_KEY`。这些文件通过只读 secrets 挂载进入容器。手机使用 `private/native.json` 中的专用访问码，内部服务使用 `private/mcp.json`。

容器使用 UID／GID 1000 读取私有配置。Linux 上以 root 或 UID 1000 运行生成器，root 执行时会设置本次私有目录为 1000:1000、目录 0700／文件 0600。其他 UID 生成或从 Windows 复制的文件，需在部署前调整这个明确目录的归属和权限。自定义容器 UID 时，同步修改镜像、Compose 与挂载文件归属。

## 检查和启动

GitHub 受限时，使用小源码包方案的完整命令，合并 `-f compose.yaml -f compose.cached-source.yaml`，并串行构建 API 与 MCP。后续启动和维护沿用这两个 `-f` 参数。[小源码包构建方案](../deploy/travel/CACHED-SOURCE.md)

```bash
node deploy/travel/validate.mjs
cd deploy/travel
docker compose --env-file private/compose.env config --quiet
docker compose --env-file private/compose.env build --pull
docker compose --env-file private/compose.env up -d
docker compose --env-file private/compose.env ps
```

`validate.mjs` 检查私有配置与 Compose 语法。源码静态检查命令为 `node deploy/travel/validate.mjs --static`。API 健康检查要求未授权请求返回 401，MCP 健康检查检查内部服务状态，真实检索另行验收。

正式扫码前先验证 Chromium 沙箱运行。

```bash
docker compose --env-file private/compose.env exec -T mcp timeout 30s chromium --headless --dump-dom about:blank
```

验收结果应为命令成功退出并输出空白页 HTML。出现 `No usable sandbox` 或超时时，先排查主机与容器策略。Caddy 自动 HTTPS 需要正确 DNS 和可达的 80／443。[Caddy HTTPS 说明](https://caddyserver.com/docs/automatic-https)

## 首次小红书登录

首次部署通过云端 MCP 建立独立登录状态。管理员经已认证的 SSH，在服务器源码的 `deploy/travel` 目录执行以下命令。

```bash
docker compose --env-file private/compose.env exec -T api node deploy/travel/admin-login.mjs qr
docker compose --env-file private/compose.env cp api:/var/lib/myplan/login-qr.png private/login-qr.png
```

按 helper 提示的 `.png` 或 `.jpg` 文件名，通过已认证的加密通道取回短期图片，用本人小红书扫码并确认。已登录分支只报告状态。连接器后台等待窗口约四分钟，二维码实际有效期由平台决定，应在生成后尽快展示。授权图片与登录资料保存在私有位置。

完成扫码后查询登录状态。

```bash
docker compose --env-file private/compose.env exec -T api node deploy/travel/admin-login.mjs status
```

结果应为 `{"loggedIn":true}`，随后重启 MCP 再查一次。登录资料保存在 `mcp_state` 命名卷，平台仍可能要求重新授权。首次部署或重新授权可用另一块屏幕展示二维码，由本人完成平台验证。

## 手机接入与验收

在测试 APK 的「旅行行程 → 找旅行灵感 → 服务连接设置」填写自己的 HTTPS 地址和 `private/native.json` 中的手机访问码。供应商 Key 由服务端保存。

关闭个人电脑与手机 Wi-Fi，使用移动网络依次验证连接状态、城市检索、配图与原帖、地点选择、日期设置、AI 排程、确认保存以及 App 重启后的行程读取。再检查断网、资料过期、无图、登录失效和额度不足时的真实错误处理。这一整套手机跨网验收仍待完成。

## 配额、持久化与维护

服务器默认每小时最多新建研究 6 次、排程 12 次，相同运行中请求复用。滚动小时计数保存在 `api_state` 卷的 `/var/lib/myplan/usage-budget.json`，重启后继续累计。文件损坏或无法写入时会拒绝新的付费任务。模型 token、费用和平台账单需另查供应商控制台。

本配置按 **一个 API 实例** 设计，持久计数文件由该实例独占写入。服务端任务与会话约保留 4 小时，API 重启后需重新发起运行任务。手机已保存的 Room 行程独立持久化。

更新时沿用 `private/` 与命名卷，重建 API／MCP 后复验登录和移动网络。日常停止命令为 `docker compose --env-file private/compose.env down`。配置、登录、预算和证书的卷备份归入私有加密备份。

本次构建在已校验的基础归档上应用公开源码覆盖，并使用已记录的软件源、CA 与 seccomp 适配。常规、Windows、小包缓存和 vendor 离线入口均复制同一 `tools/windows/mcp-compat/files/`。后续更新应记录镜像、策略和有效源码摘要。[部署来源说明](../deploy/travel/mcp/PROVENANCE.md)

## 本轮验证记录

GitHub 或模块代理受限时，可由可信电脑准备并校验含 vendor 的源码包，编译阶段禁用网络。基础镜像和 Debian 签名软件包仍需可访问。[离线源码构建流程](../deploy/travel/OFFLINE.md)

云机使用已核验的小源码包与腾讯云 Go 代理下载依赖，完成模块校验和 vendor 准备后以 `network=none` 编译。基础归档 SHA-256 为 `96cfedb5c3888e9952944bb3b9fb08330b91ec53c0dac6bea3b72037121e9563`，搜索修复由公开覆盖文件提供。原 Chromium 软件包安装曾停滞，重试使用腾讯云官方 Debian HTTPS 源及同属 Bookworm 的官方 Go 阶段 CA 文件，保留 suite、`Signed-By` 和包校验后构建通过。

首次真实搜索在约 60 秒后报 `context deadline exceeded`。原整页稳定等待会占满搜索预算，现改为独立 5 秒子上下文。默认无筛选搜索若耗尽该子等待，会继续在父级 60 秒预算内等待首批非空 feeds。稳定页面的合法空结果正常返回，筛选继续使用原刷新逻辑。诊断仅记录阶段耗时和受限页面状态。

Windows 首次完整回归在浏览器退出清理阶段超时，测试工厂改用受控的独立 launcher 和有界清理后，四项及四个子例在 35.745 秒内全部通过。2026-11-05 18:50 至 18:51（北京时间），同一云端 runtime 上的最终 Linux 回归全部通过，随后正式 API 客户端完成真实攻略和 WebP 读取。

前序 Node 研究、实时接口、前端模型与部署脚本合计 224 项通过，另有 3 项浏览器策略测试通过。Android 全套 266 项在独立重跑后通过，Lint 为 0 个错误。首次并发 Lint／单测出现过一次等待超时，独立重跑未复现。云端验证范围另列于上表，AI／高德配置、备案、HTTPS 和手机跨网验收继续推进。
