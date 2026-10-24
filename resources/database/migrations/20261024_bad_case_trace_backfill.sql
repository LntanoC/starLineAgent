-- Manual repair after reviewing the read-only candidate report. Never run as an automatic time-based backfill.
-- psql -v message_id=... -v user_id=... -v trace_id=... -f this_file.sql
\set ON_ERROR_STOP on
\if :{?message_id}
\else
\echo 'message_id is required'
\quit 1
\endif
\if :{?user_id}
\else
\echo 'user_id is required'
\quit 1
\endif
\if :{?trace_id}
\else
\echo 'trace_id is required'
\quit 1
\endif
BEGIN;
UPDATE t_message m SET trace_id=r.trace_id
FROM t_rag_trace_run r
WHERE m.id=:'message_id' AND m.user_id=:'user_id' AND m.role='assistant' AND m.deleted=0
 AND r.trace_id=:'trace_id' AND r.deleted=0
 AND r.user_id=m.user_id AND r.conversation_id=m.conversation_id
 AND (m.trace_id IS NULL OR m.trace_id=r.trace_id)
 AND (r.message_id IS NULL OR r.message_id=m.id);
UPDATE t_rag_trace_run r SET message_id=m.id
FROM t_message m
WHERE m.id=:'message_id' AND m.user_id=:'user_id' AND m.role='assistant' AND m.deleted=0
 AND r.trace_id=:'trace_id' AND r.trace_id=m.trace_id AND r.deleted=0
 AND r.user_id=m.user_id AND r.conversation_id=m.conversation_id
 AND (r.message_id IS NULL OR r.message_id=m.id);
COMMIT;
