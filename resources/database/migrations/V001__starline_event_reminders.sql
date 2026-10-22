CREATE TABLE IF NOT EXISTS t_starline_event_reminder (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    artist_name     VARCHAR(160) NOT NULL,
    event_name      VARCHAR(240),
    info_status     VARCHAR(32) NOT NULL,
    source_name     VARCHAR(160),
    source_url      TEXT,
    ticket_time     TIMESTAMP,
    email           VARCHAR(320),
    remind_at       TIMESTAMP,
    reminder_status VARCHAR(32) NOT NULL,
    sent_at         TIMESTAMP,
    created_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_starline_user_created
    ON t_starline_event_reminder (user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_starline_due_reminders
    ON t_starline_event_reminder (reminder_status, remind_at)
    WHERE reminder_status = 'PENDING';