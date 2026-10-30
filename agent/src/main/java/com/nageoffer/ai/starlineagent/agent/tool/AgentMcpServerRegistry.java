/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.starlineagent.agent.tool;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMcpServerRegistry {

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final JdbcTemplate jdbcTemplate;
    private final AgentMcpProperties properties;

    @PostConstruct
    public void seedConfiguredServersOnce() {
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_agent_mcp_server", Long.class);
        if (rows == null || rows > 0 || properties.getServers() == null) return;
        for (AgentMcpProperties.ServerConfig configured : properties.getServers()) {
            if (configured.getName() == null || configured.getUrl() == null) continue;
            String name = validateName(configured.getName());
            String url = validateUrl(configured.getUrl());
            jdbcTemplate.update("""
                    INSERT INTO t_agent_mcp_server (id, server_name, url)
                    VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, IdUtil.getSnowflakeNextIdStr(), name, url);
        }
    }

    public List<AgentMcpServerConfig> list() {
        return jdbcTemplate.query("""
                SELECT id, server_name, url
                FROM t_agent_mcp_server
                WHERE deleted = 0
                ORDER BY server_name
                """, (rs, rowNum) -> new AgentMcpServerConfig(
                rs.getString("id"), rs.getString("server_name"), rs.getString("url")));
    }

    public AgentMcpServerConfig create(String name, String url) {
        String safeName = validateName(name);
        String safeUrl = validateUrl(url);
        String id = IdUtil.getSnowflakeNextIdStr();
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_agent_mcp_server (id, server_name, url)
                    VALUES (?, ?, ?)
                    """, id, safeName, safeUrl);
        } catch (DuplicateKeyException e) {
            throw new ClientException("MCP 服务名称已存在：" + safeName);
        }
        return new AgentMcpServerConfig(id, safeName, safeUrl);
    }

    public AgentMcpServerConfig update(String id, String name, String url) {
        String safeName = validateName(name);
        String safeUrl = validateUrl(url);
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE t_agent_mcp_server
                    SET server_name = ?, url = ?, update_time = CURRENT_TIMESTAMP
                    WHERE id = ? AND deleted = 0
                    """, safeName, safeUrl, id);
            if (updated == 0) throw new ClientException("MCP 服务不存在或已删除");
        } catch (DuplicateKeyException e) {
            throw new ClientException("MCP 服务名称已存在：" + safeName);
        }
        return new AgentMcpServerConfig(id, safeName, safeUrl);
    }

    public void delete(String id) {
        int deleted = jdbcTemplate.update("""
                UPDATE t_agent_mcp_server
                SET deleted = 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND deleted = 0
                """, id);
        if (deleted == 0) throw new ClientException("MCP 服务不存在或已删除");
    }

    private String validateName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (!SERVER_NAME.matcher(normalized).matches()) {
            throw new ClientException("服务名称限 64 位以内，且只能以字母或数字开头并包含字母、数字、点、下划线或短横线");
        }
        return normalized;
    }

    private String validateUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 512) throw new ClientException("MCP 服务地址不能超过 512 个字符");
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ClientException("MCP 服务地址必须是无凭据的 HTTP 或 HTTPS URL");
            }
        } catch (IllegalArgumentException e) {
            throw new ClientException("MCP 服务地址格式无效");
        }
        return normalized;
    }
}
