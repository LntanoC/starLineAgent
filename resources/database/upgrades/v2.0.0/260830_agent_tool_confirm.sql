-- v2.0.0 260830 Agent 写操作执行前确认
-- 意图树的 MCP 节点新增确认开关：勾上之后 Agent 调这个工具前先停下来，把工具名和参数交给用户点头
-- 默认 0 而不是 1：确认卡片是打断动作，误弹一次的代价是用户把整个能力关掉，写工具由接入方逐节点勾选
-- 非 MCP 节点写死 0，与 mcp_tool_id 同进退，避免节点改类型后确认标志残留、改回来时突然生效
-- message_status 扩宽给 AWAITING_CONFIRM 留位：等待确认是挂起态不是终态，续跑成功后会改回 NORMAL

ALTER TABLE t_intent_node
    ADD COLUMN IF NOT EXISTS require_confirm SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_intent_node.require_confirm IS '执行前是否需要用户确认 1：需要 0：不需要';

ALTER TABLE t_agent_message
    ALTER COLUMN message_status TYPE VARCHAR(32);
