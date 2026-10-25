BEGIN;
ALTER TABLE t_starline_event_reminder
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_error_message VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS last_delivery_outcome VARCHAR(16),
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_starline_failed_reminders
    ON t_starline_event_reminder (failed_at, id)
    WHERE reminder_status = 'FAILED';

COMMIT;
