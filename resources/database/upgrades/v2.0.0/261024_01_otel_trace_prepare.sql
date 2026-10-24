-- Expand first: keep the old columns so the current service remains compatible if deployment rolls back.
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
    END IF;
END $$;

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
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_bad_case_run ON t_bad_case (run_id);
COMMIT;
