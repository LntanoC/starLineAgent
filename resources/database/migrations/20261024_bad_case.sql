-- Starline offline bad-case migration. Additive and safe to re-run; no historical time-based UPDATE.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE t_rag_trace_run ADD COLUMN IF NOT EXISTS message_id VARCHAR(20);
ALTER TABLE t_agent_message ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_message_trace ON t_message (trace_id);
CREATE INDEX IF NOT EXISTS idx_trace_run_message ON t_rag_trace_run (message_id);
CREATE INDEX IF NOT EXISTS idx_agent_message_trace ON t_agent_message (trace_id);
CREATE TABLE IF NOT EXISTS t_bad_case (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    message_id VARCHAR(20) NOT NULL,
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    message_type VARCHAR(16) NOT NULL DEFAULT 'RAG',
    trace_id VARCHAR(64),
    trace_matched_by_fallback SMALLINT NOT NULL DEFAULT 0,
    trace_match_status VARCHAR(32) NOT NULL DEFAULT 'MISSING',
    vote SMALLINT NOT NULL CHECK (vote = -1),
    reason VARCHAR(255),
    comment VARCHAR(1024),
    user_question TEXT,
    assistant_answer TEXT,
    thinking_content TEXT,
    message_status VARCHAR(32),
    sources JSONB,
    retrieved_chunks JSONB,
    trace_nodes JSONB,
    suspicions JSONB,
    evidence_notes JSONB,
    message_create_time TIMESTAMP,
    root_cause VARCHAR(32),
    root_cause_detail VARCHAR(1024),
    fix_action VARCHAR(32),
    fix_ref VARCHAR(128),
    fix_note VARCHAR(1024),
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING','REVIEWED','IGNORED','FIXED')),
    reviewer VARCHAR(64),
    review_time TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_bad_case_src UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_bad_case_status ON t_bad_case (review_status, create_time);
CREATE INDEX IF NOT EXISTS idx_bad_case_trace ON t_bad_case (trace_id);
CREATE INDEX IF NOT EXISTS idx_bad_case_root ON t_bad_case (root_cause, create_time);
CREATE INDEX IF NOT EXISTS idx_feedback_bad_case_scan ON t_message_feedback (update_time, id)
    WHERE vote=-1 AND deleted=0;
COMMENT ON TABLE t_bad_case IS '点踩离线分析台账；自动线索与人工判定分开，归档保留唯一键墓碑';
COMMENT ON COLUMN t_bad_case.trace_match_status IS 'DIRECT/TIME_CANDIDATE/AMBIGUOUS/MISSING/AGENT_BLOCKS/OTEL_DIRECT_WITH_BLOCKS';
COMMENT ON COLUMN t_bad_case.suspicions IS '规则产生的待核查线索，不是已确认根因';
COMMIT;
