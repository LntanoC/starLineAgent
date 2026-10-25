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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式的 MCP 连接和工具发现由 AgentScope 持有
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpClients {

    private final AgentMcpProperties properties;
    private final Map<String, RemoteTool> tools = new ConcurrentHashMap<>();
    private final List<McpClientWrapper> clients = new CopyOnWriteArrayList<>();
    private final Set<String> connectedServers = ConcurrentHashMap.newKeySet();
    private final Set<String> connectingServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastConnectionAttemptAt = new ConcurrentHashMap<>();
    private static final long RETRY_INTERVAL_MILLIS = 15_000;

    @PostConstruct
    public void init() {
        refreshConnections();
    }

    /** 启动失败的 MCP 服务在后续请求或管理端同步时重试连接。 */
    public void refreshConnections() {
        List<AgentMcpProperties.ServerConfig> servers = properties.getServers();
        if (servers == null) return;
        for (AgentMcpProperties.ServerConfig server : servers) {
            if (server.getName() != null && !connectedServers.contains(server.getName())) {
                connect(server);
            }
        }
    }

    private void connect(AgentMcpProperties.ServerConfig server) {
        String serverName = server.getName();
        if (serverName == null || connectedServers.contains(serverName)) return;
        long now = System.currentTimeMillis();
        Long lastAttempt = lastConnectionAttemptAt.putIfAbsent(serverName, now);
        if (lastAttempt != null) {
            if (now - lastAttempt < RETRY_INTERVAL_MILLIS) return;
            if (!lastConnectionAttemptAt.replace(serverName, lastAttempt, now)) return;
        }
        if (!connectingServers.add(serverName)) return;
        McpClientWrapper client = null;
        try {
            String baseUrl = StrUtil.removeSuffix(server.getUrl(), "/");
            String url = baseUrl.endsWith("/mcp") ? baseUrl : baseUrl + "/mcp";
            client = McpClientBuilder.create(server.getName())
                    .streamableHttpTransport(url)
                    .buildSync();
            client.initialize().block();
            List<Tool> discovered = client.listTools().block();
            clients.add(client);
            connectedServers.add(serverName);
            if (discovered != null) {
                for (Tool tool : discovered) {
                    RemoteTool existing = tools.putIfAbsent(tool.name(), new RemoteTool(tool, client));
                    if (existing != null) {
                        log.warn("MCP 工具重名，保留先连接的服务, toolId={}, server={}", tool.name(), server.getName());
                    }
                }
            }
            log.info("AgentScope MCP 服务已连接, server={}, tools={}", server.getName(),
                    discovered == null ? 0 : discovered.size());
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    log.warn("关闭失败的 MCP 连接时发生异常, server={}", server.getName(), closeError);
                }
            }
            log.error("AgentScope MCP 服务连接失败, server={}, reason={}", server.getName(), e.getMessage());
        } finally {
            connectingServers.remove(serverName);
        }
    }

    public RemoteTool get(String toolId) {
        return tools.get(toolId);
    }

    /** 已连接 MCP 服务发现的工具快照，供管理端同步到项目注册表。 */
    public Map<String, RemoteTool> allTools() {
        return Map.copyOf(tools);
    }

    @PreDestroy
    public void close() {
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 连接失败, server={}", client.getName(), e);
            }
        }
    }

    public record RemoteTool(Tool definition, McpClientWrapper client) {
    }
}
