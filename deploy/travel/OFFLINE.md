# 受限网络主机：MCP 离线源码构建

当目标主机不能访问 GitHub 或 Go 模块代理时，先在可信构建电脑生成包含 `vendor/` 的源码包，再通过已认证的文件传输上传。此方式不需要复制任何账号 Cookie、浏览器目录、供应商密钥或本机凭据。

这不是完全断网的容器构建：版本化 Go／Node 基础镜像，以及 Debian 签名仓库中的 Chromium、字体和证书包仍需可访问。Docker registry mirror 只解决镜像拉取，不能替代 Go／GitHub 源码准备。

## 使用已交付的源码包

把以下文件放在服务器源码的 `deploy/travel/offline-cache/`：

- `mcp-source.tar.gz`
- `mcp-source.sha256`
- `PROVENANCE.json`（本次来源、补丁摘要与文件审查记录）
- `FILES.sha256`（本次包内文件列表及摘要，用于审查）

该目录已被 Git 忽略，但保留在 Docker 构建上下文中；不要只上传 `git archive HEAD` 而漏掉它。另需使用本版本新增的 `Mcp.offline.Dockerfile`。

从仓库根目录检查并构建，两个镜像串行执行：

```bash
(cd deploy/travel/offline-cache && sha256sum -c mcp-source.sha256)
tar -tzf deploy/travel/offline-cache/mcp-source.tar.gz
docker build --pull --progress=plain -t myplan-travel-api:preflight -f deploy/travel/Api.Dockerfile .
docker build --pull --progress=plain -t myplan-travel-mcp:preflight -f deploy/travel/Mcp.offline.Dockerfile .
```

MCP Dockerfile 先验证源码包摘要，再使用 `-mod=vendor`、`GOPROXY=off`、`GOTOOLCHAIN=local` 构建。编译步骤禁用网络，Go 编译并行度为 2／`-p 1`；不会在镜像构建中下载 GitHub 或 Go 模块。最终镜像保留 vendor 中的许可证与通知文件，浏览器沙箱和标准浏览器策略与正常构建一致。

常规 Compose 默认仍使用在线 `Mcp.Dockerfile`。使用离线构建持续部署时，在**本机私有**覆盖文件中指定 `mcp.build.dockerfile: deploy/travel/Mcp.offline.Dockerfile`，运行 Compose 时显式同时传入原文件与该覆盖文件；不要把真实域名、访问码或 Cookie 写进覆盖文件或镜像。后续域名、首次扫码、私有配置和手机验收按 [云端部署说明](../../docs/travel-cloud-deployment.md)进行。

## 重新准备源码包

需要 Git、Go 1.24+、tar 和可信的上游源码获取方式。以下是 POSIX shell 的重建步骤；所有新文件写入新的临时工作目录，命令不删除已有项目。Windows PowerShell 不应使用文本管道传输 tar 二进制；可以用 Git 的 `--output` 导出 tar 文件，再单独执行 `tar -xf`。

```bash
ROOT="$PWD"
BASE=a5c8f7799980ba1fdd501999843eb2d17e4c9a9f
WORK=$(mktemp -d)
CACHE="$ROOT/deploy/travel/offline-cache"
git clone https://github.com/xpzouying/xiaohongshu-mcp.git "$WORK/upstream"
git -C "$WORK/upstream" cat-file -e "$BASE^{commit}"
mkdir -p "$WORK/mcp" "$WORK/local-headless"
git -C "$WORK/upstream" archive --format=tar --output="$WORK/source.tar" "$BASE" -- '*.go' go.mod go.sum LICENSE browser/browser_version.txt
tar -xf "$WORK/source.tar" -C "$WORK/mcp"
git -C "$WORK/mcp" apply "$ROOT/tools/windows/mcp-compat/compat.patch"
cp -R "$ROOT/tools/windows/mcp-compat/files/." "$WORK/mcp/"
cp -R "$ROOT/deploy/travel/mcp/local-headless/." "$WORK/local-headless/"
cd "$WORK/mcp"
GOSUMDB=sum.golang.org go mod download
go mod verify
go mod vendor
GOPROXY=off GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOMAXPROCS=2 \
  go build -mod=vendor -p 1 -trimpath -buildvcs=false -ldflags='-s -w' -o "$WORK/cross-build-proof" .
mkdir -p "$CACHE"
# Use a new empty CACHE directory if an earlier bundle is still in use.
test ! -e "$CACHE/mcp-source.tar.gz"
tar -czf "$CACHE/mcp-source.tar.gz" -C "$WORK" mcp local-headless
(cd "$CACHE" && sha256sum mcp-source.tar.gz > mcp-source.sha256)
```

打包前只允许 `mcp/`、`local-headless/` 下的 Go 源码、必要嵌入素材、模块 vendor 和许可证；拒绝绝对路径、`..`、符号链接、`.git`、私有状态和独立可执行程序。交付时记录上游提交、兼容补丁摘要、archive SHA-256、文件列表及模块验证／离线交叉编译结果。不同工具的压缩元数据可能导致不同包摘要，应使用该次生成并核对的摘要，不能只修改旧摘要文件来接受不明来源的包。

本轮交付包已实际审查全部文件，并在 Windows Go 1.24.13 上以 `GOPROXY=off` 成功生成 vendor、验证模块并交叉编译 Linux amd64。Docker 使用的 Go 1.26.10 编译、Chromium 沙箱和 Linux 实际运行仍需目标主机验证；交叉编译成功不等于这些步骤已通过。
