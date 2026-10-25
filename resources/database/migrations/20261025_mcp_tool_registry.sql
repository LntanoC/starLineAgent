-- MCP 工具注册表：把 MCP 工具治理从意图树中拆出。
-- 可重复执行；先保留 kind=2 节点到新表，再软删除旧 SYSTEM/MCP 节点以恢复知识路由边界。

BEGIN;

CREATE TABLE IF NOT EXISTS t_mcp_tool (
    id               VARCHAR(20) NOT NULL PRIMARY KEY,
    tool_id          VARCHAR(128) NOT NULL UNIQUE,
    display_name     VARCHAR(64),
    description      TEXT NOT NULL,
    description_hash VARCHAR(64),
    require_confirm  SMALLINT NOT NULL DEFAULT 0,
    resident         SMALLINT NOT NULL DEFAULT 0,
    dispatchable     SMALLINT NOT NULL DEFAULT 1,
    enabled          SMALLINT NOT NULL DEFAULT 1,
    sort_order       INTEGER NOT NULL DEFAULT 900,
    remark           VARCHAR(255),
    create_by        VARCHAR(20),
    update_by        VARCHAR(20),
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_mcp_tool_active
    ON t_mcp_tool (enabled, dispatchable, sort_order) WHERE deleted = 0;

CREATE TABLE IF NOT EXISTS t_mcp_tool_vector (
    tool_id           VARCHAR(128) NOT NULL PRIMARY KEY,
    model_id          VARCHAR(64) NOT NULL,
    dimension         INTEGER NOT NULL,
    embedding         BYTEA NOT NULL,
    description_hash VARCHAR(64) NOT NULL,
    update_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_mcp_tool IS 'MCP 工具元数据与分发策略；与意图树解耦';
COMMENT ON COLUMN t_mcp_tool.description IS '工具用途说明，同时作为 Tool RAG 检索语料';
COMMENT ON COLUMN t_mcp_tool.resident IS '常驻工具直接进入 Agent 工具集，不参与召回';
COMMENT ON COLUMN t_mcp_tool.dispatchable IS '是否允许被 Tool RAG 召回并暴露给模型';
COMMENT ON TABLE t_mcp_tool_vector IS 'MCP 工具描述向量，使用 BYTEA 保存 float32 向量';

-- 仅备份本次确实会停用的旧节点，部署失败时可精确恢复，不会重活化历史已删除行。
CREATE TABLE IF NOT EXISTS t_mcp_tool_intent_migration_backup (
    intent_node_id VARCHAR(20) PRIMARY KEY,
    deleted_before SMALLINT NOT NULL,
    migrated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO t_mcp_tool_intent_migration_backup (intent_node_id, deleted_before)
SELECT id, deleted FROM t_intent_node
WHERE kind IN (1, 2) AND deleted = 0
ON CONFLICT (intent_node_id) DO NOTHING;

-- 先迁移旧意图树 MCP 节点。使用基于 tool_id 的稳定 ID，避免重复迁移时主键冲突。
INSERT INTO t_mcp_tool (
    id, tool_id, display_name, description, require_confirm, resident,
    dispatchable, enabled, sort_order, remark
)
SELECT
    'mt_' || SUBSTRING(MD5(n.mcp_tool_id) FROM 1 FOR 17),
    n.mcp_tool_id,
    n.name,
    COALESCE(NULLIF(BTRIM(n.description), ''), 'MCP 工具：' || n.mcp_tool_id),
    COALESCE(n.require_confirm, 0),
    CASE WHEN n.mcp_tool_id IN ('search_artist_events', 'current_date') THEN 1 ELSE 0 END,
    1,
    COALESCE(n.enabled, 1),
    COALESCE(n.sort_order, 900),
    'migrated from t_intent_node kind=2'
FROM t_intent_node n
WHERE n.kind = 2 AND n.deleted = 0 AND n.mcp_tool_id IS NOT NULL
ON CONFLICT (tool_id) DO NOTHING;

-- 新安装或尚无 MCP 意图节点的实例也能注册星序首期工具。
INSERT INTO t_mcp_tool (id, tool_id, display_name, description, resident, dispatchable, enabled, sort_order, remark)
VALUES
    ('mt_' || SUBSTRING(MD5('search_artist_events') FROM 1 FOR 17), 'search_artist_events', '艺人演出查询',
     '查询中国大陆艺人近期演唱会、巡演、城市场次、场馆、开票时间与售票状态。只返回公开页面线索；来源核验、过期判断和冲突处理由星序业务规则完成。',
     1, 1, 1, 100, 'starline default resident tool'),
    ('mt_' || SUBSTRING(MD5('current_date') FROM 1 FOR 17), 'current_date', '当前日期',
     '查询指定时区的当前日期和星期，用于解释今天、明天、今年、下个月等相对时间。',
     1, 1, 1, 110, 'starline default resident tool'),
    ('mt_' || SUBSTRING(MD5('youcom_search') FROM 1 FOR 17), 'youcom_search', '通用联网检索',
     '对演出安排以外的一般公开信息进行联网检索；不能替代 search_artist_events 进行艺人演出事实查询。',
     0, 1, 1, 900, 'optional MCP tool; requires YDC_API_KEY')
ON CONFLICT (tool_id) DO NOTHING;

-- MCP 工具不再属于意图路由。只软删除旧节点，数据仍可恢复。
UPDATE t_intent_node
SET deleted = 1, update_time = CURRENT_TIMESTAMP
WHERE id IN (SELECT intent_node_id FROM t_mcp_tool_intent_migration_backup)
  AND deleted = 0;

-- 回滚时先按 t_mcp_tool_intent_migration_backup 精确恢复被停用节点；注册表表可保留以避免丢失新治理数据。

COMMIT;
