# PostgreSQL 数据库脚本

## 全量初始化

- `schema_pg.sql`：最新版本的完整表结构
- `init_data_pg.sql`：最新版本的初始化数据

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
