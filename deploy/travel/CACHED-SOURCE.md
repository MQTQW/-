# 小体积源码传输与腾讯云 Go 代理

当目标主机无法访问 GitHub，且完整 vendor 源码包不便传输时，可使用 `Mcp.cached-source.Dockerfile`。`source-cache/mcp-small.tar.gz` 包含固定基线与已审查兼容补丁的公开 MCP／浏览器模块源码，依赖通过腾讯云 Go 代理下载。基础归档 SHA-256 为 `96cfedb5c3888e9952944bb3b9fb08330b91ec53c0dac6bea3b72037121e9563`，后续搜索适配由公开源码覆盖提供。

腾讯云 Go SDK 文档推荐 `https://mirrors.tencent.com/go/`，软件源说明确认 `mirrors.cloud.tencent.com` 是同一软件源的公网／云内网统一域名。本 Dockerfile 使用该 HTTPS 代理，保留 `GOSUMDB=sum.golang.org`、`go.sum` 和 TLS 校验，代理或校验失败时终止构建。[Go SDK 文档](https://cloud.tencent.com/document/sdk/Go)、[统一域名说明](https://cloud.tencent.com.cn/document/product/213/8623)

可信电脑和目标云机已核验以下接口均返回 HTTPS 200。

```text
https://mirrors.cloud.tencent.com/go/github.com/go-rod/rod/@v/v0.116.2.mod
https://mirrors.cloud.tencent.com/go/github.com/modelcontextprotocol/go-sdk/@v/v1.4.0.mod
https://mirrors.cloud.tencent.com/go/sumdb/sum.golang.org/supported
```

另在可信电脑用全新的 Go 模块缓存从该代理成功下载 `github.com/go-rod/rod@v0.116.2` 的模块包，保留 `GOSUMDB=sum.golang.org`，其模块与 go.mod 摘要均与固定源码的 `go.sum` 一致。目标云机随后也完成了全部依赖下载、校验与编译，实际运行验证范围见下文。

从可信交付记录取得小包、对应 SHA-256 文件和 `PROVENANCE.json`，另行传入服务器源码的 `deploy/travel/source-cache/`。该目录由 Git 忽略。小包仅包含公开源码、模块清单与许可证，授权状态和私有配置独立保存。

```bash
(cd deploy/travel/source-cache && sha256sum -c mcp-small.sha256)
docker build --pull --progress=plain -t myplan-travel-mcp:preflight -f deploy/travel/Mcp.cached-source.Dockerfile .
```

上面的命令用于构建验收镜像。业务部署使用主 Compose 与 `compose.cached-source.yaml` 的合并配置。准备好小源码包及 `private/` 配置后，从仓库根目录执行以下命令。

```bash
cd deploy/travel
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml build api
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml build mcp
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml up -d --no-build
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml ps
```

API 和 MCP 采用串行构建，合并配置继承主 Compose 的网络、挂载、能力限制和沙箱设置。后续部署与维护命令均带上这两个 `-f` 参数。

已有预检用量卷 `myplan-travel_api_state` 时，在以上每条 Compose 命令的两个 `-f` 参数后追加 `-f compose.existing-state.yaml`，将该卷作为外部卷接续，保留已有用量记录。该附加配置要求卷已经存在。实际接续步骤见[部署说明](../../docs/travel-cloud-deployment.md)。

构建先验证基础归档摘要并解包，再将 `tools/windows/mcp-compat/files/` 复制到 `/src/mcp/`。该覆盖目录也供常规、Windows 和 vendor 离线构建使用。随后通过腾讯云代理下载依赖，执行 `go mod verify` 并生成 vendor。最终编译禁用网络，使用 Go 1.26.10／amd64、`-p 1` 与 `GOMAXPROCS=2`。非 root 浏览器、沙箱和许可证随镜像一并保留，基础镜像及 Debian 签名软件包需可访问。

迁移到其他主机时，重新验证代理下载、容器编译和 Chromium 沙箱运行。完整模块代理不可用时，可采用含 vendor 的源码包完成依赖准备。[离线构建方案](OFFLINE.md)

## 云机运行层的软件源

目标 Debian 13.2／amd64 主机已实际完成腾讯云代理依赖下载、`go mod verify`、vendor 生成、断网 Go 编译及 MCP 完整镜像构建。运行层原先在 `deb.debian.org` 的软件包索引下载处停滞，改用下面的官方镜像后，apt 安装和镜像构建均已通过。

固定 `node:24.22.2-bookworm-slim` 镜像初始缺少系统 CA bundle。运行层先从官方 `golang:1.26.10-bookworm` 构建阶段复制 `/etc/ssl/certs/ca-certificates.crt`，为首次 HTTPS apt 连接提供可信根证书。随后由 Debian `ca-certificates` 包维护标准信任链。

`/etc/apt/sources.list.d/debian.sources` 的主仓库与安全仓库 URI 使用以下腾讯云官方 HTTPS 镜像。

```text
https://mirrors.cloud.tencent.com/debian
https://mirrors.cloud.tencent.com/debian-security
```

容器使用 `bookworm`、`bookworm-updates`、`bookworm-security`，依照 `Signed-By`、Debian archive keyring 和元数据有效期完成校验。更新与安装各设置 30 秒 HTTPS 超时、2 次重试。两处镜像在本机和目标云机均返回 HTTPS 200，云机 apt 安装与完整构建也已通过。

软件源配置依据腾讯云官方镜像说明。[Debian 镜像帮助](https://mirrors.cloud.tencent.com/help/debian.html)、[安全镜像帮助](https://mirrors.cloud.tencent.com/help/debian-security.html)、[统一镜像域名说明](https://cloud.tencent.com.cn/document/product/213/8623)

## 云机验证记录

2026-11-04 20:44（北京时间），Chromium `156.0.8078.79` 的 `about:blank` 隔离检查退出码为 0。测试使用非 root UID 1000、禁用容器网络、只读根文件系统、可写 HOME／配置／临时目录 tmpfs、`cap_drop ALL`、`no-new-privileges` 和自定义 seccomp，浏览器沙箱正常运行。[策略来源说明](mcp/PROVENANCE.md)

2026-11-05 的首次真实搜索在约 60 秒后出现 `context deadline exceeded`。公开覆盖文件将整页稳定等待限制为独立 5 秒子上下文。默认无筛选搜索耗尽子等待后，在原父级 60 秒预算内等待首批非空 feeds，稳定合法空结果仍正常返回，筛选沿用原刷新逻辑。安全诊断记录阶段耗时、已知 pathname、readyState 与 feeds 数组形状。

Windows 首次完整回归曾卡在浏览器退出清理阶段，测试工厂采用独立标准 launcher 和有界清理后，四项及四个子例在 35.745 秒内全部通过。Linux 最终在同一 runtime 上实际通过四项及四个子例，未跳过。有效源码树与基础归档加公开覆盖后的清单匹配。

已验收 runtime 镜像 ID 为 `sha256:c05deb41e3a613af90b474f08aa0729a3c4a2ac7f8048e494133540dfb9863fe`。私有连接器使用 UID 1000、`privileged=false`、`cap_drop ALL`、`no-new-privileges` 和自定义 seccomp，端口发布为空。登录状态在重启及更新后均通过验证。

正式 API 客户端与 `normalizeNote` 搜索得到 20 条结果，读取 1 篇详情，正文长度 619，图片目录及地址各 12 项。media store 实际取回 1 张 576460 字节的 WebP。2026-11-06 配置云端供应商后，正式原生 HTTP 接口在 84.57 秒内完成 3 篇攻略、12 个候选、4 个地图匹配及 5 个已选内容的两日 AI 排程。备案、HTTPS 和手机跨网验收继续推进。[部署验证范围](../../docs/travel-cloud-deployment.md)
