# 小体积源码传输与腾讯云 Go 代理

当目标主机无法访问 GitHub，且完整 vendor 源码包不便传输时，可使用 `Mcp.cached-source.Dockerfile`。`source-cache/mcp-small.tar.gz` 只包含固定基线和已审查兼容补丁的公开 MCP／浏览器模块源码，**不含 vendor**。因此该方式仍需要通过网络下载 Go 模块，不能称为完全离线构建。

腾讯云 [Go SDK 官方文档](https://cloud.tencent.com/document/sdk/Go)推荐 `https://mirrors.tencent.com/go/`；[软件源官方说明](https://cloud.tencent.com.cn/document/product/213/8623)确认 `mirrors.cloud.tencent.com` 是同一软件源的公网／云内网统一域名。本 Dockerfile 只指定该 HTTPS 代理，不设置 `direct` 或其他备用代理。`GOSUMDB=sum.golang.org` 和 `go.sum` 校验保留；代理或校验失败时停止，不关闭 TLS 或校验。

本轮在可信电脑核验以下接口均为 HTTPS 200，但**仍需在目标云机验证**：

```text
https://mirrors.cloud.tencent.com/go/github.com/go-rod/rod/@v/v0.116.2.mod
https://mirrors.cloud.tencent.com/go/github.com/modelcontextprotocol/go-sdk/@v/v1.4.0.mod
https://mirrors.cloud.tencent.com/go/sumdb/sum.golang.org/supported
```

另用全新的 Go 模块缓存从该代理成功下载 `github.com/go-rod/rod@v0.116.2` 的模块包，保留 `GOSUMDB=sum.golang.org`，其模块与 go.mod 摘要均与固定源码的 `go.sum` 一致。这只证明该电脑上的代理和校验链可用，不替代云机完整依赖下载验收。

从可信交付记录取得小包、对应 SHA-256 文件和 `PROVENANCE.json`，放入服务器源码 `deploy/travel/source-cache/`。该目录由 Git 忽略，不能只依赖 `git archive HEAD` 传输它。小包内仅有公开源码、模块清单与许可证；不能放入 Cookie、浏览器目录、身份资料、私有配置或可执行程序。

```bash
(cd deploy/travel/source-cache && sha256sum -c mcp-small.sha256)
docker build --pull --progress=plain -t myplan-travel-mcp:preflight -f deploy/travel/Mcp.cached-source.Dockerfile .
```

构建先验证源码包摘要，再通过腾讯云代理下载依赖，执行 `go mod verify` 并生成 vendor。最后的编译步骤禁用网络，使用 Go 1.26.10／amd64、`-p 1` 与 `GOMAXPROCS=2`；运行镜像、非 root 浏览器、沙箱和许可证保留规则与正常配置一致。基础镜像和 Debian 签名软件包仍需可访问。

当前没有 Docker 引擎的本机构建不能替代云机的代理下载、容器编译和 Chromium 沙箱验收。若完整模块代理在云机不可用，应回到 [vendor 离线包](OFFLINE.md)方案，而非关闭校验。
