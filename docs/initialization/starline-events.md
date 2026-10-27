# 星序演出助手初始化数据集

模板包含星序人设、两个规则知识库、独立 MCP 工具注册表、Agent 技能和知识型示例问题。RAG 在所有已启用知识库中进行多通道检索，不再使用意图树选择知识库或触发澄清。实时演出事实必须走 `search_artist_events`，不要把具体场次写入知识文档。

## 重要：全量初始化会清理当前平台数据

`InitializeMain` 会执行模板中的 `cleanup.sql`，清理会话、消息、知识库、技能和示例问题，再创建星序数据。**不要在保留线上数据时运行全量入口。** 只有明确决定重置线上数据、完成数据库备份并核实影响后，才运行该步骤。

`cleanup.sql` 会保留遗留的 `t_intent_node` 表及其数据，避免代码回滚时丢失恢复依据。当前运行代码不再读取该表；新版完成线上验证后，再单独安排数据库清理。

管理员密码通过环境变量 `STARLINEAGENT_ADMIN_PASSWORD` 注入，真实凭据不能写入模板或 Git。

## 目录要点

- `resources/initializer/starline-events/mcp-tools/*.properties` 独立声明工具描述、常驻/召回策略和启用状态。
- `resources/initializer/starline-events/docs/knowledge/policy/` 说明星序规则，不保存实时演出事实。
- `resources/initializer/starline-events/docs/knowledge/platform/` 说明公开页面字段差异，不承诺平台 API 或全量覆盖。
- `resources/initializer/starline-events/prompts/agent-main.txt` 明确来源、时效、未知售票状态和提醒边界。
- `resources/regression/agent-memory-evaluation/datasets/starline-events.json` 是行为回归样例。

## 保留现有数据时分步补齐 RAG 资产

如果只需导入星序知识库和文档，不要运行 `CleanupMain` 或 `InitializeMain`。先运行 `PreflightMain`，再按顺序运行 `KnowledgeBaseInitMain`、`DocumentInitMain`。文档导入默认不替换同名文档；若需覆盖，应显式调整 `document.replace-existing` 并先核实影响范围。

`PreflightMain` 的空闲检测只检查知识文档、摄取任务和 Redis 活跃锁，不依赖已退役的 `t_rag_trace_run`。`VerifyMain` 会校验示例问题、MCP 工具、人设等整套模板资产；只做 RAG 分步初始化时，不要把它当作必需步骤，应单独核对知识库、文档分块和向量。
