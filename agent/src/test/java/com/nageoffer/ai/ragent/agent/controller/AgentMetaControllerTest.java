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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMetaVO;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMetaControllerTest {

    private AgentToolCatalog toolCatalog;
    private AgentMetaController controller;

    @BeforeEach
    void setUp() {
        toolCatalog = mock(AgentToolCatalog.class);
        AgentProperties properties = new AgentProperties();
        properties.getChat().setModel("qwen-max");
        controller = new AgentMetaController(properties, toolCatalog);
    }

    @Test
    void shouldNotClaimMcpToolsWhenNoneAvailable() {
        when(toolCatalog.mcpToolCount()).thenReturn(0);

        AgentMetaVO meta = controller.meta().getData();

        // 能力清单说有、mcpConfigured 说没有，两个字段各说各话
        assertThat(meta.capabilities()).containsExactly("react", "knowledge-base");
        assertThat(meta.mcpConfigured()).isFalse();
    }

    @Test
    void shouldClaimMcpToolsWhenAvailable() {
        when(toolCatalog.mcpToolCount()).thenReturn(2);

        AgentMetaVO meta = controller.meta().getData();

        assertThat(meta.capabilities()).containsExactly("react", "knowledge-base", "mcp-tools");
        assertThat(meta.mcpConfigured()).isTrue();
    }
}
