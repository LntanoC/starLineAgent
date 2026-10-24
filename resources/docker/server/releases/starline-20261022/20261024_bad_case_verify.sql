-- Deployment smoke check. Synthetic rows are visible only inside this transaction and always rolled back.
\set ON_ERROR_STOP on
SELECT 'TRACE_COLUMNS',table_name,column_name FROM information_schema.columns
WHERE table_schema='public' AND ((table_name IN ('t_message','t_agent_message') AND column_name='trace_id')
 OR (table_name='t_rag_trace_run' AND column_name='message_id')) ORDER BY table_name;
BEGIN;
INSERT INTO t_message_feedback(id,message_id,conversation_id,user_id,vote,create_time,update_time,deleted)
VALUES('bc-fb-20261024','bc-msg-20261024','bc-conv-20261024','bc-user-20261024',-1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);
INSERT INTO t_bad_case(id,message_id,conversation_id,user_id,message_type,vote)
VALUES('bc-check-20261024','bc-msg-20261024','bc-conv-20261024','bc-user-20261024','UNKNOWN',-1)
ON CONFLICT(message_id,user_id) DO NOTHING;
INSERT INTO t_bad_case(id,message_id,conversation_id,user_id,message_type,vote)
VALUES('bc-dup-20261024','bc-msg-20261024','bc-conv-20261024','bc-user-20261024','UNKNOWN',-1)
ON CONFLICT(message_id,user_id) DO NOTHING;
DO $check$
BEGIN
 IF (SELECT count(*) FROM t_bad_case WHERE message_id='bc-msg-20261024' AND user_id='bc-user-20261024')<>1
 THEN RAISE EXCEPTION 'bad case unique constraint failed'; END IF;
END
$check$;
ROLLBACK;
SELECT 'NEGATIVE_FEEDBACK',count(*) FROM t_message_feedback WHERE vote=-1 AND deleted=0;
SELECT 'BAD_CASE_COUNT',count(*) FROM t_bad_case WHERE deleted=0;
SELECT 'REPORT_TYPES',message_type,count(*) FROM t_bad_case WHERE deleted=0 GROUP BY message_type;
SELECT 'SYNTHETIC_ROWS_REMAINING',count(*) FROM t_bad_case WHERE id IN ('bc-check-20261024','bc-dup-20261024');
