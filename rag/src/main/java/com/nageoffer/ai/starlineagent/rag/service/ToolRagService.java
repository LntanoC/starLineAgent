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

package com.nageoffer.ai.starlineagent.rag.service;

import com.nageoffer.ai.starlineagent.rag.config.ToolRagProperties;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.service.impl.McpToolVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRagService {

    private static final Set<String> STOPWORDS = Set.of("什么", "怎么", "如何", "请问", "帮我", "一下", "可以",
            "最近", "帮忙", "请", "查询", "查一下", "给我", "这个", "那个", "有关", "相关");

    private final McpToolRegistryService registry;
    private final McpToolVectorService vectorService;
    private final ToolRagProperties properties;

    public RecallResult recall(String question, Collection<String> skillProtectedToolIds) {
        List<McpToolDO> active = registry.listDispatchable();
        if (active.isEmpty()) {
            return new RecallResult(List.of(), List.of(), false, false);
        }

        List<McpToolDO> residents = active.stream()
                .filter(tool -> Integer.valueOf(1).equals(tool.getResident()))
                .toList();
        Set<String> residentIds = residents.stream().map(McpToolDO::getToolId).collect(Collectors.toSet());
        if (!properties.isEnabled() || question == null || question.isBlank()) {
            List<ScoredTool> all = active.stream().filter(tool -> !residentIds.contains(tool.getToolId()))
                    .map(tool -> new ScoredTool(tool, 1.0, "all-tools"))
                    .toList();
            return new RecallResult(residents, all, false, false);
        }

        List<McpToolDO> candidates = active.stream()
                .filter(tool -> !residentIds.contains(tool.getToolId()))
                .toList();
        if (candidates.isEmpty()) {
            return new RecallResult(residents, List.of(), true, false);
        }

        Map<String, Double> semantic = Map.of();
        boolean vectorFallback = false;
        if (properties.isVectorEnabled()) {
            try {
                semantic = vectorService.cosineScores(question, candidates);
            } catch (Exception e) {
                vectorFallback = true;
                log.warn("MCP 工具语义召回失败，本轮降级为关键词召回, reason={}", e.getClass().getSimpleName());
            }
        }

        Set<String> tokens = tokenize(question);
        Map<String, Double> keyword = new LinkedHashMap<>();
        for (McpToolDO tool : candidates) {
            double score = keywordScore(tokens, tool);
            if (score > 0) {
                keyword.put(tool.getToolId(), score);
            }
        }

        List<ScoredTool> ranked = new ArrayList<>();
        for (McpToolDO tool : candidates) {
            Double semanticScore = semantic.get(tool.getToolId());
            double keywordScore = keyword.getOrDefault(tool.getToolId(), 0.0);
            double score;
            if (semanticScore == null) {
                score = keywordScore;
            } else {
                double weights = properties.getSemanticWeight() + properties.getKeywordWeight();
                score = weights <= 0 ? semanticScore
                        : (semanticScore * properties.getSemanticWeight()
                        + keywordScore * properties.getKeywordWeight()) / weights;
            }
            if (score >= properties.getRecallMinScore()) {
                ranked.add(new ScoredTool(tool, score, semanticScore == null ? "keyword" : "hybrid"));
            }
        }
        ranked.sort(Comparator.comparingDouble(ScoredTool::score).reversed()
                .thenComparing(candidate -> candidate.tool().getSortOrder() == null
                        ? 900 : candidate.tool().getSortOrder())
                .thenComparing(candidate -> candidate.tool().getToolId()));

        int topK = Math.max(1, Math.min(properties.getRecallTopK(), 20));
        List<ScoredTool> recalled = ranked.stream().limit(topK).toList();
        Set<String> recalledIds = recalled.stream().map(hit -> hit.tool().getToolId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (skillProtectedToolIds != null) {
            for (McpToolDO tool : active) {
                if (skillProtectedToolIds.contains(tool.getToolId())
                        && !residentIds.contains(tool.getToolId()) && recalledIds.add(tool.getToolId())) {
                    recalled = append(recalled, new ScoredTool(tool, 1.0, "skill-protected"));
                }
            }
        }

        // Empty recall deliberately falls back to residents only. Registering every schema here
        // contradicts the small-context goal and turns unrelated messages into an all-tools menu.
        if (recalled.isEmpty()) {
            log.info("MCP 工具未召回候选，保留常驻工具, candidateCount={}, residentCount={}",
                    candidates.size(), residents.size());
        } else {
            log.info("MCP 工具召回完成, candidateCount={}, residentCount={}, recalledCount={}, vectorFallback={}",
                    candidates.size(), residents.size(), recalled.size(), vectorFallback);
        }
        return new RecallResult(residents, recalled, true, vectorFallback);
    }

    public RecallResult recallAll() {
        List<McpToolDO> active = registry.listDispatchable();
        List<McpToolDO> residents = active.stream()
                .filter(tool -> Integer.valueOf(1).equals(tool.getResident())).toList();
        Set<String> residentsIds = residents.stream().map(McpToolDO::getToolId).collect(Collectors.toSet());
        List<ScoredTool> rest = active.stream().filter(tool -> !residentsIds.contains(tool.getToolId()))
                .map(tool -> new ScoredTool(tool, 1, "confirmation-resume")).toList();
        return new RecallResult(residents, rest, false, false);
    }

    public RecallResult recallForTest(String question) {
        return recall(question, List.of());
    }

    private List<ScoredTool> append(List<ScoredTool> source, ScoredTool item) {
        List<ScoredTool> result = new ArrayList<>(source);
        result.add(item);
        return List.copyOf(result);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+")) {
            if (part.matches("[a-z0-9]{2,}")) {
                tokens.add(part);
            } else if (part.matches("[\\p{IsHan}]+")) {
                String cleaned = part;
                for (String stop : STOPWORDS) {
                    cleaned = cleaned.replace(stop, " ");
                }
                for (String segment : cleaned.split("\\s+")) {
                    for (int i = 0; i + 1 < segment.length(); i++) {
                        tokens.add(segment.substring(i, i + 2));
                    }
                }
            }
        }
        return tokens;
    }

    private double keywordScore(Set<String> tokens, McpToolDO tool) {
        if (tokens.isEmpty()) {
            return 0;
        }
        Set<String> nameTokens = tokenize(tool.getToolId() + " " + tool.getDisplayName());
        Set<String> descriptionTokens = tokenize(tool.getDescription());
        long nameHits = tokens.stream().filter(nameTokens::contains).count();
        long descriptionHits = tokens.stream().filter(descriptionTokens::contains).count();
        double denominator = Math.max(1, tokens.size());
        return Math.min(1.0, (nameHits * 1.5 + descriptionHits * 0.35) / denominator);
    }

    public record ScoredTool(McpToolDO tool, double score, String source) {
    }

    public record RecallResult(List<McpToolDO> residents, List<ScoredTool> recalled,
                               boolean usedRecall, boolean vectorFallback) {
        public RecallResult {
            residents = List.copyOf(residents);
            recalled = List.copyOf(recalled);
        }

        public List<McpToolDO> selectedTools() {
            List<McpToolDO> result = new ArrayList<>(residents);
            Set<String> seen = new HashSet<>();
            residents.forEach(tool -> seen.add(tool.getToolId()));
            for (ScoredTool item : recalled) {
                if (seen.add(item.tool().getToolId())) {
                    result.add(item.tool());
                }
            }
            return List.copyOf(result);
        }
    }
}
