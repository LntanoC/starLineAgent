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
import com.nageoffer.ai.starlineagent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.service.McpToolRegistryService;
import com.nageoffer.ai.starlineagent.rag.service.ToolRagService;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the visible MCP tool set from the current run's active task step. */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolRoutingService {

    private final ToolRagService toolRagService;
    private final McpToolRegistryService toolRegistry;
    private final AgentSkillRegistry skillRegistry;

    public String currentQuery(RuntimeContext context) {
        if (context == null) {
            return null;
        }
        Object active = context.get(AgentRuntimeContextKeys.ACTIVE_TOOL_ROUTING_QUERY);
        if (active instanceof String query && StrUtil.isNotBlank(query)) {
            return query;
        }
        Object initial = context.get(AgentRuntimeContextKeys.INITIAL_TOOL_ROUTING_QUERY);
        return initial instanceof String query && StrUtil.isNotBlank(query) ? query : null;
    }

    public ToolRagService.RecallResult resolve(RuntimeContext context) {
        String query = currentQuery(context);
        if (context != null) {
            Object cached = context.get(AgentRuntimeContextKeys.TOOL_ROUTING_RECALL);
            if (cached instanceof CachedRecall recall && java.util.Objects.equals(recall.query(), query)) {
                return recall.result();
            }
        }

        List<String> skillToolIds = skillRegistry.listEnabled().stream()
                .flatMap(skill -> skill.toolIds().stream()).distinct().toList();
        ToolRagService.RecallResult result = StrUtil.isBlank(query)
                ? toolRagService.recallAll()
                : toolRagService.recall(query, skillToolIds);
        if (context != null) {
            context.put(AgentRuntimeContextKeys.TOOL_ROUTING_RECALL, new CachedRecall(query, result));
        }
        return result;
    }

    public ToolRagService.RecallResult updateQuery(RuntimeContext context, String query) {
        if (context == null) {
            throw new IllegalStateException("当前运行上下文不可用，无法刷新工具目录");
        }
        String normalized = StrUtil.trimToNull(query);
        if (normalized == null || normalized.length() > 1000) {
            throw new IllegalArgumentException("子目标不能为空且不能超过 1000 个字符");
        }
        context.put(AgentRuntimeContextKeys.ACTIVE_TOOL_ROUTING_QUERY, normalized);
        return resolve(context);
    }

    public Set<String> dispatchableToolIds(RuntimeContext context) {
        if (context != null) {
            Object cached = context.get(AgentRuntimeContextKeys.DISPATCHABLE_TOOL_IDS);
            if (cached instanceof Set<?>) {
                return ((Set<?>) cached).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toUnmodifiableSet());
            }
        }
        Set<String> ids = toolRegistry.listDispatchable().stream()
                .map(McpToolDO::getToolId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> result = Set.copyOf(ids);
        if (context != null) {
            context.put(AgentRuntimeContextKeys.DISPATCHABLE_TOOL_IDS, result);
        }
        return result;
    }

    private record CachedRecall(String query, ToolRagService.RecallResult result) {
    }
}
