# 本地行程研究样例

用于验证“公开攻略 → 文字/图片解析 → 候选地点 → 地图匹配和交通估计 → 可编辑草稿”。这是独立的 Node 22+ 命令行工具，暂未接入 Android，没有部署公网服务。

## 配置

复制 `.env.example` 为 `.env.local`，填写 `DEEPSEEK_API_KEY` 和 `AMAP_WEB_SERVICE_KEY`。后者需要高德 **Web 服务** 类型 Key，不要求 Android 签名 SHA1 或 PackageName。配置文件已被仓库的 `.gitignore` 排除。

- [高德 Key 官方申请说明](https://lbs.amap.com/api/webservice/create-project-and-key)
- [DeepSeek 图片输入](https://api-docs.deepseek.com/guides/vision/)
- [高德地点搜索 2.0](https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch)
- [高德路径规划 2.0](https://lbs.amap.com/api/webservice/guide/api/newroute/)

缺少 AI 配置时，仅对杭州样例执行公开正文地名的规则匹配，明确标为 `body_rules`；没有读图或真实模型调用。缺少地图配置时，候选保持待核验，自动日程留空。可以在 HTML 中手动编辑，但导出始终保留待核验状态。

## 运行

使用已经运行、仅监听回环地址并带令牌的 MCP 服务。`--mcp-config` 指向本机 JSON 配置，形状为 `{ "url": "http://127.0.0.1:18062", "token": "本机访问令牌" }`。该配置应放在仓库外，不能提交。

```powershell
node server/travel-research/cli.mjs --mcp-config <本机MCP配置文件> --destination 杭州 --days 3 --out delivery/travel-research-20261028
```

也可以复用已取得的来源清单，避免重复访问小红书：

```powershell
node server/travel-research/cli.mjs --source-file delivery/travel-research-20261028/sources.json --destination 杭州 --days 3 --out delivery/travel-research-20261028
```

兼容本次验证期间的原始捕获目录：

```powershell
node server/travel-research/cli.mjs --capture-dir <原始MCP捕获目录> --destination 杭州 --days 3 --out delivery/travel-research-20261028
```

只刷新已生成报告的地图信息，可复用本机已校验提取结果，避免再次调用模型和识图。目的地必须一致，引用仍会重新检查：

```powershell
node server/travel-research/cli.mjs --resume-report delivery/travel-research-20261028/research.json --destination 杭州 --days 3 --out delivery/travel-research-20261028
```

生成 `index.html` 和 `research.json`。HTML 无外部脚本、字体或图片请求，可直接打开；编辑只发生在页面内存中，点击“导出”下载修改后的 JSON。再次运行 CLI 会重新生成文件，不会读取用户此前下载的修改稿。`research.json` 是本机研究资料，含取得的公开正文，不建议直接发布。

## 证据与边界

- MCP 客户端只允许登录检查、公开笔记搜索和详情读取；最多取 5 篇，不加载全部评论。
- 视觉解析最多选 3 张短正文来源的图片，未选中或无法读取的图片不能作为提取证据。模型仅能引用输入正文或已转录图片的原文片段；程序生成候选 ID 并保留来源。
- 地图匹配只确认地点身份和坐标；同名分店、入口、不同城市或多个精确匹配结果保留待确认，不盲选第一条。
- 开放、预约、临时闭园和门票仍需景区官方信息核实。交通耗时为查询时的估计，景区内部步行和排队未计入。
- 未知交通始终保留 `null`，建议缓冲另存为 `planningBufferMinutes`。草稿不是全局最优路线，也不会自动保存到 App 或创建提醒。
- 本机保存的 Cookie、访问令牌和模型/地图 Key 均不进入 HTML 或导出结果。外部正文作为数据处理，不能授权工具执行其他操作。

## 测试

```powershell
cd server/travel-research
node --test
```

测试使用明确的虚构输入和模拟上游，不代表真实模型质量、地图权限、持续可用性或手机端联调已通过。
