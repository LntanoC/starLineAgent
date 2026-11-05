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

package com.nageoffer.ai.starlineagent.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.starlineagent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.rag.service.McpToolRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;

/** Authenticated continuation of an event search; never accepts an arbitrary tool name. */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class EventSearchController {
    private final AgentMcpClients clients;
    private final McpToolRegistryService registry;
    private final ObjectMapper mapper;
    @PostMapping("/starline/event-search")
    public Result<JsonNode> search(@RequestBody Map<String, Object> request) {
        StpUtil.checkLogin();
        if (request.size() > 7 || !Set.of("artist", "city", "window", "includeEnded", "pageSize", "cursor", "freshness").containsAll(request.keySet()))
            throw new IllegalArgumentException("演出查询参数不合法");
        var remote = clients.get("search_artist_events");
        if (remote == null || !registry.enabledById().containsKey("search_artist_events")) throw new IllegalStateException("演出查询暂不可用");
        var result = remote.client().callTool("search_artist_events", request, Map.of()).block(Duration.ofSeconds(12));
        if (result == null || Boolean.TRUE.equals(result.isError())) throw new IllegalArgumentException("分页已过期或查询暂不可用，请重新查询");
        try {
            var text = result.content().stream().filter(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent)
                    .map(c -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) c).text()).findFirst().orElseThrow();
            return Results.success(mapper.readTree(text));
        } catch (java.io.IOException error) { throw new IllegalStateException("演出查询响应无法解析", error); }
    }
}
