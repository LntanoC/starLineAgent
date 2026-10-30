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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 模式的 MCP 连接和工具发现由 AgentScope 持有
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpClients {

    private final AgentMcpServerRegistry serverRegistry;
    private final Map<String, ServerConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Long> lastConnectionAttemptAt = new ConcurrentHashMap<>();
    private volatile List<AgentMcpServerConfig> configuredServers = List.of();
    private volatile Map<String, RemoteTool> tools = Map.of();
    private static final long RETRY_INTERVAL_MILLIS = 15_000;

    @PostConstruct
    public void init() {
        applyServers(serverRegistry.list());
    }

    /** 启动失败的 MCP 服务在后续请求或管理端同步时重试连接。 */
    public synchronized void refreshConnections() {
        for (AgentMcpServerConfig server : configuredServers) {
            if (!connections.containsKey(server.name())) {
                connect(server);
            }
        }
        rebuildToolIndex();
    }

    /** 应用管理端保存的服务器列表，移除或改址后立即替换旧连接。 */
    public synchronized void applyServers(List<AgentMcpServerConfig> servers) {
        List<AgentMcpServerConfig> desired = servers == null ? List.of() : List.copyOf(servers);
        Map<String, AgentMcpServerConfig> byName = new LinkedHashMap<>();
        for (AgentMcpServerConfig server : desired) {
            byName.put(server.name(), server);
        }
        configuredServers = desired;

        for (Map.Entry<String, ServerConnection> entry : new ArrayList<>(connections.entrySet())) {
            AgentMcpServerConfig replacement = byName.get(entry.getKey());
            if (replacement == null || !Objects.equals(replacement.url(), entry.getValue().url())) {
                connections.remove(entry.getKey());
                lastConnectionAttemptAt.remove(entry.getKey());
                closeConnection(entry.getValue());
            }
        }
        for (AgentMcpServerConfig server : desired) {
            if (!connections.containsKey(server.name())) {
                connect(server);
            }
        }
        rebuildToolIndex();
    }

    private void connect(AgentMcpServerConfig server) {
        String serverName = server.name();
        if (serverName == null || connections.containsKey(serverName)) return;
        long now = System.currentTimeMillis();
        Long lastAttempt = lastConnectionAttemptAt.get(serverName);
        if (lastAttempt != null && now - lastAttempt < RETRY_INTERVAL_MILLIS) return;
        lastConnectionAttemptAt.put(serverName, now);
        McpClientWrapper client = null;
        try {
            String baseUrl = StrUtil.removeSuffix(server.url(), "/");
            String url = baseUrl.endsWith("/mcp") ? baseUrl : baseUrl + "/mcp";
            client = McpClientBuilder.create(serverName)
                    .streamableHttpTransport(url)
                    .buildSync();
            client.initialize().block();
            List<Tool> discovered = client.listTools().block();
            Map<String, RemoteTool> serverTools = new LinkedHashMap<>();
            if (discovered != null) {
                for (Tool tool : discovered) {
                    serverTools.putIfAbsent(tool.name(), new RemoteTool(tool, client));
                }
            }
            connections.put(serverName, new ServerConnection(server.url(), client, Map.copyOf(serverTools)));
            lastConnectionAttemptAt.remove(serverName);
            log.info("AgentScope MCP 服务已连接, server={}, tools={}", serverName,
                    discovered == null ? 0 : discovered.size());
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    log.warn("关闭失败的 MCP 连接时发生异常, server={}", serverName, closeError);
                }
            }
            log.error("AgentScope MCP 服务连接失败, server={}, reason={}", serverName, e.getMessage());
        }
    }

    public RemoteTool get(String toolId) {
        return tools.get(toolId);
    }

    /** 已连接 MCP 服务发现的工具快照，供管理端同步到项目注册表。 */
    public Map<String, RemoteTool> allTools() {
        return tools;
    }

    public boolean isConnected(String serverName) {
        return connections.containsKey(serverName);
    }

    public int toolCount(String serverName) {
        ServerConnection connection = connections.get(serverName);
        return connection == null ? 0 : connection.tools().size();
    }

    private void rebuildToolIndex() {
        Map<String, RemoteTool> refreshed = new LinkedHashMap<>();
        for (AgentMcpServerConfig server : configuredServers) {
            ServerConnection connection = connections.get(server.name());
            if (connection != null) {
                connection.tools().forEach(refreshed::putIfAbsent);
            }
        }
        tools = Map.copyOf(refreshed);
    }

    private void closeConnection(ServerConnection connection) {
        try {
            connection.client().close();
        } catch (Exception e) {
            log.warn("关闭 MCP 连接失败, server={}", connection.client().getName(), e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        connections.values().forEach(this::closeConnection);
        connections.clear();
        tools = Map.of();
    }

    private record ServerConnection(String url, McpClientWrapper client, Map<String, RemoteTool> tools) {
    }

    public record RemoteTool(Tool definition, McpClientWrapper client) {
    }
}
