-- PostgreSQL Schema for StarlineAgent
-- Converted from MySQL schema_table.sql

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- User & Conversation Tables
-- ============================================

CREATE TABLE t_user (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    password     VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL,
    avatar       VARCHAR(128),
    create_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
COMMENT ON TABLE t_user IS '系统用户表';
COMMENT ON COLUMN t_user.id IS '主键ID';
COMMENT ON COLUMN t_user.username IS '用户名，唯一';
COMMENT ON COLUMN t_user.password IS '密码';
COMMENT ON COLUMN t_user.role IS '角色：admin/user';
COMMENT ON COLUMN t_user.avatar IS '用户头像';
COMMENT ON COLUMN t_user.create_time IS '创建时间';
COMMENT ON COLUMN t_user.update_time IS '更新时间';
COMMENT ON COLUMN t_user.deleted IS '是否删除 0：正常 1：删除';

CREATE TABLE t_message_feedback (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    message_id      VARCHAR(20)       NOT NULL,
    conversation_id VARCHAR(20)  NOT NULL,
    user_id         VARCHAR(20)  NOT NULL,
    vote            SMALLINT     NOT NULL,
    reason          VARCHAR(255),
    comment         VARCHAR(1024),
    create_time     TIMESTAMP  NOT NULL,
    update_time     TIMESTAMP  NOT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_msg_user UNIQUE (message_id, user_id)
);
CREATE INDEX idx_conversation_id ON t_message_feedback (conversation_id);
CREATE INDEX idx_user_id ON t_message_feedback (user_id);
COMMENT ON TABLE t_message_feedback IS '会话消息反馈表';

CREATE TABLE t_sample_question (
    id          VARCHAR(20)        NOT NULL PRIMARY KEY,
    title       VARCHAR(64),
    description VARCHAR(255),
    question    VARCHAR(255) NOT NULL,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT      DEFAULT 0
);
CREATE INDEX idx_sample_question_deleted ON t_sample_question (deleted);
COMMENT ON TABLE t_sample_question IS '示例问题表';

-- ============================================
-- Business Change Audit Tables
-- ============================================

CREATE TABLE t_biz_change_log (
    id               VARCHAR(20)  NOT NULL PRIMARY KEY,
    biz_type         VARCHAR(64)  NOT NULL,
    biz_id           VARCHAR(64)  NOT NULL,
    operation_type   VARCHAR(32)  NOT NULL,
    action_desc      VARCHAR(512),
    before_snapshot  JSONB,
    after_snapshot   JSONB,
    change_diff      JSONB,
    operator_id      VARCHAR(64),
    operator_name    VARCHAR(128),
    operator_role    VARCHAR(64),
    success          BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message    TEXT,
    class_name       VARCHAR(255),
    method_name      VARCHAR(255),
    ip               VARCHAR(64),
    user_agent       VARCHAR(512),
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_biz_change_log_biz ON t_biz_change_log (biz_type, biz_id);
CREATE INDEX idx_biz_change_log_time ON t_biz_change_log (create_time);
CREATE INDEX idx_biz_change_log_operator ON t_biz_change_log (operator_id);
COMMENT ON TABLE t_biz_change_log IS '业务数据变更审计日志表';
COMMENT ON COLUMN t_biz_change_log.biz_type IS '业务对象类型';
COMMENT ON COLUMN t_biz_change_log.biz_id IS '业务对象主键';
COMMENT ON COLUMN t_biz_change_log.operation_type IS '操作类型';
COMMENT ON COLUMN t_biz_change_log.action_desc IS '操作描述';
COMMENT ON COLUMN t_biz_change_log.before_snapshot IS '变更前快照';
COMMENT ON COLUMN t_biz_change_log.after_snapshot IS '变更后快照';
COMMENT ON COLUMN t_biz_change_log.change_diff IS '变更差异';
COMMENT ON COLUMN t_biz_change_log.operator_id IS '操作人ID';
COMMENT ON COLUMN t_biz_change_log.operator_name IS '操作人名称';
COMMENT ON COLUMN t_biz_change_log.operator_role IS '操作人角色';
COMMENT ON COLUMN t_biz_change_log.success IS '是否成功';
COMMENT ON COLUMN t_biz_change_log.error_message IS '失败信息';
COMMENT ON COLUMN t_biz_change_log.class_name IS '触发类名';
COMMENT ON COLUMN t_biz_change_log.method_name IS '触发方法名';
COMMENT ON COLUMN t_biz_change_log.ip IS '来源IP';
COMMENT ON COLUMN t_biz_change_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN t_biz_change_log.create_time IS '创建时间';

-- ============================================
-- Knowledge Base Tables
-- ============================================

CREATE TABLE t_knowledge_base (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64) NOT NULL,
    created_by      VARCHAR(20)  NOT NULL,
    updated_by      VARCHAR(20),
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);
CREATE INDEX idx_kb_name ON t_knowledge_base (name);
COMMENT ON TABLE t_knowledge_base IS '知识库表';

CREATE TABLE t_knowledge_document (
    id               VARCHAR(20)        NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(20)        NOT NULL,
    doc_name         VARCHAR(256)  NOT NULL,
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    chunk_count      INTEGER       DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(16)   NOT NULL,
    mime_type        VARCHAR(128),
    file_size        BIGINT,
    process_mode     VARCHAR(16)   DEFAULT 'chunk',
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    source_type      VARCHAR(16),
    source_location  VARCHAR(1024),
    schedule_enabled SMALLINT,
    schedule_cron    VARCHAR(64),
    ingestion_spec   JSONB,
    pipeline_id      VARCHAR(20),
    created_by       VARCHAR(20)   NOT NULL,
    updated_by       VARCHAR(20),
    create_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_kb_id ON t_knowledge_document (kb_id);
COMMENT ON TABLE t_knowledge_document IS '知识库文档表';

CREATE TABLE t_knowledge_chunk (
    id             VARCHAR(20)      NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(20)      NOT NULL,
    doc_id         VARCHAR(20)      NOT NULL,
    chunk_index    INTEGER     NOT NULL,
    content        TEXT        NOT NULL,
    content_hash   VARCHAR(64),
    char_count     INTEGER,
    token_count    INTEGER,
    embedding_text TEXT,
    enabled        SMALLINT    NOT NULL DEFAULT 1,
    created_by     VARCHAR(20) NOT NULL,
    updated_by     VARCHAR(20),
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_doc_id ON t_knowledge_chunk (doc_id);
COMMENT ON TABLE t_knowledge_chunk IS '知识库文档分块表';

CREATE TABLE t_knowledge_document_chunk_log (
    id                 VARCHAR(20)      NOT NULL PRIMARY KEY,
    doc_id             VARCHAR(20)      NOT NULL,
    status             VARCHAR(16)      NOT NULL,
    process_mode       VARCHAR(16),
    parse_profile      VARCHAR(16),
    pipeline_id        VARCHAR(20),
    extract_duration   BIGINT,
    chunk_duration     BIGINT,
    embed_duration     BIGINT,
    persist_duration   BIGINT,
    total_duration     BIGINT,
    chunk_count        INTEGER,
    error_message      TEXT,
    start_time         TIMESTAMP,
    end_time           TIMESTAMP,
    create_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_id_log ON t_knowledge_document_chunk_log (doc_id);
COMMENT ON TABLE t_knowledge_document_chunk_log IS '知识库文档分块日志表';

CREATE TABLE t_knowledge_document_schedule (
    id                VARCHAR(20)       NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(20)       NOT NULL,
    kb_id             VARCHAR(20)       NOT NULL,
    cron_expr         VARCHAR(64),
    enabled           SMALLINT     DEFAULT 0,
    next_run_time     TIMESTAMP,
    last_run_time     TIMESTAMP,
    last_success_time TIMESTAMP,
    last_status       VARCHAR(16),
    last_error        VARCHAR(512),
    last_etag         VARCHAR(256),
    last_modified     VARCHAR(256),
    last_content_hash VARCHAR(128),
    lock_owner        VARCHAR(128),
    lock_until        TIMESTAMP,
    create_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX idx_next_run ON t_knowledge_document_schedule (next_run_time);
CREATE INDEX idx_lock_until ON t_knowledge_document_schedule (lock_until);
COMMENT ON TABLE t_knowledge_document_schedule IS '知识库文档定时刷新任务表';

CREATE TABLE t_knowledge_document_schedule_exec (
    id            VARCHAR(20)       NOT NULL PRIMARY KEY,
    schedule_id   VARCHAR(20)       NOT NULL,
    doc_id        VARCHAR(20)       NOT NULL,
    kb_id         VARCHAR(20)       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    message       VARCHAR(512),
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    file_name     VARCHAR(512),
    file_size     BIGINT,
    content_hash  VARCHAR(128),
    etag          VARCHAR(256),
    last_modified VARCHAR(256),
    create_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_schedule_time ON t_knowledge_document_schedule_exec (schedule_id, start_time);
CREATE INDEX idx_doc_id_exec ON t_knowledge_document_schedule_exec (doc_id);
COMMENT ON TABLE t_knowledge_document_schedule_exec IS '知识库文档定时刷新执行记录';

-- ============================================
-- RAG Intent & Query Tables
-- ============================================

CREATE TABLE t_intent_node (
    id                    VARCHAR(20)       NOT NULL PRIMARY KEY,
    kb_id                 VARCHAR(20),
    intent_code           VARCHAR(64)  NOT NULL,
    name                  VARCHAR(64)  NOT NULL,
    level                 SMALLINT     NOT NULL,
    parent_code           VARCHAR(64),
    description           VARCHAR(512),
    examples              TEXT,
    collection_name       VARCHAR(128),
    collection_names      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    top_k                 INTEGER,
    mcp_tool_id           VARCHAR(128),
    require_confirm       SMALLINT     NOT NULL DEFAULT 0,
    kind                  SMALLINT     NOT NULL DEFAULT 0,
    prompt_snippet        TEXT,
    prompt_template       TEXT,
    sort_order            INTEGER      NOT NULL DEFAULT 0,
    enabled               SMALLINT     NOT NULL DEFAULT 1,
    create_by             VARCHAR(20),
    update_by             VARCHAR(20),
    create_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_intent_node_mcp_tool ON t_intent_node (mcp_tool_id) WHERE kind = 2 AND deleted = 0;
COMMENT ON TABLE t_intent_node IS '意图树节点配置表';

CREATE TABLE t_query_term_mapping (
    id          VARCHAR(20)       NOT NULL PRIMARY KEY,
    domain      VARCHAR(64),
    source_term VARCHAR(128) NOT NULL,
    target_term VARCHAR(128) NOT NULL,
    match_type  SMALLINT     NOT NULL DEFAULT 1,
    priority    INTEGER      NOT NULL DEFAULT 100,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255),
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_domain ON t_query_term_mapping (domain);
CREATE INDEX idx_source ON t_query_term_mapping (source_term);
COMMENT ON TABLE t_query_term_mapping IS '关键词归一化映射表';

-- ============================================
-- Agent Profile Tables
-- ============================================

CREATE TABLE t_agent_profile (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512),
    avatar      VARCHAR(32),
    builtin     SMALLINT     NOT NULL DEFAULT 0,
    active      SMALLINT     NOT NULL DEFAULT 0,
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
-- 逻辑删除后名称应可重新占用，唯一性只约束未删除的行
CREATE UNIQUE INDEX uk_agent_name ON t_agent_profile (name) WHERE deleted = 0;
CREATE INDEX idx_agent_active ON t_agent_profile (active);
COMMENT ON TABLE t_agent_profile IS '智能体人设配置表';

CREATE TABLE t_agent_prompt (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    agent_id    VARCHAR(20)  NOT NULL,
    slot_key    VARCHAR(64)  NOT NULL,
    content     TEXT,
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_slot UNIQUE (agent_id, slot_key)
);
CREATE INDEX idx_agent_prompt_agent ON t_agent_prompt (agent_id);
COMMENT ON TABLE t_agent_prompt IS '智能体提示词槽位表';

CREATE TABLE t_agent_skill (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    skill_code  VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) NOT NULL,
    content     TEXT         NOT NULL,
    tool_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
-- 逻辑删除后 skill_code 应可重新占用，唯一性只约束未删除的行
CREATE UNIQUE INDEX uk_agent_skill_code ON t_agent_skill (skill_code) WHERE deleted = 0;
CREATE INDEX idx_agent_skill_enabled ON t_agent_skill (enabled, deleted);
COMMENT ON TABLE t_agent_skill IS '智能体技能表';

-- ============================================
-- Agent Engine Tables (v2 ReAct，与 workflow 会话两套分立)
-- ============================================

CREATE TABLE t_agent_conversation (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0
);
-- 会话身份不可复用：逻辑删除后仍保留唯一键，新会话必须使用服务端生成的新 ID
CREATE UNIQUE INDEX uk_agent_conversation_user ON t_agent_conversation (conversation_id, user_id);
CREATE INDEX idx_agent_conv_user_time ON t_agent_conversation (user_id, last_time);
COMMENT ON TABLE t_agent_conversation IS 'Agent 会话列表';

CREATE TABLE t_agent_message (
    id                  VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id     VARCHAR(20) NOT NULL,
    user_id             VARCHAR(20) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             TEXT,
    thinking_content    TEXT,
    blocks              JSONB,
    reply_to_message_id VARCHAR(20),
    message_status      VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT    DEFAULT 0
);
CREATE INDEX idx_agent_msg_conv ON t_agent_message (conversation_id, user_id, create_time);
-- 长期记忆按用户跨会话取待处理消息，按 id 升序
CREATE INDEX idx_agent_msg_user ON t_agent_message (user_id, id);
COMMENT ON TABLE t_agent_message IS 'Agent 消息记录';

CREATE TABLE t_agent_state (
    user_id     VARCHAR(64) NOT NULL,
    session_id  VARCHAR(64) NOT NULL,
    state_key   VARCHAR(64) NOT NULL,
    payload     JSONB,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id, state_key)
);
COMMENT ON TABLE t_agent_state IS 'AgentScope状态存储';

CREATE TABLE t_agent_context_compaction (
    id                   VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id              VARCHAR(20) NOT NULL,
    conversation_id      VARCHAR(20) NOT NULL,
    generation           INTEGER     NOT NULL,
    summary              TEXT,
    material_msg_count   INTEGER     NOT NULL,
    material_chars       INTEGER     NOT NULL,
    summary_chars        INTEGER     NOT NULL,
    context_chars_before INTEGER     NOT NULL,
    context_chars_after  INTEGER     NOT NULL,
    create_time          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_compaction_conv ON t_agent_context_compaction (conversation_id, user_id, create_time);
COMMENT ON TABLE t_agent_context_compaction IS 'Agent 上下文压缩事件，追加型审计日志，应用侧无读路径';

CREATE TABLE t_agent_memory (
    id            VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(20)  NOT NULL,
    content       VARCHAR(500) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,
    invalid_at    TIMESTAMP,
    superseded_by VARCHAR(20),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 部分索引：读路径只查 ACTIVE，失效行不进索引
CREATE INDEX idx_agent_memory_active ON t_agent_memory (user_id) WHERE invalid_at IS NULL;
COMMENT ON TABLE t_agent_memory IS 'Agent长期记忆事实表';

CREATE TABLE t_agent_memory_extraction (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id         VARCHAR(20) NOT NULL,
    conversation_id VARCHAR(20) NOT NULL,
    from_message_id VARCHAR(20) NOT NULL,
    to_message_id   VARCHAR(20) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    trigger_type    VARCHAR(16) NOT NULL,
    decision_count  INTEGER     NOT NULL DEFAULT 0,
    attempt_count   INTEGER     NOT NULL DEFAULT 1,
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settle_time     TIMESTAMP
);
CREATE INDEX idx_agent_memory_extraction_user ON t_agent_memory_extraction (user_id, to_message_id);
-- 部分唯一索引即分布式 claim：同一用户同时只允许一次在飞抽取，记忆只有一份，消费顺序必须是说话先后
CREATE UNIQUE INDEX uk_agent_memory_extraction_processing
    ON t_agent_memory_extraction (user_id) WHERE status = 'PROCESSING';
COMMENT ON TABLE t_agent_memory_extraction IS 'Agent长期记忆抽取台账';

CREATE TABLE t_agent_memory_control (
    user_id     VARCHAR(20) NOT NULL PRIMARY KEY,
    revision    BIGINT      NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_agent_memory_control IS 'Agent长期记忆控制面';

-- ============================================
-- Ingestion Pipeline Tables
-- ============================================

CREATE TABLE t_ingestion_pipeline (
    id          VARCHAR(20)      NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_by  VARCHAR(20) DEFAULT '',
    updated_by  VARCHAR(20) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_ingestion_pipeline IS '摄取流水线表';

CREATE TABLE t_ingestion_pipeline_node (
    id             VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id    VARCHAR(20)      NOT NULL,
    node_id        VARCHAR(20) NOT NULL,
    node_type      VARCHAR(16) NOT NULL,
    next_node_id   VARCHAR(20),
    settings_json  JSONB,
    condition_json JSONB,
    created_by     VARCHAR(20) DEFAULT '',
    updated_by     VARCHAR(20) DEFAULT '',
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted)
);
CREATE INDEX idx_ingestion_pipeline_node_pipeline ON t_ingestion_pipeline_node (pipeline_id);
COMMENT ON TABLE t_ingestion_pipeline_node IS '摄取流水线节点表';

CREATE TABLE t_ingestion_task (
    id               VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id      VARCHAR(20)      NOT NULL,
    source_type      VARCHAR(20) NOT NULL,
    source_location  TEXT,
    source_file_name VARCHAR(255),
    status           VARCHAR(16) NOT NULL,
    chunk_count      INTEGER     DEFAULT 0,
    error_message    TEXT,
    logs_json        JSONB,
    metadata_json    JSONB,
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    created_by       VARCHAR(20) DEFAULT '',
    updated_by       VARCHAR(20) DEFAULT '',
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_pipeline ON t_ingestion_task (pipeline_id);
CREATE INDEX idx_ingestion_task_status ON t_ingestion_task (status);
COMMENT ON TABLE t_ingestion_task IS '摄取任务表';

CREATE TABLE t_ingestion_task_node (
    id            VARCHAR(20)      NOT NULL PRIMARY KEY,
    task_id       VARCHAR(20)      NOT NULL,
    pipeline_id   VARCHAR(20)      NOT NULL,
    node_id       VARCHAR(20) NOT NULL,
    node_type     VARCHAR(16) NOT NULL,
    node_order    INTEGER     NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    message       TEXT,
    error_message TEXT,
    output_json   TEXT,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_node_task ON t_ingestion_task_node (task_id);
CREATE INDEX idx_ingestion_task_node_pipeline ON t_ingestion_task_node (pipeline_id);
CREATE INDEX idx_ingestion_task_node_status ON t_ingestion_task_node (status);
COMMENT ON TABLE t_ingestion_task_node IS '摄取任务节点表';

-- ============================================
-- Vector Storage Table (pgvector)
-- ============================================

CREATE TABLE t_knowledge_vector (
    id              VARCHAR(20) PRIMARY KEY,
    collection_name VARCHAR(64) NOT NULL,
    content         TEXT,
    metadata        JSONB,
    embedding       vector(1536)
);

CREATE INDEX idx_kv_collection_name ON t_knowledge_vector (collection_name);
CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);
COMMENT ON TABLE t_knowledge_vector IS '知识库向量存储表';
COMMENT ON COLUMN t_knowledge_vector.id IS '分块ID';
COMMENT ON COLUMN t_knowledge_vector.collection_name IS '知识库Collection';
COMMENT ON COLUMN t_knowledge_vector.content IS '分块文本内容';
COMMENT ON COLUMN t_knowledge_vector.metadata IS '元数据';
COMMENT ON COLUMN t_knowledge_vector.embedding IS '向量';

-- ============================================
-- Column Comments
-- ============================================

-- t_message_feedback
COMMENT ON COLUMN t_message_feedback.id IS '主键ID';
COMMENT ON COLUMN t_message_feedback.message_id IS '消息ID';
COMMENT ON COLUMN t_message_feedback.conversation_id IS '会话ID';
COMMENT ON COLUMN t_message_feedback.user_id IS '用户ID';
COMMENT ON COLUMN t_message_feedback.vote IS '投票 1：赞 -1：踩';
COMMENT ON COLUMN t_message_feedback.reason IS '反馈原因';
COMMENT ON COLUMN t_message_feedback.comment IS '反馈评论';
COMMENT ON COLUMN t_message_feedback.create_time IS '创建时间';
COMMENT ON COLUMN t_message_feedback.update_time IS '更新时间';
COMMENT ON COLUMN t_message_feedback.deleted IS '是否删除 0：正常 1：删除';

-- t_sample_question
COMMENT ON COLUMN t_sample_question.id IS 'ID';
COMMENT ON COLUMN t_sample_question.title IS '展示标题';
COMMENT ON COLUMN t_sample_question.description IS '描述或提示';
COMMENT ON COLUMN t_sample_question.question IS '示例问题内容';
COMMENT ON COLUMN t_sample_question.create_time IS '创建时间';
COMMENT ON COLUMN t_sample_question.update_time IS '更新时间';
COMMENT ON COLUMN t_sample_question.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_base
COMMENT ON COLUMN t_knowledge_base.id IS '主键 ID';
COMMENT ON COLUMN t_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN t_knowledge_base.embedding_model IS '嵌入模型标识';
COMMENT ON COLUMN t_knowledge_base.collection_name IS 'Collection名称';
COMMENT ON COLUMN t_knowledge_base.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_base.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_base.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_base.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_document
COMMENT ON COLUMN t_knowledge_document.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document.doc_name IS '文档名称';
COMMENT ON COLUMN t_knowledge_document.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_knowledge_document.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document.file_url IS '文件存储路径';
COMMENT ON COLUMN t_knowledge_document.file_type IS '文件类型';
COMMENT ON COLUMN t_knowledge_document.mime_type IS '真实MIME类型';
COMMENT ON COLUMN t_knowledge_document.file_size IS '文件大小（字节）';
COMMENT ON COLUMN t_knowledge_document.process_mode IS '处理模式：chunk/pipeline';
COMMENT ON COLUMN t_knowledge_document.status IS '状态：pending/running/success/failed';
COMMENT ON COLUMN t_knowledge_document.source_type IS '来源类型：file/url';
COMMENT ON COLUMN t_knowledge_document.source_location IS '来源地址';
COMMENT ON COLUMN t_knowledge_document.schedule_enabled IS '是否启用定时刷新';
COMMENT ON COLUMN t_knowledge_document.schedule_cron IS '定时表达式';
COMMENT ON COLUMN t_knowledge_document.ingestion_spec IS '文档级摄取配置：解析档位 + 分块预算';
COMMENT ON COLUMN t_knowledge_document.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_document.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_document.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_document.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_chunk
COMMENT ON COLUMN t_knowledge_chunk.id IS 'ID';
COMMENT ON COLUMN t_knowledge_chunk.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_chunk.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_chunk.chunk_index IS '分块序号';
COMMENT ON COLUMN t_knowledge_chunk.content IS '分块内容';
COMMENT ON COLUMN t_knowledge_chunk.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_chunk.char_count IS '字符数';
COMMENT ON COLUMN t_knowledge_chunk.token_count IS 'Token数';
COMMENT ON COLUMN t_knowledge_chunk.embedding_text IS '向量文本';
COMMENT ON COLUMN t_knowledge_chunk.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_chunk.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_chunk.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_chunk.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_chunk.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_chunk.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_document_chunk_log
COMMENT ON COLUMN t_knowledge_document_chunk_log.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_chunk_log.process_mode IS '处理模式';
COMMENT ON COLUMN t_knowledge_document_chunk_log.parse_profile IS '解析档位';
COMMENT ON COLUMN t_knowledge_document_chunk_log.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.extract_duration IS '提取耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_duration IS '分块耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_duration IS '向量化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.persist_duration IS 'DB持久化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.total_duration IS '总耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document_chunk_log.error_message IS '错误信息';
COMMENT ON COLUMN t_knowledge_document_chunk_log.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.update_time IS '更新时间';

-- t_knowledge_document_schedule
COMMENT ON COLUMN t_knowledge_document_schedule.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule.cron_expr IS 'Cron表达式';
COMMENT ON COLUMN t_knowledge_document_schedule.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_document_schedule.next_run_time IS '下次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_run_time IS '上次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_success_time IS '上次成功时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_status IS '上次状态';
COMMENT ON COLUMN t_knowledge_document_schedule.last_error IS '上次错误';
COMMENT ON COLUMN t_knowledge_document_schedule.last_etag IS '上次ETag';
COMMENT ON COLUMN t_knowledge_document_schedule.last_modified IS '上次修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_content_hash IS '上次内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_owner IS '锁持有者';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_until IS '锁过期时间';
COMMENT ON COLUMN t_knowledge_document_schedule.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule.update_time IS '更新时间';

-- t_knowledge_document_schedule_exec
COMMENT ON COLUMN t_knowledge_document_schedule_exec.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.schedule_id IS '调度ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.message IS '消息';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_name IS '文件名';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_size IS '文件大小';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.etag IS 'ETag';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.last_modified IS '最后修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.update_time IS '更新时间';

-- t_intent_node
COMMENT ON COLUMN t_intent_node.id IS '自增主键';
COMMENT ON COLUMN t_intent_node.kb_id IS '知识库ID';
COMMENT ON COLUMN t_intent_node.intent_code IS '业务唯一标识';
COMMENT ON COLUMN t_intent_node.name IS '展示名称';
COMMENT ON COLUMN t_intent_node.level IS '层级 0：DOMAIN 1：CATEGORY 2：TOPIC';
COMMENT ON COLUMN t_intent_node.parent_code IS '父节点标识';
COMMENT ON COLUMN t_intent_node.description IS '语义描述';
COMMENT ON COLUMN t_intent_node.examples IS '示例问题';
COMMENT ON COLUMN t_intent_node.collection_name IS '兼容旧版本，后续删除';
COMMENT ON COLUMN t_intent_node.collection_names IS '知识库Collection集合';
COMMENT ON COLUMN t_intent_node.top_k IS '知识库检索TopK';
COMMENT ON COLUMN t_intent_node.mcp_tool_id IS 'MCP工具ID';
COMMENT ON COLUMN t_intent_node.require_confirm IS '执行前是否需要用户确认 1：需要 0：不需要';
COMMENT ON COLUMN t_intent_node.kind IS '类型 0：知识库 1：系统交互 2：MCP 工具';
COMMENT ON COLUMN t_intent_node.prompt_snippet IS '提示词片段';
COMMENT ON COLUMN t_intent_node.prompt_template IS '提示词模板';
COMMENT ON COLUMN t_intent_node.sort_order IS '排序字段';
COMMENT ON COLUMN t_intent_node.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_intent_node.create_by IS '创建人';
COMMENT ON COLUMN t_intent_node.update_by IS '修改人';
COMMENT ON COLUMN t_intent_node.create_time IS '创建时间';
COMMENT ON COLUMN t_intent_node.update_time IS '修改时间';
COMMENT ON COLUMN t_intent_node.deleted IS '是否删除 0：正常 1：删除';

-- t_query_term_mapping
COMMENT ON COLUMN t_query_term_mapping.id IS 'ID';
COMMENT ON COLUMN t_query_term_mapping.domain IS '领域';
COMMENT ON COLUMN t_query_term_mapping.source_term IS '源词';
COMMENT ON COLUMN t_query_term_mapping.target_term IS '目标词';
COMMENT ON COLUMN t_query_term_mapping.match_type IS '匹配类型 1：精确 2：模糊';
COMMENT ON COLUMN t_query_term_mapping.priority IS '优先级';
COMMENT ON COLUMN t_query_term_mapping.enabled IS '是否启用';
COMMENT ON COLUMN t_query_term_mapping.remark IS '备注';
COMMENT ON COLUMN t_query_term_mapping.create_by IS '创建人';
COMMENT ON COLUMN t_query_term_mapping.update_by IS '修改人';
COMMENT ON COLUMN t_query_term_mapping.create_time IS '创建时间';
COMMENT ON COLUMN t_query_term_mapping.update_time IS '修改时间';
COMMENT ON COLUMN t_query_term_mapping.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_pipeline
COMMENT ON COLUMN t_ingestion_pipeline.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline.name IS '流水线名称';
COMMENT ON COLUMN t_ingestion_pipeline.description IS '流水线描述';
COMMENT ON COLUMN t_ingestion_pipeline.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_pipeline_node
COMMENT ON COLUMN t_ingestion_pipeline_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_id IS '节点标识(同一流水线内唯一)';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_pipeline_node.next_node_id IS '下一个节点ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.settings_json IS '节点配置JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.condition_json IS '条件JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline_node.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_task
COMMENT ON COLUMN t_ingestion_task.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task.source_type IS '来源类型';
COMMENT ON COLUMN t_ingestion_task.source_location IS '来源地址或URL';
COMMENT ON COLUMN t_ingestion_task.source_file_name IS '原始文件名';
COMMENT ON COLUMN t_ingestion_task.status IS '任务状态';
COMMENT ON COLUMN t_ingestion_task.chunk_count IS '分块数量';
COMMENT ON COLUMN t_ingestion_task.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task.logs_json IS '节点日志JSON';
COMMENT ON COLUMN t_ingestion_task.metadata_json IS '扩展元数据JSON';
COMMENT ON COLUMN t_ingestion_task.started_at IS '开始时间';
COMMENT ON COLUMN t_ingestion_task.completed_at IS '完成时间';
COMMENT ON COLUMN t_ingestion_task.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_task.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_task.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_task_node
COMMENT ON COLUMN t_ingestion_task_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task_node.task_id IS '任务ID';
COMMENT ON COLUMN t_ingestion_task_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task_node.node_id IS '节点标识';
COMMENT ON COLUMN t_ingestion_task_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_task_node.node_order IS '节点顺序';
COMMENT ON COLUMN t_ingestion_task_node.status IS '节点状态';
COMMENT ON COLUMN t_ingestion_task_node.duration_ms IS '执行耗时(毫秒)';
COMMENT ON COLUMN t_ingestion_task_node.message IS '节点消息';
COMMENT ON COLUMN t_ingestion_task_node.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task_node.output_json IS '节点输出JSON(全量)';
COMMENT ON COLUMN t_ingestion_task_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task_node.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_profile
COMMENT ON COLUMN t_agent_profile.id IS '主键ID';
COMMENT ON COLUMN t_agent_profile.name IS '智能体名称，唯一';
COMMENT ON COLUMN t_agent_profile.description IS '智能体描述';
COMMENT ON COLUMN t_agent_profile.avatar IS '头像预设标识，取值由前端预设表定义，认不出时按 id 哈希兜底';
COMMENT ON COLUMN t_agent_profile.builtin IS '是否内置 0：否 1：是。内置智能体不可编辑不可删除，是所有空槽位的回落终点';
COMMENT ON COLUMN t_agent_profile.active IS '是否激活 0：否 1：是。全局仅允许一条为 1';
COMMENT ON COLUMN t_agent_profile.create_by IS '创建人';
COMMENT ON COLUMN t_agent_profile.update_by IS '更新人';
COMMENT ON COLUMN t_agent_profile.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_profile.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_profile.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_prompt
COMMENT ON COLUMN t_agent_prompt.id IS '主键ID';
COMMENT ON COLUMN t_agent_prompt.agent_id IS '所属智能体ID';
COMMENT ON COLUMN t_agent_prompt.slot_key IS '槽位标识，见 AgentPromptSlot 枚举';
COMMENT ON COLUMN t_agent_prompt.content IS '提示词全文，空白视为未配置并回落内置智能体';
COMMENT ON COLUMN t_agent_prompt.create_by IS '创建人';
COMMENT ON COLUMN t_agent_prompt.update_by IS '更新人';
COMMENT ON COLUMN t_agent_prompt.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_prompt.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_prompt.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_skill
COMMENT ON COLUMN t_agent_skill.id IS '主键ID';
COMMENT ON COLUMN t_agent_skill.skill_code IS '技能标识，模型按此名加载正文';
COMMENT ON COLUMN t_agent_skill.name IS '技能展示名';
COMMENT ON COLUMN t_agent_skill.description IS '技能适用场景，随清单一起交给模型判断是否加载';
COMMENT ON COLUMN t_agent_skill.content IS '技能正文 Markdown，模型加载后按此执行';
COMMENT ON COLUMN t_agent_skill.tool_ids IS '加载技能后才解锁的 MCP 工具 ID，取自意图树 MCP 节点';
COMMENT ON COLUMN t_agent_skill.sort_order IS '排序，越小越靠前';
COMMENT ON COLUMN t_agent_skill.enabled IS '是否启用 0：停用 1：启用';
COMMENT ON COLUMN t_agent_skill.create_by IS '创建人';
COMMENT ON COLUMN t_agent_skill.update_by IS '更新人';
COMMENT ON COLUMN t_agent_skill.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_skill.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_skill.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_conversation
COMMENT ON COLUMN t_agent_conversation.id IS '主键ID';
COMMENT ON COLUMN t_agent_conversation.conversation_id IS '会话ID';
COMMENT ON COLUMN t_agent_conversation.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_conversation.title IS '会话标题';
COMMENT ON COLUMN t_agent_conversation.last_time IS '最后活动时间';
COMMENT ON COLUMN t_agent_conversation.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_conversation.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_conversation.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_message
COMMENT ON COLUMN t_agent_message.id IS '主键ID';
COMMENT ON COLUMN t_agent_message.conversation_id IS '会话ID';
COMMENT ON COLUMN t_agent_message.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_message.role IS '角色 user：用户 assistant：助手';
COMMENT ON COLUMN t_agent_message.content IS '消息正文';
COMMENT ON COLUMN t_agent_message.thinking_content IS '思考内容';
COMMENT ON COLUMN t_agent_message.blocks IS '运行轨迹块（reasoning/answer/tool 有序序列），回放还原时间线';
COMMENT ON COLUMN t_agent_message.reply_to_message_id IS '回复的用户消息ID';
COMMENT ON COLUMN t_agent_message.message_status IS '消息终态 NORMAL：正常 INTERRUPTED：用户中断';
COMMENT ON COLUMN t_agent_message.duration_ms IS '本轮 run 的服务端耗时（毫秒），仅 assistant 有值';
COMMENT ON COLUMN t_agent_message.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_message.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_message.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_state
COMMENT ON COLUMN t_agent_state.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_state.session_id IS '会话ID，即 AgentScope 的 sessionId';
COMMENT ON COLUMN t_agent_state.state_key IS '状态键，AgentScope 侧固定传 agent_state';
COMMENT ON COLUMN t_agent_state.payload IS '框架自有编码的状态 JSON，业务侧不解析';
COMMENT ON COLUMN t_agent_state.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_state.update_time IS '更新时间';

-- t_agent_context_compaction
COMMENT ON COLUMN t_agent_context_compaction.id IS '主键ID';
COMMENT ON COLUMN t_agent_context_compaction.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_context_compaction.conversation_id IS '会话ID，即 AgentScope 的 sessionId';
COMMENT ON COLUMN t_agent_context_compaction.generation IS '同一会话内的第几代摘要，从 1 起';
COMMENT ON COLUMN t_agent_context_compaction.summary IS '本代摘要正文，回填进上下文的那一份';
COMMENT ON COLUMN t_agent_context_compaction.material_msg_count IS '被换出的原文消息条数';
COMMENT ON COLUMN t_agent_context_compaction.material_chars IS '被换出的原文字符数';
COMMENT ON COLUMN t_agent_context_compaction.summary_chars IS '摘要正文字符数';
COMMENT ON COLUMN t_agent_context_compaction.context_chars_before IS '压缩前上下文总字符数';
COMMENT ON COLUMN t_agent_context_compaction.context_chars_after IS '压缩后上下文总字符数';
COMMENT ON COLUMN t_agent_context_compaction.create_time IS '创建时间';

-- t_agent_memory
COMMENT ON COLUMN t_agent_memory.id IS '主键ID';
COMMENT ON COLUMN t_agent_memory.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory.content IS '记忆正文';
COMMENT ON COLUMN t_agent_memory.source_type IS '写入来源：FLUSH/BACKGROUND/CONSOLIDATION';
COMMENT ON COLUMN t_agent_memory.invalid_at IS '失效时刻，NULL 即 ACTIVE';
COMMENT ON COLUMN t_agent_memory.superseded_by IS '取代者ID，撤回与清空行留空';
COMMENT ON COLUMN t_agent_memory.create_time IS '创建时间';

-- t_agent_memory_extraction
COMMENT ON COLUMN t_agent_memory_extraction.id IS '主键ID';
COMMENT ON COLUMN t_agent_memory_extraction.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory_extraction.conversation_id IS '触发本批的会话ID';
COMMENT ON COLUMN t_agent_memory_extraction.from_message_id IS '本批首条用户消息ID';
COMMENT ON COLUMN t_agent_memory_extraction.to_message_id IS '本批末条用户消息ID';
COMMENT ON COLUMN t_agent_memory_extraction.status IS '抽取状态：PROCESSING/WRITTEN/NOOP/DROPPED/CONFLICT';
COMMENT ON COLUMN t_agent_memory_extraction.trigger_type IS '触发方：FLUSH/BACKGROUND';
COMMENT ON COLUMN t_agent_memory_extraction.decision_count IS '实际落库的决策条数';
COMMENT ON COLUMN t_agent_memory_extraction.attempt_count IS '第几次尝试，达上限记 DROPPED';
COMMENT ON COLUMN t_agent_memory_extraction.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_memory_extraction.settle_time IS '抽取结束时刻，非终态为空';

-- t_agent_memory_control
COMMENT ON COLUMN t_agent_memory_control.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory_control.revision IS '记忆集版本号，提交期与水位一同双校验';
COMMENT ON COLUMN t_agent_memory_control.create_time IS '建行时刻，兼作抽取下界：更早的历史消息不倒灌';
COMMENT ON COLUMN t_agent_memory_control.update_time IS '更新时间';


-- 星序 MVP 活动信息与邮箱提醒
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

-- Starline official source survey and review registry
CREATE TABLE IF NOT EXISTS t_starline_platform_source (
    id                          VARCHAR(64) PRIMARY KEY,
    platform_name               VARCHAR(160) NOT NULL,
    official_domain              VARCHAR(255) NOT NULL UNIQUE,
    public_accessible            BOOLEAN NOT NULL,
    activity_fields_available    BOOLEAN NOT NULL,
    ticket_time_available        BOOLEAN NOT NULL,
    ticket_status_available      BOOLEAN NOT NULL,
    field_stability              VARCHAR(24) NOT NULL CHECK (field_stability IN ('STABLE', 'PARTIAL', 'UNSTABLE')),
    official_identity_evidence   TEXT NOT NULL,
    update_frequency             VARCHAR(160) NOT NULL,
    access_boundary              TEXT NOT NULL,
    source_status                VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (source_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_time                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_starline_platform_source_status
    ON t_starline_platform_source (source_status, official_domain);

CREATE TABLE IF NOT EXISTS t_starline_source_review (
    id              VARCHAR(64) PRIMARY KEY,
    source_id       VARCHAR(64) NOT NULL REFERENCES t_starline_platform_source(id) ON DELETE CASCADE,
    reviewer_type   VARCHAR(24) NOT NULL CHECK (reviewer_type IN ('PRODUCT', 'ENGINEERING')),
    reviewer_name   VARCHAR(120) NOT NULL,
    passed          BOOLEAN NOT NULL,
    review_note     TEXT,
    reviewed_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_starline_source_reviewer UNIQUE (source_id, reviewer_type)
);

CREATE INDEX IF NOT EXISTS idx_starline_source_review_source
    ON t_starline_source_review (source_id);


-- Offline bad-case analysis
CREATE TABLE IF NOT EXISTS t_bad_case (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    message_id VARCHAR(20) NOT NULL,
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    message_type VARCHAR(16) NOT NULL DEFAULT 'RAG',
    run_id VARCHAR(64),
    legacy_trace_id VARCHAR(64),
    trace_matched_by_fallback SMALLINT NOT NULL DEFAULT 0,
    trace_match_status VARCHAR(32) NOT NULL DEFAULT 'MISSING',
    vote SMALLINT NOT NULL CHECK (vote = -1),
    reason VARCHAR(255),
    comment VARCHAR(1024),
    user_question TEXT,
    assistant_answer TEXT,
    thinking_content TEXT,
    message_status VARCHAR(32),
    sources JSONB,
    retrieved_chunks JSONB,
    trace_nodes JSONB,
    suspicions JSONB,
    evidence_notes JSONB,
    message_create_time TIMESTAMP,
    root_cause VARCHAR(32),
    root_cause_detail VARCHAR(1024),
    fix_action VARCHAR(32),
    fix_ref VARCHAR(128),
    fix_note VARCHAR(1024),
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING','REVIEWED','IGNORED','FIXED')),
    reviewer VARCHAR(64),
    review_time TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_bad_case_src UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_bad_case_status ON t_bad_case (review_status, create_time);
CREATE INDEX IF NOT EXISTS idx_bad_case_run ON t_bad_case (run_id);
CREATE INDEX IF NOT EXISTS idx_bad_case_root ON t_bad_case (root_cause, create_time);
CREATE INDEX IF NOT EXISTS idx_feedback_bad_case_scan ON t_message_feedback (update_time, id)
    WHERE vote=-1 AND deleted=0;
COMMENT ON TABLE t_bad_case IS '点踩离线分析台账；自动线索与人工判定分开，归档保留唯一键墓碑';
COMMENT ON COLUMN t_bad_case.trace_match_status IS 'AGENT_AUDIT/AGENT_BLOCKS/MISSING; legacy rows may contain older values';
COMMENT ON COLUMN t_bad_case.legacy_trace_id IS 'Historical trace identifier retained for old bad-case records; not used by current Agent runtime';
COMMENT ON COLUMN t_bad_case.suspicions IS '规则产生的待核查线索，不是已确认根因';

-- Starline self-hosted Agent audit. Additive and idempotent.
BEGIN;
SET LOCAL lock_timeout = '10s';
CREATE TABLE IF NOT EXISTS t_agent_run (
    id VARCHAR(20) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    parent_run_id VARCHAR(64),
    resume_message_id VARCHAR(20),
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    user_message_id VARCHAR(20),
    assistant_message_id VARCHAR(20),
    question TEXT,
    answer TEXT,
    run_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' CHECK (run_status IN ('RUNNING','NORMAL','INTERRUPTED','FAILED','AWAITING_CONFIRM')),
    capture_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE' CHECK (capture_status IN ('COMPLETE','PARTIAL','DISABLED')),
    round_count INTEGER NOT NULL DEFAULT 0,
    model_call_count INTEGER NOT NULL DEFAULT 0,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT,
    output_tokens BIGINT,
    cached_tokens BIGINT,
    duration_ms BIGINT,
    error_message TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_agent_run_node (
    id VARCHAR(20) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(32) NOT NULL,
    parent_node_id VARCHAR(32),
    round_index INTEGER NOT NULL DEFAULT 0,
    node_type VARCHAR(16) NOT NULL,
    node_name VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    batch_id VARCHAR(64),
    tool_call_id VARCHAR(128),
    duration_source VARCHAR(16),
    input_payload JSONB,
    output_payload JSONB,
    input_tokens BIGINT,
    output_tokens BIGINT,
    cached_tokens BIGINT,
    ttft_ms BIGINT,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    duration_ms BIGINT,
    error_message TEXT,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_run_node UNIQUE(run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation ON t_agent_run(conversation_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_created ON t_agent_run(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON t_agent_run(run_status, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_message ON t_agent_run(assistant_message_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_agent_node_run ON t_agent_run_node(run_id, started_at);
CREATE INDEX IF NOT EXISTS idx_agent_node_type ON t_agent_run_node(node_type, status);
COMMENT ON TABLE t_agent_run IS 'Agent execution audit, keyed by StreamTask taskId';
COMMENT ON TABLE t_agent_run_node IS 'Actual model inputs and tool execution audit; admin only';
COMMIT;
