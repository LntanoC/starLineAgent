-- 平台库 ragent 的清理脚本，只清这套数据集会重建的东西
--
-- 固定白名单，不加 CASCADE：以后新加的关联关系要一条条审过才能进来，不给空白支票
--
-- 故意不整表清的三张：t_user 清了 admin 账号就没了，初始化完登不进去；
-- t_agent_profile 与 t_agent_prompt 清了连内置智能体和槽位回落的终点都没有了

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

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

-- 非内置人设按行删，不整表清：重复执行会撞 t_agent_profile 的名称唯一约束
-- 顺序不能反，prompt 先删 profile 后删；删完没有任何 profile 处于激活，槽位全部回落内置，
-- 这正是「切回企业助手」的状态
--
-- 必须走 SQL 不能走 DELETE /agents/{id}：接口对激活中的智能体会直接拒
DELETE
FROM t_agent_prompt
WHERE agent_id IN (SELECT id FROM t_agent_profile WHERE builtin = 0);

DELETE
FROM t_agent_profile
WHERE builtin = 0;
