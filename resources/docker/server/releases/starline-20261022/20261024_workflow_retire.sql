-- Run only after backup and successful Agent-only deployment.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '30s';
-- RESTRICT intentionally refuses unexpected dependent objects; no broad CASCADE.
DROP TABLE IF EXISTS public.t_rag_trace_node RESTRICT;
DROP TABLE IF EXISTS public.t_rag_trace_run RESTRICT;
DROP TABLE IF EXISTS public.t_conversation_summary RESTRICT;
DROP TABLE IF EXISTS public.t_message RESTRICT;
DROP TABLE IF EXISTS public.t_conversation RESTRICT;
COMMIT;
