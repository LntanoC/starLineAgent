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
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMetaVO;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 引擎探活与身份，前端进入聊天页先拉一次点亮徽标，绝不带密钥
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMetaController {

    private final AgentProperties agentProperties;
    private final AgentToolCatalog toolCatalog;

    @GetMapping("/agent/v1/meta")
    public Result<AgentMetaVO> meta() {
        boolean mcpConfigured = toolCatalog.mcpToolCount() > 0;
        // 能力清单随实况增删，否则会与 mcpConfigured 各说各话，前端只能自己对齐
        List<String> capabilities = new ArrayList<>(List.of("react", "knowledge-base"));
        if (mcpConfigured) {
            capabilities.add("mcp-tools");
        }
        return Results.success(new AgentMetaVO(
                "AgentScope ReAct",
                agentProperties.getChat().getModel(),
                agentProperties.getMaxIters(),
                List.copyOf(capabilities),
                mcpConfigured ? "native + mcp" : "native",
                mcpConfigured));
    }
}
