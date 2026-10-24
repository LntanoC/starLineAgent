-- Contract after the new service has passed health checks; historical values remain in legacy columns.
BEGIN;
SET LOCAL lock_timeout = '10s';

ALTER TABLE t_agent_message ADD COLUMN IF NOT EXISTS legacy_otel_trace_id VARCHAR(64);
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_message' AND column_name = 'trace_id'
    ) THEN
        UPDATE t_agent_message
        SET legacy_otel_trace_id = COALESCE(legacy_otel_trace_id, trace_id);
        ALTER TABLE t_agent_message DROP COLUMN trace_id;
    END IF;
END $$;
DROP INDEX IF EXISTS idx_agent_message_trace;
COMMENT ON COLUMN t_agent_message.legacy_otel_trace_id IS
    'Historical OTel identifier retained for old messages; unused by current Agent runtime';

ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS legacy_trace_id VARCHAR(64);
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_bad_case' AND column_name = 'trace_id'
    ) THEN
        UPDATE t_bad_case SET legacy_trace_id = COALESCE(legacy_trace_id, trace_id);
        UPDATE t_bad_case SET run_id = COALESCE(run_id, trace_id)
        WHERE trace_match_status = 'AGENT_AUDIT';
        ALTER TABLE t_bad_case DROP COLUMN trace_id;
    END IF;
END $$;
DROP INDEX IF EXISTS idx_bad_case_trace;
CREATE INDEX IF NOT EXISTS idx_bad_case_run ON t_bad_case (run_id);
COMMENT ON COLUMN t_bad_case.trace_match_status IS
    'AGENT_AUDIT/AGENT_BLOCKS/MISSING; legacy rows may contain older values';
COMMENT ON COLUMN t_bad_case.legacy_trace_id IS
    'Historical trace identifier retained for old bad-case records; not used by current Agent runtime';

COMMIT;
