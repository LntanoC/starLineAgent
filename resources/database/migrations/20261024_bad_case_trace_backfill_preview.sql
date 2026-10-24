-- READ ONLY. Historical candidates from the last 7 days, never silently write approximate links.
-- Only mutually unique candidates are listed. Review with the run question before a deliberate correction.
WITH candidates AS (
 SELECT m.id AS message_id,m.user_id,m.conversation_id,r.trace_id,r.extra_data,
        count(*) OVER (PARTITION BY m.id,m.user_id) AS message_matches,
        count(*) OVER (PARTITION BY r.trace_id) AS trace_matches
 FROM t_message m JOIN t_rag_trace_run r ON r.conversation_id=m.conversation_id AND r.user_id=m.user_id
 WHERE m.role='assistant' AND m.deleted=0 AND r.deleted=0 AND m.trace_id IS NULL
   AND r.message_id IS NULL AND r.trace_name='rag-stream-chat'
   AND m.create_time>=CURRENT_TIMESTAMP-INTERVAL '7 days'
   AND r.start_time BETWEEN m.create_time-INTERVAL '5 minutes' AND m.create_time+INTERVAL '5 minutes'
)
SELECT message_id,user_id,conversation_id,trace_id,extra_data
FROM candidates WHERE message_matches=1 AND trace_matches=1 ORDER BY message_id;
