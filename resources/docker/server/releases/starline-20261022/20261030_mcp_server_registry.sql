BEGIN;

CREATE TABLE IF NOT EXISTS t_agent_mcp_server (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    server_name VARCHAR(64)  NOT NULL,
    url         VARCHAR(512) NOT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_mcp_server_name
    ON t_agent_mcp_server (server_name) WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_agent_mcp_server_deleted
    ON t_agent_mcp_server (deleted, server_name);

COMMENT ON TABLE t_agent_mcp_server IS 'Agent 可管理的 MCP 服务连接配置';

COMMIT;
