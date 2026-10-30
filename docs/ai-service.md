# 一句话安排、计划拆解与旅行草稿：AI 服务

本版提供 Android 客户端和可自行部署的轻量 Node.js 服务源码，**没有购买服务器、部署公网接口或使用真实模型密钥进行在线验证**。未配置服务时，应用仍可使用本地支持的句式解析和手动创建计划；复杂理解与 AI 拆解需要联网并配置服务。不能将本地解析结果称为已经调用 AI。

AI 只返回计划草稿、步骤建议或旅行行程草稿。用户确认后，Android 才调用现有本地保存流程；提醒、专注计时、积分和响铃继续本地执行，不依赖 AI 服务持续运行。服务不连接数据库，也不接受创建提醒、扣分、修改计划等命令。

## 运行服务

需要 Node.js 22 或以上，无第三方 npm 依赖。在 `server/ai-assist` 下将 `.env.example` 复制为 `.env`，填写：

| 环境变量 | 含义 |
| --- | --- |
| `DEEPSEEK_API_KEY` | 模型供应商密钥，只放在服务端 |
| `ASSIST_ACCESS_TOKEN` | 独立生成的 32–256 位可见 ASCII 访问码，仅供私人部署使用；不要复用模型密钥 |
| `DEEPSEEK_MODEL` | 默认 `deepseek-flash`，可按账户支持的模型修改 |
| `HOST` | 默认 `127.0.0.1`，部署环境按实际需要绑定 |
| `PORT` | 默认 `8787` |

```powershell
node --env-file=.env server.mjs
node --test
```

`.env` 已在服务目录的 `.gitignore` 排除。生产环境使用宿主平台的环境变量或 secret 管理，外层必须提供 HTTPS。Android 设置填写完整地址，如 `https://your-domain.example/v1/assist`，以及专用访问码；**APK 不内置服务地址、访问码或该服务的模型密钥**。

调试 APK 允许 `localhost`、`127.0.0.1`、`::1`、Android 模拟器宿主 `10.0.2.2` 的 HTTP 地址；正式构建仅允许 HTTPS。URL 不接受内嵌用户名、密码、查询参数或 fragment，避免将凭据放入 URL。客户端配置单独保存在 `ai_assist_settings`，由 Android 备份排除规则保护；清空 AI 设置会删除本机配置。

服务使用 Node request listener，可由提供 Node 兼容 `req/res` 的云函数适配器调用 `createAssistHandler()`；具体平台的路由、HTTPS、鉴权与配额仍需要实际部署配置。本仓库没有宣称任何云函数已上线。

## HTTP 契约

`POST /v1/assist`，请求头 `Content-Type: application/json`、`Authorization: Bearer <专用访问码>`。所有响应 `Cache-Control: no-store`。

```json
{
  "operation": "parse",
  "text": "明晚八点读书，专注二十五分钟",
  "now": "2026-10-26T15:00:00+08:00",
  "timeZone": "Asia/Shanghai"
}
```

`now` 必须是带偏移的 ISO 日期时间，且日期、当地时间与 `timeZone` 一致。由手机传入当前时间与时区，服务不让模型返回 epoch 毫秒数。

```json
{
  "draft": {
    "title": "读书",
    "category": "学习",
    "repeatMode": "ONCE",
    "date": "2026-10-27",
    "reminderTimes": ["20:00"],
    "customDays": [],
    "focusMinutes": 25
  },
  "question": null
}
```

重复方式仅支持 `ONCE`、`EVERY_DAY`、`WEEKDAYS` 和 `CUSTOM`。`CUSTOM` 星期 1–7 表示周一至周日；其他模式星期数组为空，周期计划的 `date` 为 `null`。未明确周期按一次性理解，缺少具体日期、提醒时间时必须追问；明确包含隔周、每月、每年、每隔几天等不支持周期的输入由服务端确定性规则直接返回 `draft:null` 与解释问题，不调用模型，不能擅自改成每天。“下个月1号”等单次日期表达不被该周期规则拦截。没有指定专注时长时 `focusMinutes:null`，明确建议时仅接受 15、25、45。日期、提醒时间、重复字段及额外字段在服务端和 Android 各校验一次；Android 还拒绝所在时区夏令时跳变中不存在的时间，以及已经过去的一次性提醒。

`split` 使用同一接口，带已有计划上下文：

```json
{
  "operation": "split",
  "text": "学习 Kotlin",
  "now": "2026-10-26T15:00:00+08:00",
  "timeZone": "Asia/Shanghai",
  "context": { "title": "学习 Kotlin", "description": "想入门协程" }
}
```

返回 `{"steps":[{"title":"阅读协程基础概念","focusMinutes":15}]}`，最多 8 个非重复步骤。步骤不生成提醒，用户修改、勾选采纳后在本地保存。

### 旅行行程草稿

`itinerary` 复用同一 HTTPS 地址、访问码、限流和安全错误处理，接收明确的旅行偏好：

```json
{
  "operation": "itinerary",
  "destination": "用户指定的目的地",
  "title": "旅行草稿",
  "startDate": "2030-10-28",
  "dayCount": 2,
  "timeZoneId": "Asia/Shanghai",
  "transport": "公共交通",
  "pace": "适中",
  "notes": "希望留出休息时间"
}
```

`dayCount` 为 1–14；交通仅支持公共交通、步行、自驾；节奏为舒缓、适中、紧凑。目的地最多 200 字，旅行名称最多 120 字，偏好说明最多 1000 字。日期是当地 `yyyy-MM-dd`，时区为有效 IANA ID。

返回结构为 `{"days":[{"day":1,"stops":[{"title":"区域散步","location":"可搜索的地区名称","startTime":"09:00","durationMinutes":60,"notes":"建议时段；请自行核实地点开放与交通信息。"}]}]}`。真实响应必须恰好有请求指定的天数，`day` 从 1 连续递增，每天 1–6 个节点。每节点名称最多 120 字、地点最多 200 字、备注最多 1000 字；时间为 `HH:mm`，停留时长为 1–720 分钟整数。同一天节点须按时间排序、无重叠且不跨午夜。两端均拒绝多余字段，包括坐标、精确距离等未在契约中的数据。

**这不是实时旅行或地图查询。** 服务不接入地图、营业、票务、天气、路况和交通数据源；生成的名称只是可搜索地名，时间与停留时长为建议，需要用户自行核实。提示词明确禁止声称已经查询、验证或预订，不能把草稿当成实时营业时间、精确路线或可达性承诺。没有服务配置或服务失败时显示实际错误，不生成虚假的 AI 行程作为降级结果；手动安排行程由应用的本地编辑流程处理。

旅行响应单独限制为 512 KiB，生成 token 上限按天数从 1900 到 13600 增长、硬上限 14000；上游超时 45 秒，Android 总调用上限 50 秒。原有 `parse/split` 的 32 KiB、1400 tokens、20 秒上游／25 秒客户端限制不变；所有操作请求仍最多 12 KiB，复用同一实例配额。旅行生成可能比普通解析消耗更多模型额度，部署时仍应配置供应商预算限制。旅行接口同样未部署、未使用真实模型在线验证；本地测试仅使用模拟上游响应。

错误仅返回 `{"error":{"code":"..."}}`，不转发上游正文、请求内容、访问码或模型密钥。客户端将 401、429、超时、未配置、网络异常和无效输出转成可操作的中文提示。协程取消会取消对应 OkHttp Call，服务在连接断开后取消上游请求。`GET /health` 只用于进程存活检查，固定返回 `{"ok":true}`，不披露配置或供应商信息。

## 私人部署的边界

- 每请求最多 12 KiB。普通解析／拆解文本最多 1000 字符、计划标题最多 80 字符，上游响应最多 32 KiB，生成上限 1400 tokens，20 秒上游超时，Android 总调用上限 25 秒；旅行的独立上限见上节。
- 访问码 SHA-256 后通过常量时间比较。每 IP 每分钟 10 次、每 UTC 日 60 次，单实例每日总共 100 次，最多并发 2 次；拒绝的繁忙请求不会占用并发。不开匿名 AI 接口，不提供任意上游 URL 代理。
- 内存配额在重启后重置，多实例彼此独立；不能作为面向公众的计费或额度系统。正式公开前应使用用户身份验证、共享限流存储，以及托管平台和供应商的预算上限。共享访问码只适合自己或少量可信测试人员，泄露后须立即更换。
- 默认根据 socket IP 限流，不信任客户端可伪造的 `X-Forwarded-For`。置于反向代理后可能把所有请求归为同一 IP；部署时应在可信边缘配置限流，或在限定来源的适配器内取得真实 IP，不能直接无条件信任该请求头。
- 服务本身不记录用户文本；请求只包含用户本次提交内容和拆解所需的标题、说明，不上传整个计划库。模型供应商会接收这些内容，实际部署应向使用者说明处理方，并检查托管平台是否默认记录请求体。
- 供应商调用固定到官方 `https://api.deepseek.com/chat/completions`，不允许重定向。使用 `response_format: {"type":"json_object"}` 并在 system prompt 明确 JSON 格式；拒绝空输出、截断结果和不合约定的字段。参考 [DeepSeek JSON 输出文档](https://api-docs.deepseek.com/guides/json_mode/) 与 [Chat Completions 接口文档](https://api-docs.deepseek.com/api/create-chat-completion/)。模型名称依据本轮官方文档选用 `deepseek-flash`，避免继续依赖旧名称 `deepseek-chat`。

服务测试使用本机 HTTP 与模拟上游，覆盖鉴权、限流、并发、日期与字段校验、错误清理及超时；Android 测试覆盖独立输出校验、配置、时区、取消请求等，不消耗真实模型额度。模拟响应通过不代表模型语义已经在线验证，部署后仍需以真实模型检查中文日期、模糊表达和不支持周期的追问。
