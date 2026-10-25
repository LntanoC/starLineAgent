BEGIN;
SET LOCAL lock_timeout='10s';

SET LOCAL lock_timeout = '10s';

ALTER TABLE t_bad_case ALTER COLUMN message_id DROP NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN conversation_id DROP NOT NULL;

ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS subject_type VARCHAR(32);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS subject_id VARCHAR(64);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(160);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS primary_source VARCHAR(32);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS confidence VARCHAR(8);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS severity VARCHAR(4);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS triage_status VARCHAR(24);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS signal_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS first_detected_at TIMESTAMP;
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS last_detected_at TIMESTAMP;
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS snapshot_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS auto_review_result JSONB;
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS auto_review_model VARCHAR(64);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS auto_reviewed_at TIMESTAMP;
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS suppressed_reason VARCHAR(512);
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

UPDATE t_bad_case
SET subject_type = CASE WHEN message_type = 'AGENT' THEN 'AGENT_MESSAGE' ELSE 'RAG_MESSAGE' END,
    subject_id = message_id,
    dedup_key = 'MSG:' || message_id,
    title = left(coalesce(nullif(user_question, ''), 'bad case'), 255),
    primary_source = 'USER_FEEDBACK',
    confidence = 'HIGH',
    severity = 'S3',
    triage_status = 'READY_FOR_HUMAN',
    signal_count = 1,
    first_detected_at = create_time,
    last_detected_at = create_time,
    snapshot_status = CASE
        WHEN message_type = 'UNKNOWN' OR message_status = 'MISSING' THEN 'MISSING'
        ELSE 'PARTIAL'
    END,
    archived_at = CASE WHEN deleted = 1 THEN update_time ELSE NULL END
WHERE subject_id IS NULL;

ALTER TABLE t_bad_case ALTER COLUMN subject_type SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN subject_id SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN dedup_key SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN primary_source SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN confidence SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN severity SET NOT NULL;
ALTER TABLE t_bad_case ALTER COLUMN triage_status SET NOT NULL;

ALTER TABLE t_bad_case DROP CONSTRAINT IF EXISTS t_bad_case_vote_check;
ALTER TABLE t_bad_case ALTER COLUMN vote SET DEFAULT 0;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='t_bad_case_vote_check') THEN ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_vote_check CHECK (vote IN (-1, 0)); END IF; END $$;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='t_bad_case_confidence_check') THEN ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_confidence_check CHECK (confidence IN ('HIGH','LOW')); END IF; END $$;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='t_bad_case_severity_check') THEN ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_severity_check CHECK (severity IN ('S1','S2','S3','S4')); END IF; END $$;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='t_bad_case_triage_check') THEN ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_triage_check CHECK (triage_status IN ('OBSERVING','AUTO_REVIEW_PENDING','READY_FOR_HUMAN','SUPPRESSED')); END IF; END $$;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='t_bad_case_snapshot_check') THEN ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_snapshot_check CHECK (snapshot_status IN ('PENDING','COMPLETE','PARTIAL','MISSING')); END IF; END $$;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='t_bad_case'::regclass AND conname='uk_bad_case_dedup') THEN ALTER TABLE t_bad_case ADD CONSTRAINT uk_bad_case_dedup UNIQUE (dedup_key); END IF; END $$;

CREATE INDEX IF NOT EXISTS idx_bad_case_triage
    ON t_bad_case (triage_status, confidence, severity, last_detected_at DESC)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_bad_case_subject
    ON t_bad_case (subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_bad_case_primary_source
    ON t_bad_case (primary_source, create_time DESC) WHERE deleted = 0;

COMMENT ON COLUMN t_bad_case.confidence IS '检测信号可信度，不是人工根因置信度，也不是统计概率';
COMMENT ON COLUMN t_bad_case.triage_status IS '机器分诊状态，与人工 review_status 分离';


CREATE TABLE IF NOT EXISTS t_bad_case_signal (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    bad_case_id VARCHAR(20) NOT NULL REFERENCES t_bad_case(id),
    source VARCHAR(32) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    confidence VARCHAR(8) NOT NULL CHECK (confidence IN ('HIGH','LOW')),
    severity VARCHAR(4) NOT NULL CHECK (severity IN ('S1','S2','S3','S4')),
    occurrence_key VARCHAR(160) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    detector_version VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    invalidated_at TIMESTAMP,
    invalid_reason VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bad_case_signal_occurrence UNIQUE (rule_code, occurrence_key)
);

CREATE INDEX IF NOT EXISTS idx_bad_case_signal_case
    ON t_bad_case_signal (bad_case_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_bad_case_signal_rule
    ON t_bad_case_signal (source, rule_code, occurred_at DESC);

-- 历史点踩也必须补一条信号，不能只回填 signal_count。
INSERT INTO t_bad_case_signal
    (id,bad_case_id,source,rule_code,confidence,severity,occurrence_key,
     evidence,detector_version,occurred_at)
SELECT b.id,b.id,'USER_FEEDBACK','USER_DOWNVOTE','HIGH','S3',
       'feedback:' || b.message_id || ':' || b.user_id,
       jsonb_build_object('legacyBackfill',true,'reason',b.reason,'comment',b.comment),
       'legacy-v1',b.create_time
FROM t_bad_case b
WHERE b.message_id IS NOT NULL AND b.vote=-1
ON CONFLICT (rule_code,occurrence_key) DO NOTHING;

UPDATE t_bad_case b
SET signal_count = (
    SELECT count(*) FROM t_bad_case_signal s
    WHERE s.bad_case_id = b.id AND s.invalidated_at IS NULL
);

CREATE TABLE IF NOT EXISTS t_bad_case_review_log (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    bad_case_id VARCHAR(20) NOT NULL REFERENCES t_bad_case(id),
    action VARCHAR(16) NOT NULL,
    root_cause VARCHAR(32),
    root_cause_detail VARCHAR(1024),
    fix_action VARCHAR(32),
    fix_ref VARCHAR(128),
    fix_note VARCHAR(1024),
    review_status VARCHAR(16) NOT NULL,
    reviewer VARCHAR(64),
    review_time TIMESTAMP,
    revision BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bad_case_review_log_case
    ON t_bad_case_review_log (bad_case_id, create_time DESC);

CREATE TABLE IF NOT EXISTS t_bad_case_sample_batch (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    sample_date DATE NOT NULL,
    engine VARCHAR(16) NOT NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('DISCOVERY','MEASUREMENT')),
    stratum_key VARCHAR(64) NOT NULL DEFAULT 'ALL',
    rate_bp INTEGER NOT NULL,
    seed VARCHAR(64) NOT NULL,
    frame_count INTEGER NOT NULL DEFAULT 0,
    selected_count INTEGER NOT NULL DEFAULT 0,
    inclusion_probability NUMERIC(12,11),
    frame_high_water JSONB NOT NULL,
    frame_definition_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bad_case_sample_batch
        UNIQUE (sample_date, engine, purpose, stratum_key)
);

CREATE TABLE IF NOT EXISTS t_bad_case_eval_sample (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    bad_case_id VARCHAR(20) NOT NULL REFERENCES t_bad_case(id),
    case_id VARCHAR(128) NOT NULL,
    dataset_name VARCHAR(128) NOT NULL DEFAULT 'bad-case-regression',
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT,
    turns JSONB NOT NULL,
    test_lane VARCHAR(24) NOT NULL DEFAULT 'CONVERSATION'
        CHECK (test_lane IN ('CONVERSATION','SERVICE_TEST','INTEGRATION_TEST')),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','APPROVED','EXPORTED','RETIRED')),
    dataset_fingerprint VARCHAR(64),
    last_run_id VARCHAR(64),
    last_result VARCHAR(16),
    last_run_finished_at TIMESTAMP,
    recurrence_count INTEGER NOT NULL DEFAULT 0,
    created_by VARCHAR(64),
    reviewer VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bad_case_eval_case UNIQUE (dataset_name, case_id)
);

CREATE TABLE IF NOT EXISTS t_bad_case_eval_result (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    sample_id VARCHAR(20) NOT NULL REFERENCES t_bad_case_eval_sample(id),
    run_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(128) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    repetition INTEGER NOT NULL,
    dataset_turn_fingerprint VARCHAR(64) NOT NULL,
    turn_status VARCHAR(16) NOT NULL,
    scored SMALLINT NOT NULL,
    passed SMALLINT,
    failure_class VARCHAR(64),
    checks JSONB,
    run_finished_at TIMESTAMP NOT NULL,
    raw_result JSONB,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bad_case_eval_result_turn
        UNIQUE (run_id, case_id, turn_id, repetition)
);
CREATE INDEX IF NOT EXISTS idx_bad_case_eval_result_sample
    ON t_bad_case_eval_result (sample_id, run_finished_at DESC);


ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS capture_status VARCHAR(16),
 ADD COLUMN IF NOT EXISTS round_count INTEGER,
 ADD COLUMN IF NOT EXISTS tool_call_count INTEGER,
 ADD COLUMN IF NOT EXISTS snapshot_attempts INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS t_bad_case_detector_cursor (
 detector VARCHAR(64) PRIMARY KEY, cursor_time TIMESTAMP NOT NULL, cursor_id VARCHAR(20) NOT NULL,
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS t_bad_case_metric (
 metric_date DATE NOT NULL, detector VARCHAR(64) NOT NULL, metric VARCHAR(64) NOT NULL, value BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(metric_date,detector,metric));
CREATE TABLE IF NOT EXISTS t_bad_case_sample_member (
 batch_id VARCHAR(20) NOT NULL REFERENCES t_bad_case_sample_batch(id),
 message_id VARCHAR(20) NOT NULL, user_id VARCHAR(20) NOT NULL,
 score VARCHAR(64) NOT NULL, selected BOOLEAN NOT NULL, processed BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(batch_id,message_id));
CREATE TABLE IF NOT EXISTS t_bad_case_auto_review_attempt (
 id VARCHAR(20) PRIMARY KEY,bad_case_id VARCHAR(20) NOT NULL REFERENCES t_bad_case(id),
 model VARCHAR(128),prompt_version VARCHAR(32) NOT NULL, verdict VARCHAR(16),error_code VARCHAR(64),
 duration_ms BIGINT, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_bad_case_auto_attempt_day ON t_bad_case_auto_review_attempt(create_time);
CREATE INDEX IF NOT EXISTS idx_bad_case_runtime_scan ON t_agent_run_node(ended_at,id) WHERE deleted=0 AND status='FAILED';

ALTER TABLE t_bad_case_eval_sample ADD COLUMN IF NOT EXISTS last_scored_result VARCHAR(16),
 ADD COLUMN IF NOT EXISTS last_scored_finished_at TIMESTAMP;
ALTER TABLE t_bad_case_auto_review_attempt ADD COLUMN IF NOT EXISTS candidate_revision BIGINT NOT NULL DEFAULT 0;
CREATE OR REPLACE FUNCTION bad_case_legacy_insert_defaults() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.dedup_key IS NULL AND NEW.message_id IS NOT NULL THEN
  NEW.subject_type=CASE WHEN NEW.message_type='AGENT' THEN 'AGENT_MESSAGE' ELSE 'RAG_MESSAGE' END;
  NEW.subject_id=NEW.message_id;NEW.dedup_key='MSG:'||NEW.message_id;NEW.primary_source='USER_FEEDBACK';
  NEW.confidence='HIGH';NEW.severity='S3';NEW.triage_status='READY_FOR_HUMAN';
  NEW.title=left(coalesce(NEW.user_question,'用户点踩'),255);NEW.first_detected_at=NEW.create_time;NEW.last_detected_at=NEW.create_time;
  NEW.snapshot_status='PARTIAL';
 END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_bad_case_legacy_insert_defaults ON t_bad_case;
CREATE TRIGGER trg_bad_case_legacy_insert_defaults BEFORE INSERT ON t_bad_case FOR EACH ROW EXECUTE FUNCTION bad_case_legacy_insert_defaults();
COMMIT;
