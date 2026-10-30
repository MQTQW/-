# 本机联网旅行服务

为 `design/travel-live` 提供真实攻略检索、候选与配图整理，以及用户选择后的 AI 编排。主线是「城市 → 真实帖子 → 有来源的候选 → 用户选择 → 真实 AI 行程」，没有固定攻略或本地规则的首次生成回退。

## 启动

需要 Node.js 22、可用网络，以及已启动并登录的本机小红书 MCP 服务。浏览器使用当前版本 Chrome。MCP 连接器只调用 `check_login_status`、`search_feeds` 和 `get_feed_detail`。

在仓库外准备本机 MCP 连接 JSON，字段为 `url` 与 `token`。`url` 是本机 HTTP 根地址，如 `http://127.0.0.1:<MCP端口>/`；`token` 使用现有服务分配的令牌。该文件不放进前端或交付包。

服务端配置默认读取 `server/travel-research/.env.local`，也可用 `--env-file` 指定；同名进程环境变量优先。

| 字段 | 用途 |
| --- | --- |
| `DEEPSEEK_API_KEY` | 真实候选提取和行程编排，必需 |
| `DEEPSEEK_MODEL` | 模型名，默认 `deepseek-flash` |
| `AMAP_WEB_SERVICE_KEY` | 地点身份／坐标核对与部分相邻交通估计；未配置时保留待核对状态 |

从项目根目录运行，替换私有配置文件占位路径：

```powershell
node design/travel-live/build.mjs
node server/travel-live/serve.mjs --mcp-config "<本机私有JSON路径>"
```

打开 [联网旅行页面](http://127.0.0.1:8794)。前台启动时可用 Ctrl+C 停止。可选参数为 `--env-file "<服务端配置路径>"`、`--port 8794`。服务绑定 `127.0.0.1`，不提供局域网或 Android 入口；页面和 API 由同一服务提供。单独打开 HTML 不能完成联网流程。

## 实际处理范围

每轮读取 1–3 篇攻略，前端请求 3 篇；最多返回 12 个有来源的同城候选。景点、美食、体验按实际结果显示，某类可以为空。最多核对 8 个地点，只有通过名称、城市与坐标匹配的项目获得已知位置。`verified` 仅表示这项 POI 身份与坐标核对，不代表营业、票务、价格或原帖说法已全部核实。

配图保留攻略归属，标为“攻略配图，非地点实拍确认”。服务预读取最多 3 张，AI 读图与图片可读取分别计数；一张攻略图可关联多个候选。单轮最多注册 12 张展示图，内存图片池最多 36 项并按近期使用情况淘汰；单图上限 5 MiB。前端只取得 `/api/images/<opaque-id>`，不取得上游图片鉴权地址。

首次规划接受本轮候选的 1–12 个已选 ID 和优先级，支持 1–5 天。候选在成功结果中各出现一次，额外安排只来自用户填写的抵达、住宿和返程条件。必选表示内容保留，固定时间是另一种约束。未排下的项目返回 `needs_changes` 与具名原因，不以缺项行程宣告成功。AI 结构校验失败最多发起一次修正请求。

交通核对只覆盖可查询的相邻已知位置。返回的是查询时估计；若耗时超过排定空档，界面和 TXT 显示时间不足，用户仍需调整。未查询、地点被手动替换或相关安排变化的路段保持待核对，不声称路线全局最优。结果内“规则调整”使用本地规则，不是再次请求 AI。

## API 契约

先调用 `GET /api/session` 建立浏览器会话；响应返回本会话的 `csrfToken`，同时设置 HttpOnly Cookie。随后沿用 Cookie；POST 还必须带同源 `Origin`、`Content-Type: application/json` 和 `X-CSRF-Token`。接口只接受当前本机 Host／Origin，不开放跨域访问。除 session 外不接受查询参数。

| 方法与路径 | 输入 | 成功响应 |
| --- | --- | --- |
| `GET /`、`GET /index.html` | 无 | 构建后的前端 HTML，并建立会话 |
| `GET /api/session` | 可选 `?refresh=1` 强制复查上游登录；否则连接状态缓存 20 秒 | `200 {csrfToken, capabilities:{research,ai,map}, connection:{loggedIn,message}}` |
| `POST /api/research` | `{destination, count?}`；城市 1–40 字，count 为 1–3、默认 3 | `202 {jobId}` |
| `POST /api/plan` | `{researchId, selected:[{id,priority}], form}`；priority 为 `want` 或 `must` | `202 {jobId}` |
| `GET /api/jobs/<jobId>` | 当前会话拥有的任务 ID | `200 {id,kind,status,progress,updatedAt,result?,error?}` |
| `GET /api/images/<opaque-id>` | 本机图片池展示 ID，需有效会话 | 图片字节；不存在或已淘汰时为 `404` |

任务 `kind` 为 `research`／`plan`，`status` 为 `running`／`done`／`failed`。HTTP 错误为 `{error:{code,message}}`；任务执行失败在任务响应的 `error` 中返回同样结构。

`form` 使用以下字段；日期未定时设置 `undated:true`，日期不参与编排：

```json
{
  "destination": "杭州",
  "days": 1,
  "date": "",
  "undated": true,
  "arrival": "",
  "returnTime": "",
  "hotel": "",
  "pace": "舒缓",
  "mode": "步行"
}
```

抵达／返程为可选 `HH:MM`；住宿留空表示未定，填写后生成首日入住占位，尚未核对地址和实际入住规则。前端交通值为 `公共交通`、`步行`、`自驾`。目的地应与本次研究城市一致。

研究任务完成后的 `result` 是 `{id,city,destination,fetchedAt,sources,candidates,warnings,stats,status}`。`sources` 含来源 ID、标题、作者、日期、原帖链接、摘要与代理配图；`candidates` 含 ID、分类、标题、时长、`sourceIds`／`sourceRefs`、位置核对状态、坐标和代理配图。统计包括 `sourceCount`、`candidateCount`、`imagesAvailable`、`imagesRead`、`matchedPlaceCount`；状态分别记录检索、AI 提取和地图核对结果。

规划任务完成后的 `result` 有两种：

- `{status:"ready", trip, unplaced:[], warnings, stats}`：通过已选 ID、时段与固定条件校验的草稿；`trip.days[].stops[]` 保留选择、优先级、出处与位置状态。
- `{status:"needs_changes", trip:null, unplaced:[{id,title,priority,reason}], message, warnings, stats}`：需要用户调整条件，清单保留。

常见恢复方向：

| 代码 | 处理 |
| --- | --- |
| `SESSION_REQUIRED`、`CSRF_REJECTED` | 重新获取 session 与 CSRF；同 Cookie 会话的旧任务仍可继续，会话失效或更换后才无法访问原任务 |
| `RESEARCH_EXPIRED` | 重新检索，再选择与编排 |
| `JOB_NOT_FOUND` | 任务过期、重启后已清除，或不属于当前会话 |
| `ACTIVE_JOB_CONFLICT` | 同类不同输入的任务仍在运行，继续查看原任务或等待完成 |
| `SERVICE_BUSY` | 等待正在执行的任务结束 |
| `AI_NOT_CONFIGURED` | 修正服务端配置并重启旅行服务 |
| MCP 登录／连接错误 | 恢复上游服务或登录后，再检测连接 |

## 生命周期与恢复

相同会话、相同类型、相同输入的运行中提交复用同一任务；不同输入返回冲突，不把旧结果标成新表单的生成结果。单机最多并行 3 项任务，任务执行上限为 8 分钟，没有取消任务的公开接口。前端“停止查看”只停止轮询。

会话、研究结果和任务均为进程内短期数据，约 4 小时后清理，任务容量清理也可能提前淘汰已完成项；重启全部清空。浏览器 localStorage 中的清单和已生成行程可继续查看，但不能据此恢复服务器研究池；图片也可能因重启或淘汰失效。重新编排遇到资料过期时应重新检索。浏览器 Cookie、协议、主机或端口变化会影响原任务的继续访问。

## 检查与文件分工

从项目根目录运行测试；测试替换外部依赖，不发起真实付费检索或 AI 调用：

```powershell
node --test server/travel-live/test/*.test.mjs
node --test design/travel-live/model.test.mjs design/travel-live/live-client.test.mjs
```

真实联调的脱敏元数据见[联调记录](../../delivery/travel-live-20261030/research-evidence.json)；它记录本次结果，不是候选固定数量或服务可用性的承诺。不要把完整帖子、私有配置或鉴权链接追加到该文件。

| 文件 | 职责 |
| --- | --- |
| `serve.mjs` | 同源页面／API、会话、任务、研究池与输入指纹 |
| `mcp-client.mjs` | 读取本机连接配置，调用三个只读 MCP 工具 |
| `engine.mjs` | 真实检索、来源校验、AI 提取、地点核对和公开结果 |
| `media.mjs` | 受限图片代理、按需下载和 LRU 图片池 |
| `selection-planner.mjs` | 已选项 AI 编排、约束校验、修正与相邻交通核对 |

