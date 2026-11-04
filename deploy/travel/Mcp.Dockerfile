# syntax=docker/dockerfile:1.7
FROM golang:1.26.10-bookworm AS build
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# Pin source as well as the application runtime; no latest MCP binary is downloaded.
RUN git init mcp && cd mcp && git remote add origin https://github.com/xpzouying/xiaohongshu-mcp.git && \
    git fetch --depth=1 origin a5c8f7799980ba1fdd501999843eb2d17e4c9a9f && git checkout --detach FETCH_HEAD
COPY tools/windows/mcp-compat/compat.patch /tmp/compat.patch
COPY tools/windows/mcp-compat/files/ /tmp/mcp-files/
COPY deploy/travel/mcp/local-headless/ /src/local-headless/
WORKDIR /src/mcp
RUN git apply /tmp/compat.patch && cp -R /tmp/mcp-files/. . && go mod download && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/xhs-mcp .

FROM node:24.22.2-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium ca-certificates fonts-noto-cjk fonts-noto-color-emoji && rm -rf /var/lib/apt/lists/* && \
    mkdir -p /app/data/home /app/data/config && chown -R 1000:1000 /app/data
WORKDIR /app
COPY --from=build /out/xhs-mcp /app/xhs-mcp
COPY --from=build /src/mcp/LICENSE /app/licenses/xiaohongshu-mcp/LICENSE
COPY deploy/travel/mcp/local-headless/LICENSE /app/licenses/headless-browser/LICENSE
COPY deploy/travel/mcp-entry.mjs /opt/myplan/mcp-entry.mjs
USER 1000:1000
ENV POC_STANDARD_CHROME_BIN=/usr/bin/chromium COOKIES_PATH=/app/data/cookies.json HOME=/app/data/home XDG_CONFIG_HOME=/app/data/config
ENTRYPOINT ["node", "/opt/myplan/mcp-entry.mjs"]

