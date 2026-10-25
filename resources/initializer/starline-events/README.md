# 星序演出助手初始化数据集

此模板为方案 B 的第一阶段资产：星序人设、两个规则知识库、纯知识路由意图树、独立 MCP 工具注册表和知识型示例问题。实时演出事实必须走 `search_artist_events`，不要把具体场次写入知识文档。

## 重要：全量初始化会清理当前平台数据

`InitializeMain` 会执行模板中的 `cleanup.sql`，其中会清空会话、消息、知识库、意图树、技能和示例问题，再创建星序数据。**本模板已创建但本次开发不会执行初始化。** 只有明确决定重置线上数据、完成数据库备份并核实影响后，才运行该步骤。

管理员密码通过环境变量 `STARLINEAGENT_ADMIN_PASSWORD` 注入，真实凭据不能写入模板或 Git。

## 目录要点

- `mcp-tools/*.properties` 独立声明工具描述、常驻/召回策略和启用状态；意图树中只包含知识路由节点。
- `docs/knowledge/policy/` 只说明星序规则，不保存实时演出事实。
- `docs/knowledge/platform/` 说明公开页面字段的差异，不承诺平台 API 或全量覆盖。
- `prompts/agent-main.txt` 明确来源、时效、未知售票状态和提醒边界。
- `resources/regression/agent-memory-evaluation/datasets/starline-events.json` 是行为回归样例。
