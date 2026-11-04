# Android 本机旅行接口

这条接口让原生 Android 旅行页使用已有的真实检索与排程服务。服务仍只监听电脑 `127.0.0.1`，通过已授权的 USB 调试设备执行 `adb reverse tcp:8794 tcp:8794` 后，手机访问 `http://127.0.0.1:8794`。不需要将服务绑定到局域网，也不提供公网服务。

## 开启与连接

手机入口默认关闭。运营者在仓库外保存 JSON 文件，结构为 `{"token":"<64位小写十六进制随机令牌>"}`，通过启动参数显式开启：

```powershell
node server/travel-live/serve.mjs --mcp-config "<本机检索配置>" --native-token-file "<本机手机连接配置>"
```

这是本机手机联调连接令牌，与小红书 MCP 令牌、DeepSeek Key、高德 Key 各自独立。供应商密钥只保留在服务端；本机连接令牌不能放进 Git、BuildConfig、URL 查询参数、网页或日志。需要撤销时停止服务，生成新令牌后重启，并在手机更新配置。新令牌使旧令牌请求立即失效。

## 请求契约

所有 `/api/native/` 请求都发送 `Authorization: Bearer <连接令牌>`，包括 session、任务读取和配图。关闭状态返回 `404 NOT_FOUND`；启用后缺失或错误令牌返回 `401 NATIVE_AUTH_REQUIRED`。只接受无 `Origin` 的原生请求，带网页 Origin 或跨站 Fetch Metadata 的请求被拒绝；接口不返回 CORS 授权。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/native/session` | 建立原生 Cookie 会话、返回 CSRF、能力与上游连接状态；支持 `?refresh=1` |
| `POST /api/native/research` | 与 `/api/research` 相同请求体，返回 `202 {jobId}` |
| `POST /api/native/plan` | 与 `/api/plan` 相同请求体，返回 `202 {jobId}` |
| `GET /api/native/jobs/<id>` | 读取本原生会话拥有的任务 |
| `GET /api/native/images/<opaque-id>` | 读取已登记配图，仍需 Bearer 和原生 Cookie |

session 设置 `myplan_native_session` Cookie，属性为 `Path=/api/native; HttpOnly; SameSite=Strict; Max-Age=14400`。Android 应通过 CookieJar 沿用 Cookie；POST 额外发送 `Content-Type: application/json` 和 `X-CSRF-Token`，不能省略 CSRF。原生与网页 Cookie 名、会话通道各自独立，不能互换；多个手机会话即使共用本机令牌，也不能读取彼此任务或使用彼此研究池。

响应候选中的图片路径沿用既有 `/api/images/<opaque-id>` 格式。Android 下载时只接受该相对路径并映射成 `/api/native/images/<opaque-id>`，附带连接令牌和 Cookie；不能向原帖、上游 CDN 或任意外部 URL 附加本机令牌。正文／配图响应与浏览器接口共用数据契约，不引入固定样例。

## 恢复与限制

任务、研究池、会话在服务端内存中约保留 4 小时，重启即清空；相同类型和相同输入的运行中提交复用任务，不同输入返回 `ACTIVE_JOB_CONFLICT`。相同 Cookie 的刷新不改变任务归属。Cookie 丢失后重建 session 不能继续旧任务，需要重新检索。

图片池仍使用有限内存与过期淘汰；过期显示无图，不重新拼出外部图片地址。停止手机轮询不取消服务任务。AI、地图失败、没有体验候选和 `needs_changes` 沿用网页语义，不能用成功空行程掩盖问题。

USB 断开或转发失效后，手机保存的原生草稿仍应可见，新的检索和排程显示连接错误。再次接线、授权并重新设置 `adb reverse` 后才能继续联网。这个本机验证入口不等于离开电脑后的随身服务；公网部署与鉴权另行设计。

## 自动检查

```powershell
node --test server/travel-live/test/server.test.mjs server/travel-live/test/native-bridge.test.mjs
```

这些测试使用独立 loopback 服务和替身依赖，不调用外部平台或付费 AI。覆盖默认关闭、Bearer、Cookie／CSRF、Origin／Host、原生与网页通道隔离、任务归属、图片、过期和输入冲突。
