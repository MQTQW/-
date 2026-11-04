# 小体积源码传输与腾讯云 Go 代理

当目标主机无法访问 GitHub，且完整 vendor 源码包不便传输时，可使用 `Mcp.cached-source.Dockerfile`。`source-cache/mcp-small.tar.gz` 只包含固定基线和已审查兼容补丁的公开 MCP／浏览器模块源码，**不含 vendor**。因此该方式仍需要通过网络下载 Go 模块，不能称为完全离线构建。

腾讯云 [Go SDK 官方文档](https://cloud.tencent.com/document/sdk/Go)推荐 `https://mirrors.tencent.com/go/`；[软件源官方说明](https://cloud.tencent.com.cn/document/product/213/8623)确认 `mirrors.cloud.tencent.com` 是同一软件源的公网／云内网统一域名。本 Dockerfile 只指定该 HTTPS 代理，不设置 `direct` 或其他备用代理。`GOSUMDB=sum.golang.org` 和 `go.sum` 校验保留；代理或校验失败时停止，不关闭 TLS 或校验。

本轮在可信电脑和目标云机核验以下接口均为 HTTPS 200：

```text
https://mirrors.cloud.tencent.com/go/github.com/go-rod/rod/@v/v0.116.2.mod
https://mirrors.cloud.tencent.com/go/github.com/modelcontextprotocol/go-sdk/@v/v1.4.0.mod
https://mirrors.cloud.tencent.com/go/sumdb/sum.golang.org/supported
```

另在可信电脑用全新的 Go 模块缓存从该代理成功下载 `github.com/go-rod/rod@v0.116.2` 的模块包，保留 `GOSUMDB=sum.golang.org`，其模块与 go.mod 摘要均与固定源码的 `go.sum` 一致。目标云机随后也完成了全部依赖下载、校验与编译，实际运行验证范围见下文。

从可信交付记录取得小包、对应 SHA-256 文件和 `PROVENANCE.json`，放入服务器源码 `deploy/travel/source-cache/`。该目录由 Git 忽略，不能只依赖 `git archive HEAD` 传输它。小包内仅有公开源码、模块清单与许可证；不能放入 Cookie、浏览器目录、身份资料、私有配置或可执行程序。

```bash
(cd deploy/travel/source-cache && sha256sum -c mcp-small.sha256)
docker build --pull --progress=plain -t myplan-travel-mcp:preflight -f deploy/travel/Mcp.cached-source.Dockerfile .
```

上面的命令仅单独构建测试镜像。实际部署时必须同时指定 `compose.cached-source.yaml`，否则主 Compose 仍会使用需要访问 GitHub 的常规 MCP Dockerfile。确认小源码包及 `private/` 配置已按部署说明准备好后，从仓库根目录执行：

```bash
cd deploy/travel
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml build api
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml build mcp
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml up -d --no-build
docker compose --env-file private/compose.env -f compose.yaml -f compose.cached-source.yaml ps
```

API 和 MCP 串行构建，避免在 4GB 主机同时编译。该覆盖文件仅改变 MCP 的 Dockerfile 路径，其他网络、挂载、能力限制和沙箱配置均继承主 Compose；后续部署命令也应保留这两个 `-f` 参数。

构建先验证源码包摘要，再通过腾讯云代理下载依赖，执行 `go mod verify` 并生成 vendor。最后的编译步骤禁用网络，使用 Go 1.26.10／amd64、`-p 1` 与 `GOMAXPROCS=2`；运行镜像、非 root 浏览器、沙箱和许可证保留规则与正常配置一致。基础镜像和 Debian 签名软件包仍需可访问。

仅在本机检查源码不能替代目标主机上的代理下载、容器编译和 Chromium 沙箱验收。迁移到其他主机后应重新验证；若完整模块代理不可用，应回到 [vendor 离线包](OFFLINE.md)方案，而非关闭校验。

## 云机运行层的软件源

目标 Debian 13.2／amd64 主机已实际完成腾讯云代理依赖下载、`go mod verify`、vendor 生成、断网 Go 编译及 MCP 完整镜像构建。运行层原先在 `deb.debian.org` 的软件包索引下载处停滞，改用下面的官方镜像后，apt 安装和镜像构建均已通过。

云机还确认固定 `node:24.22.2-bookworm-slim` 镜像未包含系统 CA bundle。运行层现先从已完成的官方 `golang:1.26.10-bookworm` 构建阶段复制 `/etc/ssl/certs/ca-certificates.crt`，让首次 HTTPS apt 连接有可信根证书；随后安装的 Debian `ca-certificates` 包负责标准更新。没有关闭证书验证或加入自签信任根。

仅将 `/etc/apt/sources.list.d/debian.sources` 中的主仓库与安全仓库 URI 替换为腾讯云官方 HTTPS 镜像：

```text
https://mirrors.cloud.tencent.com/debian
https://mirrors.cloud.tencent.com/debian-security
```

保持容器的 `bookworm`、`bookworm-updates`、`bookworm-security`，不跟随宿主机改为 Trixie；原有 `Signed-By`、Debian archive keyring 和元数据有效期验证均保留。更新与安装各设置 30 秒 HTTPS 超时、2 次重试，避免无限等待。两处镜像在本机和目标云机均已返回 HTTPS 200，云机实际 apt 安装和完整构建也已通过。

来源：[腾讯云 Debian 镜像帮助](https://mirrors.cloud.tencent.com/help/debian.html)、[安全镜像帮助](https://mirrors.cloud.tencent.com/help/debian-security.html)、[官方统一镜像域名说明](https://cloud.tencent.com.cn/document/product/213/8623)。

## 本轮云机验证范围

2026-11-04 20:44（北京时间），云机上的 Chromium `156.0.8078.79` 已通过 `about:blank` 启动检查，命令退出码为 0。测试使用非 root UID 1000、禁用容器网络、只读根文件系统、归属 UID 1000 的 HOME／配置／临时目录 tmpfs、`cap_drop: ALL`、`no-new-privileges` 和本仓库自定义 seccomp；未关闭浏览器沙箱。seccomp 的本地 `chroot` 调整及边界记录见 [来源说明](mcp/PROVENANCE.md)。本轮构建的 MCP 镜像 ID 为 `sha256:1a4502f11c78e013676f9daa9973a0cff388d04eee8b55c3feb32bae1b6e61dc`。

MCP 服务已在云机回环地址启动，健康检查返回 200，未授权登录状态请求返回 401，未发布 MCP 端口。这些结果仅验证镜像构建、浏览器沙箱启动和本地服务访问控制；尚未证明小红书账号登录、真实攻略检索、公网 HTTPS 或手机蜂窝网络端到端流程通过。
