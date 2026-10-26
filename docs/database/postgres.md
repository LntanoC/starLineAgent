# 数据库初始化与升级说明

SQL 文件仍位于 resources/database/；本页集中说明新库初始化、已有库迁移规则。文中 upgrades/、migrations/ 路径均相对于 resources/database/。

## 全量初始化

- `schema_pg.sql`：最新版本的完整表结构
- `init_data_pg.sql`：最新版本的初始化数据，包含首期 GOV 与 TICKETING 官方来源白名单种子

新环境直接按顺序执行这两个文件，不需要再执行历史升级脚本

## 增量升级

`upgrades/` 按正式发布版本划分目录。当前正式版本是 v1.0，因此开发期间产生的 v1.1.0 数据库升级脚本统一放在 `upgrades/v1.1.0/`

目录内的脚本使用首次提交日期和变更含义命名：

- 默认格式：`yyMMdd_变更含义.sql`
- 同一天有多个脚本时：`yyMMdd_两位顺序号_变更含义.sql`

已有环境必须按文件名顺序逐个执行，不合并过程脚本。后续正式版本在 `upgrades/` 下新建对应目录并继续使用相同的命名规则

已经对外提供或被其他开发者执行过的升级脚本保持不变，新的数据库变更继续追加独立脚本

## Agent 唯一入口（2026-10-24）

全量 schema 已移除五张 workflow 表。现有部署先备份、切换新版并确认 Agent 正常，再执行 migrations/20261024_workflow_retire.sql。保留 t_message_feedback、Agent 消息/记忆/审计和知识库表。历史 upgrades/backups 只作为版本历史，不应重复用于新库初始化。历史反馈不在本次自动删除。

## 移除旧 OTel Trace（2026-10-24）

全量 schema 使用 `t_bad_case.run_id` 关联自建 Agent 审计，并保留 `legacy_trace_id` 供历史 bad case 查询；新消息不再保存 OTel ID。已有环境先执行 `upgrades/v2.0.0/261024_01_otel_trace_prepare.sql`，确认新服务正常后再执行 `upgrades/v2.0.0/261024_02_otel_trace_retire.sql`。第一步为新服务增加字段并复制旧值，保留原列供回滚；第二步才移除旧列和索引。

## 管理员来源白名单（2026-10-26）

现有环境执行 `migrations/20261026_starline_whitelist_admin_control.sql`，把原双评审状态转换为 `ACTIVE` / `DISABLED`。原已通过且可公开访问的域名保持启用，其他候选域名停用。管理员保存、编辑或启停来源后，下一次 MCP 演出查询立即读取新列表；历史 `t_starline_source_review` 表保留但不再参与运行逻辑。

## 演出查询卡片与双信源分层（2026-10-26）

现有环境执行 `migrations/20261026_starline_event_cards.sql`，增加 `source_tier` 并写入首期文旅公示与票务平台白名单种子，同时更新星序主提示词和演出查询工具描述。新环境按顺序执行最新 `schema_pg.sql` 和 `init_data_pg.sql`，表结构与 5 个首期来源都会初始化；白名单种子使用 `ON CONFLICT DO NOTHING`，重跑初始化时不会覆盖管理员调整的来源状态和配置。初始化模板已同步工具与提示词说明。

如果旧环境中的 Agent 名称为“星序助手”，还需追加执行 `migrations/20261026_starline_agent_prompt_compat.sql`，将其 `AGENT_MAIN` 提示词同步到卡片提醒流程；新初始化名称“星序演出助手”也可重复执行该兼容脚本。
