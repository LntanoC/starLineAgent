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

package com.nageoffer.ai.starlineagent.mcp.config;

import com.nageoffer.ai.starlineagent.mcp.executor.common.CurrentDateMcpExecutor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    @Test
    void shouldExposeCurrentDateAndNoRemovedCommerceToolsOverHttp() {
        try (var client = McpClientBuilder.create("starlineagent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .contains("current_date")
                    .doesNotContain("query_order", "search_product", "query_cart");

            CallToolResult date = client.callTool("current_date", Map.of("timezone", "UTC"))
                    .block(Duration.ofSeconds(5));
            assertThat(date.isError()).isFalse();
            assertThat(((TextContent) date.content().get(0)).text()).contains("当前日期");

        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(McpServerConfig.class)
    static class Application {

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateToolSpecification() {
            return new CurrentDateMcpExecutor().currentDateToolSpecification();
        }
    }
}
