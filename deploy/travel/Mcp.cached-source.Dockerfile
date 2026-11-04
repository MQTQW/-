# syntax=docker/dockerfile:1.7
# Small, verified public source transfer. Dependencies still need the official Tencent Go proxy.
FROM golang:1.26.10-bookworm AS build
ENV GOMAXPROCS=2 GOTOOLCHAIN=local GOPROXY=https://mirrors.cloud.tencent.com/go/ GOSUMDB=sum.golang.org
WORKDIR /tmp/source
COPY deploy/travel/source-cache/mcp-small.tar.gz deploy/travel/source-cache/mcp-small.sha256 ./
RUN sha256sum -c mcp-small.sha256 && mkdir -p /src && \
    tar --extract --gzip --file=mcp-small.tar.gz --directory=/src --no-same-owner
WORKDIR /src/mcp
RUN go mod download && go mod verify && go mod vendor
RUN --network=none GOPROXY=off CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -mod=vendor -p 1 -trimpath -buildvcs=false -ldflags="-s -w" -o /out/xhs-mcp . && \
    mkdir -p /out/vendor-licenses && \
    find vendor -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -exec cp --parents '{}' /out/vendor-licenses/ \;

FROM node:24.22.2-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium ca-certificates fonts-noto-cjk fonts-noto-color-emoji && rm -rf /var/lib/apt/lists/* && \
    mkdir -p /app/data/home /app/data/config && chown -R 1000:1000 /app/data
WORKDIR /app
COPY --from=build /out/xhs-mcp /app/xhs-mcp
COPY --from=build /usr/local/go/LICENSE /app/licenses/go/LICENSE
COPY --from=build /src/mcp/LICENSE /app/licenses/xiaohongshu-mcp/LICENSE
COPY --from=build /out/vendor-licenses/ /app/licenses/vendor/
COPY deploy/travel/mcp/local-headless/LICENSE /app/licenses/headless-browser/LICENSE
COPY deploy/travel/mcp-entry.mjs /opt/myplan/mcp-entry.mjs
USER 1000:1000
ENV POC_STANDARD_CHROME_BIN=/usr/bin/chromium COOKIES_PATH=/app/data/cookies.json HOME=/app/data/home XDG_CONFIG_HOME=/app/data/config
ENTRYPOINT ["node", "/opt/myplan/mcp-entry.mjs"]
