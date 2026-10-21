SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- Explicit allowlist. Do not add CASCADE: new relationships must be reviewed deliberately.
TRUNCATE TABLE
    t_message_feedback,
    t_conversation_summary,
    t_message,
    t_conversation,
    t_agent_state,
    t_agent_message,
    t_agent_conversation,
    t_agent_memory,
    t_agent_memory_control,
    t_agent_memory_extraction,
    t_agent_context_compaction,
    t_rag_trace_node,
    t_rag_trace_run,
    t_ingestion_task_node,
    t_ingestion_task,
    t_ingestion_pipeline_node,
    t_ingestion_pipeline,
    t_knowledge_document_schedule_exec,
    t_knowledge_document_schedule,
    t_knowledge_document_chunk_log,
    t_knowledge_chunk,
    t_knowledge_document,
    t_knowledge_vector,
    t_intent_node,
    t_agent_skill,
    t_query_term_mapping,
    t_sample_question,
    t_knowledge_base,
    t_biz_change_log
RESTART IDENTITY;

-- Delete non-builtin agent profiles row by row; truncating these two tables would remove the builtin
-- agent itself and the fallback target for every empty slot. Order matters: prompts first, profile last.
-- Afterwards no profile is active and all slots fall back to the builtin one, which is exactly the
-- state this dataset expects. Another dataset may have left an activated profile behind.
DELETE
FROM t_agent_prompt
WHERE agent_id IN (SELECT id FROM t_agent_profile WHERE builtin = 0);

DELETE
FROM t_agent_profile
WHERE builtin = 0;
