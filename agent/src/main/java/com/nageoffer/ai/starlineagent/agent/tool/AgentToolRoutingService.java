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
import java.util.HashSet;
import java.util.Locale;
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

    public ToolSearchUpdate updateQuery(RuntimeContext context, String query) {
        if (context == null) {
            throw new IllegalStateException("当前运行上下文不可用，无法刷新工具目录");
        }
        String normalized = StrUtil.trimToNull(query);
        if (normalized == null || normalized.length() > 1000) {
            throw new IllegalArgumentException("子目标不能为空且不能超过 1000 个字符");
        }
        synchronized (context) {
            String previousQuery = currentQuery(context);
            ToolRagService.RecallResult previousRecall = resolve(context);
            SearchProgress progress = searchProgress(context, previousQuery, candidateFingerprint(previousRecall));
            if (progress.exhausted()) {
                return new ToolSearchUpdate(ToolSearchStatus.EXHAUSTED, previousRecall);
            }

            context.put(AgentRuntimeContextKeys.ACTIVE_TOOL_ROUTING_QUERY, normalized);
            ToolRagService.RecallResult recall = resolve(context);
            ToolSearchStatus status = progress.record(normalized, candidateFingerprint(recall));
            if (status == ToolSearchStatus.EXHAUSTED) {
                context.put(AgentRuntimeContextKeys.TOOL_SEARCH_EXHAUSTED, true);
            }
            return new ToolSearchUpdate(status, recall);
        }
    }

    private SearchProgress searchProgress(RuntimeContext context, String query, String candidates) {
        Object value = context.get(AgentRuntimeContextKeys.TOOL_SEARCH_PROGRESS);
        if (value instanceof SearchProgress progress) {
            return progress;
        }
        SearchProgress progress = new SearchProgress(query, candidates);
        context.put(AgentRuntimeContextKeys.TOOL_SEARCH_PROGRESS, progress);
        return progress;
    }

    private static String candidateFingerprint(ToolRagService.RecallResult recall) {
        // 常驻工具本来就始终可见，不参与进展判断；这里只比较 ToolRAG 新召回的候选。
        return recall.recalled().stream()
                .map(ToolRagService.ScoredTool::tool)
                .map(McpToolDO::getToolId)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n"));
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

    public enum ToolSearchStatus {
        UPDATED,
        NO_NEW_CANDIDATES,
        EXHAUSTED
    }

    public record ToolSearchUpdate(ToolSearchStatus status, ToolRagService.RecallResult recall) {
    }

    /**
     * 状态只放在单次运行的 RuntimeContext 中；重复 query、重复候选集或空候选都算本轮无进展。
     */
    private static final class SearchProgress {

        private final Set<String> seenCandidateSets = new HashSet<>();
        private String lastQuery;
        private int consecutiveNoProgress;
        private boolean exhausted;

        private SearchProgress(String query, String candidates) {
            lastQuery = normalizeQuery(query);
            seenCandidateSets.add(candidates);
        }

        private ToolSearchStatus record(String query, String candidates) {
            String normalizedQuery = normalizeQuery(query);
            boolean noProgress = normalizedQuery.equals(lastQuery)
                    || candidates.isEmpty()
                    || !seenCandidateSets.add(candidates);
            lastQuery = normalizedQuery;
            if (!noProgress) {
                consecutiveNoProgress = 0;
                return ToolSearchStatus.UPDATED;
            }
            if (++consecutiveNoProgress >= 2) {
                exhausted = true;
                return ToolSearchStatus.EXHAUSTED;
            }
            return ToolSearchStatus.NO_NEW_CANDIDATES;
        }

        private boolean exhausted() {
            return exhausted;
        }

        private static String normalizeQuery(String query) {
            return query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }
}
