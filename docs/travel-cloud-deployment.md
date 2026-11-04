# 旅行 AI 云端部署（个人自用）

目标是手机通过移动网络或任意 Wi-Fi 完成「检索攻略 → 自己选择 → AI 排程」，个人电脑可以关机。手机仍保存自己的行程；小红书检索与模型调用在持续运行的 Linux 主机上完成。

**当前状态：实际云主机上的 API／MCP 镜像构建、隔离 API 检查、Chromium 沙箱及 MCP 基础运行验收已通过，尚未交付公网服务。** 域名、供应商配置、云端小红书授权、备案、HTTPS 和手机跨网访问仍待完成；本轮隔离检查没有调用真实供应商。受限网络下可使用下文的源码准备方案，保留 TLS、软件包及模块完整性校验。

Android 云端接入源码版本为 **1.0.9（10）**。新安装不预填不存在的服务地址；部署完成后在 App 填入自己的 HTTPS 地址和个人访问码即可，不需要 USB 转发。已配置的本机开发地址继续保留为调试选项。

## 购买参考

以下为 2026-11-04 核对的官网采购参考，不保证其他账号可获得相同结算价；域名、模型调用和超额流量另计。

| 路线 | 配置参考 | 官方价格信息 |
| --- | --- | --- |
| 大陆年付活动 | Linux 4 核／4 GB | 腾讯云活动页列示符合条件的新用户首年 109 元；新用户资格、库存和续费价需在购买页核对。[活动说明](https://cloud.tencent.com/act/pro/lhsale) |
| 香港按月验证 | Linux 2 核／4 GB／70 GB SSD | 腾讯云 2025-09-15 调价公告列示刊例 90 元／月，可作为比较参考；实际在售套餐与结算价需重新核对。[价格公告](https://cloud.tencent.com.cn/announce/detail/2131) |

服务商对中国大陆源站的域名接入有备案要求，香港源站没有该项接入要求，见[腾讯云接入规则](https://cloud.tencent.com.cn/document/product/243/18908)。这影响首次上线时间；不能只比较促销首年价格。个人验证可先比较按月方案，实际云端检索、沙箱和手机移动网络验收成功后再决定长期购买。

前序本地静态检查、策略测试和 Windows Go 1.24.13 交叉编译作为历史证据保留。本轮另在真实 Debian 13.2 amd64 云主机上完成了以下阶段；不再仅以交叉编译代替 Docker 验证：

| 阶段 | 2026-11-04 已验证结果 |
| --- | --- |
| 基础环境 | Docker 29.9.3、Compose 5.7.0 及版本化 Node／Go／Caddy 基础镜像已准备 |
| API 镜像与隔离检查 | 实际构建成功；无网络、根文件系统只读、零 capability、`no-new-privileges` 的测试容器中，未授权接口 401、授权 session 200、POST 缺 CSRF 403、部署模式根路径 404 均符合预期；Cookie 带 Secure／HttpOnly，`provider_calls=0` |
| MCP 构建 | 依赖下载与 `go mod verify` 成功；Go 1.26.10 的 Linux 离线编译阶段成功，随后完整 MCP 镜像构建成功，最终用户为 1000:1000 |
| Chromium 隔离运行 | Chromium 156.0.8078.79 在只读根文件系统、可写 HOME／配置／tmp tmpfs、无网络、零 capability 和明确修改的 seccomp 下，保留沙箱并成功输出空白页 HTML |
| MCP 基础运行 | Go 服务实际启动，`GET /health` 返回 200，未授权 `GET /api/v1/login/status` 返回 401；不发布端口，临时验收容器回收 |

这些隔离结果不表示业务栈已持续运行、小红书已经授权、真实攻略／模型调用已经成功，也不证明 HTTPS 证书链或手机移动网络已经可用。实际服务器标识、镜像标识和完整日志保留在本机部署交接记录，公开说明不包含实例 ID 或地址。

## 组成与资源

`deploy/travel/compose.yaml` 定义三个业务服务，正式业务栈尚未启动。按此方案部署后，Caddy 接受 80／443 并申请 HTTPS 证书，只转发 `/api/native/*`；API 使用独立手机访问码、Cookie 和 CSRF。MCP 与 API 共用私有网络命名空间，MCP 只监听其内部 `127.0.0.1:18062`；MCP、API 端口都不映射到服务器公网。手机不会直接连接小红书 MCP。

个人验证建议选择 **x86_64 Linux、至少 2 vCPU／4 GB 内存、约 40 GB 磁盘**。这是容器配置的起点，尚无云主机压测结论；当前配额为 API 768 MB、MCP 2 GB、Caddy 128 MB，还要留系统和 Docker 余量。不要选 Windows 或 ARM 主机直接套用本文件：MCP 构建目标固定 linux/amd64。网络需能实际访问小红书、DeepSeek、高德和镜像／源码仓库。

主机要支持非 root Chromium 的用户命名空间沙箱。当前 seccomp 基于 Microsoft Playwright 的默认拒绝配置，**不是上游原样复制**：为零 capability 场景明确增加 `chroot` syscall 的 allowlist 规则，使 Chromium 自己的 `DropFileSystemAccess` 隔离步骤可执行。它扩展了一条外层 syscall 许可，不授予宿主 capability，`cap_drop ALL`、`no-new-privileges`、默认拒绝和 Chromium 沙箱均保留；不启用 `privileged`、`SYS_ADMIN` 或 `--no-sandbox`。具体差异与摘要见[策略来源说明](../deploy/travel/mcp/PROVENANCE.md)。本轮已在上述隔离条件下通过空白页测试，其他主机／镜像变更后仍需复验；不自动关闭主机安全限制来通过检查。

首次在 4GB 主机上构建时，两个镜像应串行构建；MCP 构建阶段已限制 Go 编译并行度。运行容器的 `mem_limit` 不限制镜像构建阶段，构建前后仍需检查内存和磁盘余量。

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

如果主机不能访问 GitHub，本次云机使用的[小源码包构建方案](../deploy/travel/CACHED-SOURCE.md)包含专用 Compose 覆盖文件。该路线的构建、启动和维护命令都需要同时指定 `-f compose.yaml -f compose.cached-source.yaml`，并串行构建 API 与 MCP；请使用该方案中的完整命令，避免又回到常规 GitHub 构建路径。

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

浏览器模块、MCP 固定基线、版本化镜像和 seccomp 来源见 [部署来源说明](../deploy/travel/mcp/PROVENANCE.md)。本轮已有实际镜像、Chromium 版本与隔离运行记录；后续更新仍应记录对应镜像及策略摘要。本次云端实际构建从已核对的源码归档开始，另应用了 Dockerfile 网络／CA 兼容补丁和 seccomp 补丁，因此不能描述为完全未修改的基线提交。

## 本轮验证记录

若目标主机不能访问 GitHub／Go 模块代理，可使用[含 vendor 的离线源码构建流程](../deploy/travel/OFFLINE.md)。源码包通过可信电脑准备与校验后上传，编译阶段禁用网络；基础镜像和 Debian 软件包仍需可访问，不使用未知加速站或关闭 TLS／模块校验。

本次云主机实际使用了已核验的小源码包与腾讯云 Go 代理下载依赖，完成模块校验和 vendor 准备后再以 `network=none` 编译。原 Chromium 软件包安装阶段停滞，核对原构建进程后取消；重试采用腾讯云官方 Debian HTTPS 软件源，并从同属 Bookworm 的官方 Go 阶段复制 CA 信任文件到 Node runtime，保留 suite、`Signed-By` 和包校验，随后镜像构建与隔离运行通过。构建文件和策略的具体补丁已单独记录，没有以关闭证书或浏览器沙箱的方式处理失败。

前序本地验证记录为 Node 研究、实时接口、前端模型与部署脚本合计 224 项通过，另有 3 项浏览器策略测试通过；Android 全套 266 项在独立重跑后通过，Lint 为 0 个错误。首次并发 Lint／单测时，既有助手用例出现一次等待超时，独立重跑未复现。新增云端构建、API 与 Chromium／MCP 隔离运行结果按上表独立记录，不与历史交叉编译混为一项，也没有称为真实平台或手机跨网验收通过。
