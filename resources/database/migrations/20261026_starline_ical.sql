-- Replace SMTP reminder delivery with a user-scoped iCalendar subscription.
-- Preserve activities and cancellation; old delivery states become active follows.
BEGIN;

UPDATE t_starline_event_reminder
   SET reminder_status = CASE WHEN reminder_status = 'CANCELLED' THEN 'CANCELLED' ELSE 'ACTIVE' END;

ALTER TABLE t_starline_event_reminder
    DROP CONSTRAINT IF EXISTS ck_starline_reminder_status,
    DROP COLUMN IF EXISTS email,
    DROP COLUMN IF EXISTS remind_at,
    DROP COLUMN IF EXISTS info_status,
    DROP COLUMN IF EXISTS sent_at,
    DROP COLUMN IF EXISTS attempt_count,
    DROP COLUMN IF EXISTS last_error_code,
    DROP COLUMN IF EXISTS last_error_message,
    DROP COLUMN IF EXISTS last_delivery_outcome,
    DROP COLUMN IF EXISTS failed_at;

ALTER TABLE t_starline_event_reminder
    ALTER COLUMN reminder_status SET DEFAULT 'ACTIVE',
    ALTER COLUMN ticket_time DROP NOT NULL,
    ADD CONSTRAINT ck_starline_reminder_status
        CHECK (reminder_status IN ('ACTIVE', 'CANCELLED'));

DROP INDEX IF EXISTS idx_starline_due_reminders;
DROP INDEX IF EXISTS idx_starline_failed_reminders;
DROP INDEX IF EXISTS idx_starline_user_active;
CREATE INDEX IF NOT EXISTS idx_starline_user_active
    ON t_starline_event_reminder (user_id, ticket_time)
    WHERE reminder_status = 'ACTIVE' AND ticket_time IS NOT NULL;

CREATE TABLE IF NOT EXISTS t_starline_calendar_token (
    id                VARCHAR(64) PRIMARY KEY,
    user_id           VARCHAR(64) NOT NULL,
    token             VARCHAR(64) NOT NULL UNIQUE,
    created_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_access_time  TIMESTAMP,
    revoked           SMALLINT NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_starline_calendar_token_active_user
    ON t_starline_calendar_token (user_id) WHERE revoked = 0;

COMMENT ON TABLE t_starline_event_reminder IS
    '星序活动关注记录：已公布开票时间的活动通过 iCal 订阅提前 10 分钟提醒';
COMMENT ON COLUMN t_starline_event_reminder.ticket_time IS
    '开票时间；为空时表示待公布，不进入日历订阅';
COMMENT ON COLUMN t_starline_event_reminder.reminder_status IS
    'ACTIVE=仍关注，CANCELLED=用户已取消';
COMMENT ON TABLE t_starline_calendar_token IS
    '星序日历订阅凭证；仅用于读取当前用户的 iCal 活动订阅';

UPDATE t_agent_prompt SET content = replace(content, '- 支持的提前量为 1 天、1 小时、10 分钟，可多选；默认提前 1 小时；使用中国标准时间 Asia/Shanghai。', '- 已确认的官方开票时间通过 iCal 日历订阅提醒，固定提前 10 分钟；使用中国标准时间 Asia/Shanghai。'), update_time = CURRENT_TIMESTAMP WHERE slot_key = 'AGENT_MAIN' AND content LIKE '%' || '- 支持的提前量为 1 天、1 小时、10 分钟，可多选；默认提前 1 小时；使用中国标准时间 Asia/Shanghai。' || '%';

COMMIT;
