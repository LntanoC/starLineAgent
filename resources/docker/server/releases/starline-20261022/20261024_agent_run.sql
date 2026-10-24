-- Starline self-hosted Agent audit. Additive and idempotent.
BEGIN;
SET LOCAL lock_timeout = '10s';
CREATE TABLE IF NOT EXISTS t_agent_run (
    id VARCHAR(20) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    parent_run_id VARCHAR(64),
    resume_message_id VARCHAR(20),
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    user_message_id VARCHAR(20),
    assistant_message_id VARCHAR(20),
    question TEXT,
    answer TEXT,
    run_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' CHECK (run_status IN ('RUNNING','NORMAL','INTERRUPTED','FAILED','AWAITING_CONFIRM')),
    capture_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE' CHECK (capture_status IN ('COMPLETE','PARTIAL','DISABLED')),
    round_count INTEGER NOT NULL DEFAULT 0,
    model_call_count INTEGER NOT NULL DEFAULT 0,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT,
    output_tokens BIGINT,
    cached_tokens BIGINT,
    duration_ms BIGINT,
    error_message TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_agent_run_node (
    id VARCHAR(20) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(32) NOT NULL,
    parent_node_id VARCHAR(32),
    round_index INTEGER NOT NULL DEFAULT 0,
    node_type VARCHAR(16) NOT NULL,
    node_name VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    batch_id VARCHAR(64),
    tool_call_id VARCHAR(128),
    duration_source VARCHAR(16),
    input_payload JSONB,
    output_payload JSONB,
    input_tokens BIGINT,
    output_tokens BIGINT,
    cached_tokens BIGINT,
    ttft_ms BIGINT,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    duration_ms BIGINT,
    error_message TEXT,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_run_node UNIQUE(run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation ON t_agent_run(conversation_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_created ON t_agent_run(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON t_agent_run(run_status, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_message ON t_agent_run(assistant_message_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_agent_node_run ON t_agent_run_node(run_id, started_at);
CREATE INDEX IF NOT EXISTS idx_agent_node_type ON t_agent_run_node(node_type, status);
COMMENT ON TABLE t_agent_run IS 'Agent execution audit, keyed by StreamTask taskId';
COMMENT ON TABLE t_agent_run_node IS 'Actual model inputs and tool execution audit; admin only';
COMMIT;
