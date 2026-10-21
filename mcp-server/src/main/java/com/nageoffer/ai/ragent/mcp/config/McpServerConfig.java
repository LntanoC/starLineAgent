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

package com.nageoffer.ai.ragent.mcp.config;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 配置类
 */
@Configuration
public class McpServerConfig {

    @Bean
    public HttpServletStreamableServerTransportProvider transportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp");
    }

    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                   List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        requireReadOnlyHint(toolSpecs);
        return McpServer.sync(transportProvider)
                .serverInfo("ragent-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
    }

    /**
     * 工具必须自报是读还是写：调用方按 readOnlyHint 决定要不要拦下来让用户确认
     */
    static void requireReadOnlyHint(List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        List<String> undeclared = toolSpecs.stream()
                .filter(spec -> spec.tool().annotations() == null
                        || spec.tool().annotations().readOnlyHint() == null)
                .map(spec -> spec.tool().name())
                .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "以下 MCP 工具未声明 readOnlyHint，请在 annotations 里显式写明只读或写操作: " + undeclared);
        }
    }
}
