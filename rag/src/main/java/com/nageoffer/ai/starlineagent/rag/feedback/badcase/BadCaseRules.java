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

package com.nageoffer.ai.starlineagent.rag.feedback.badcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Deterministic bad-case clues adapted to the persisted Agent audit shape. */
@Component
@RequiredArgsConstructor
public class BadCaseRules {
    private final ObjectMapper json;
    private final BadCaseProperties properties;
    private static final Pattern REFUSAL = Pattern.compile("抱歉|我不知道|无法回答|无法提供|没有查询到");
    private static final Pattern ENDING = Pattern.compile("[。！？.!?…）)】\\]」”\"：:；;]$");
    private static final Pattern EMPTY_HINT = Pattern.compile("未查询到|没有找到|未找到|暂无相关|知识库中未");

    public List<String> evaluate(BadCaseReport report, long retrieveP95) {
        List<String> result = new ArrayList<>();
        List<BadCaseReport.TraceNodeSummary> nodes = report.getTraceNodes() == null
                ? List.of() : report.getTraceNodes();
        String answer = safe(report.getAssistantAnswer()).strip();
        boolean isAgent = "AGENT".equals(report.getMessageType());
        boolean isRag = "RAG".equals(report.getMessageType());

        // These status and answer-shape checks are meaningful for both engines.
        if (nodes.stream().anyMatch(n -> "ERROR".equals(n.status()) || "FAILED".equals(n.status()))) {
            result.add("R1 执行链路存在错误，优先核对错误节点与 errorMessage。");
        }
        if ("INTERRUPTED".equals(report.getMessageStatus())) {
            result.add("R2 生成被中断，请核查用户取消或超时。");
        }
        if ("NORMAL".equals(report.getMessageStatus()) && answer.length() >= 200
                && !ENDING.matcher(answer).find() && !answer.endsWith("```")) {
            result.add("R9 回答末尾缺少常见结束标记，存在截断弱信号，请结合 token 上限与流式日志核查。");
        }

        if (isRag) {
            evaluateRagRules(report, nodes, answer, retrieveP95, result);
        } else if (isAgent) {
            evaluateAgentRules(report, nodes, answer, result);
        }

        if (result.isEmpty()) {
            result.add("未命中可用的确定性异常线索，请人工检查回答与证据；这不代表回答正确。");
        }
        return result;
    }

    private void evaluateRagRules(BadCaseReport report, List<BadCaseReport.TraceNodeSummary> nodes,
                                  String answer, long retrieveP95, List<String> result) {
        boolean sources = report.getSources() != null && !report.getSources().isEmpty();
        boolean chunks = report.getRetrievedChunks() != null && !report.getRetrievedChunks().isEmpty();
        boolean retrieve = nodes.stream().anyMatch(n -> "RETRIEVE".equals(n.nodeType()));
        if ("REJECTED".equals(report.getMessageStatus())) {
            result.add("R3 请求被拒绝，优先核查限流及准入条件。");
        }
        if (retrieve && (!sources || !chunks)) {
            result.add("R4 检索链路存在但消息侧来源或 grounding 为空，疑似召回为空或证据未记录；需查看真实检索结果。");
        }
        String evidence = sources ? report.getSources().stream()
                .map(s -> safe(s.getDocName()) + " " + safe(s.getExcerpt())).collect(Collectors.joining(" ")) : "";
        if (sources && evidence.length() >= 80 && tokens(report.getUserQuestion()).size() >= 3
                && overlap(report.getUserQuestion(), evidence) < 0.08) {
            result.add("R5 问题与来源摘要的词面重合较低，疑似召回噪音；词面比较不能判定主题是否相关。");
        }
        if (nodes.stream().anyMatch(n -> "RETRIEVE".equals(n.nodeType()) && n.durationMs() != null
                && n.durationMs() > retrieveP95)) {
            result.add("R6 检索节点耗时超过近 7 天 P95/配置兜底阈值 " + retrieveP95 + " ms，请核查检索性能。");
        }
        if (sources && answer.length() >= 80 && tokens(evidence).size() >= 10
                && nodes.stream().anyMatch(n -> "LLM".equals(n.nodeType())) && overlap(evidence, answer) < 0.06) {
            result.add("R7 回答与来源摘要重合较低，需人工核对忠实性；摘要不代表模型完整上下文，不能据此认定幻觉。");
        }
        if (sources && REFUSAL.matcher(answer).find()) {
            result.add("R8 回答含拒答措辞但存在来源，请检查依据是否足够及提示词是否过严。");
        }
        for (var node : nodes) {
            if (!"REWRITE".equals(node.nodeType()) || node.extraData() == null) continue;
            String rewritten = rewritten(node.extraData());
            if (rewritten != null && tokens(report.getUserQuestion()).size() >= 3
                    && overlap(report.getUserQuestion(), rewritten) < 0.1) {
                result.add("R10 改写结果与原问题词面重合较低，疑似语义漂移，需人工比对。");
                break;
            }
        }
        if (!retrieve) {
            addEvidenceNote(report, "未记录 RETRIEVE 节点，无法据此判断是否召回为空。");
        }
        if (nodes.stream().anyMatch(n -> "REWRITE".equals(n.nodeType())
                && (n.extraData() == null || n.extraData().isBlank()))) {
            addEvidenceNote(report, "改写节点缺少结果正文，R10 无法完成语义漂移检查。");
        }
    }

    private void evaluateAgentRules(BadCaseReport report, List<BadCaseReport.TraceNodeSummary> nodes,
                                    String answer, List<String> result) {
        List<BadCaseReport.TraceNodeSummary> searchNodes = nodes.stream()
                .filter(n -> "TOOL".equals(n.nodeType()) && "search_knowledge".equals(n.nodeName())).toList();

        if (properties.isEnableNoToolCallRule() && Integer.valueOf(0).equals(report.getToolCallCount())
                && answer.length() >= 100 && !REFUSAL.matcher(answer).find()
                && !answer.contains("通用知识") && !answer.contains("未查询")) {
            result.add("A1 本轮未调用任何工具却给出具体回答，需核对是否存在应检索未检索；若属通用知识问答可忽略。该规则误报较高，只能作为线索，不能作为结论。");
        }

        for (var node : searchNodes) {
            if ("ERROR".equals(node.status()) || "FAILED".equals(node.status())) {
                result.add("A2 知识检索工具执行失败（status=" + node.status()
                        + "），回答可能是在无资料情况下生成，优先核查检索异常原因。");
                break;
            }
        }

        List<String> searchOutputs = searchNodes.stream()
                .filter(n -> !"ERROR".equals(n.status()) && !"FAILED".equals(n.status()))
                .map(n -> extractToolOutput(n.extraData())).filter(Objects::nonNull).toList();
        String searchOutput = String.join(" ", searchOutputs);
        if (!searchOutput.isBlank() && searchOutput.length() >= 100
                && tokens(report.getUserQuestion()).size() >= 3) {
            double questionOverlap = overlap(report.getUserQuestion(), searchOutput);
            if (questionOverlap < properties.getRetrieveOverlapMin()) {
                result.add("A3 检索结果与问题的词面重合仅 " + pct(questionOverlap) + "，低于阈值 "
                        + pct(properties.getRetrieveOverlapMin())
                        + "，疑似召回跑偏；词面比较不能判定主题是否相关，需人工核对。");
            }
            if (answer.length() >= 80) {
                double answerOverlap = overlap(answer, searchOutput);
                if (answerOverlap < properties.getAnswerOverlapMin()) {
                    result.add("A4 回答与检索资料的词面重合仅 " + pct(answerOverlap) + "，低于阈值 "
                            + pct(properties.getAnswerOverlapMin())
                            + "，疑似未依据资料作答；词面比较不能判定语义或幻觉，需人工核对忠实性。");
                }
            }
        }

        searchNodes.stream().filter(n -> n.durationMs() != null
                        && n.durationMs() > properties.getToolSlowThresholdMs())
                .max(Comparator.comparing(BadCaseReport.TraceNodeSummary::durationMs))
                .ifPresent(n -> result.add("A5 知识检索耗时 " + n.durationMs() + " ms，超过阈值 "
                        + properties.getToolSlowThresholdMs() + " ms，请核查检索性能。"));

        if (report.getRoundCount() != null && report.getRoundCount() > properties.getMaxRounds()) {
            result.add("A7 本轮共 " + report.getRoundCount() + " 个模型轮次，超过 " + properties.getMaxRounds()
                    + "，疑似反复试错未收敛，请核查工具返回是否可用。");
        }
        Map<String, Long> toolCounts = nodes.stream().filter(n -> "TOOL".equals(n.nodeType()))
                .filter(n -> n.nodeName() != null)
                .collect(Collectors.groupingBy(BadCaseReport.TraceNodeSummary::nodeName, LinkedHashMap::new,
                        Collectors.counting()));
        toolCounts.entrySet().stream().filter(e -> e.getKey() != null && e.getValue() > properties.getMaxSameToolCalls())
                .max(Map.Entry.comparingByValue()).ifPresent(e -> result.add("A7 同一工具「" + e.getKey()
                        + "」在一轮内被调用 " + e.getValue() + " 次，疑似反复调用未得到有效结果。"));

        boolean emptyResult = searchNodes.stream().anyMatch(n -> ("DONE".equals(n.status()) || "SUCCESS".equals(n.status()))
                && isEmptySearchResult(extractToolOutput(n.extraData())));
        if (emptyResult) {
            result.add("A8 检索执行成功但返回内容为空或含“未找到”提示，说明知识库可能未覆盖该问题。");
        }

        boolean completeCapture = "COMPLETE".equals(report.getCaptureStatus());
        boolean missingInput = searchNodes.stream().anyMatch(n -> n.inputData() == null || n.inputData().isBlank());
        if (searchNodes.isEmpty() || !completeCapture || missingInput) {
            addEvidenceNote(report, "工具入参不可用或运行审计采集不完整，A6 未执行；请核对 Agent 运行审计。");
        } else {
            for (var node : searchNodes) {
                String query = extractQuery(node.inputData());
                if (query == null || tokens(report.getUserQuestion()).size() < 3) continue;
                double queryOverlap = overlap(report.getUserQuestion(), query);
                if (queryOverlap < 0.1) {
                    result.add("A6 检索入参与原问题词面重合仅 " + pct(queryOverlap)
                            + "，疑似模型生成 query 时发生语义漂移；词面比较不能判定语义相关性，需人工比对。");
                    break;
                }
            }
        }
    }

    private String extractQuery(String inputData) {
        JsonNode data = parsePayloadData(inputData);
        if (data == null || !data.isObject()) return null;
        JsonNode query = data.path("query");
        if (!query.isTextual() && data.path("input").isObject()) query = data.path("input").path("query");
        return query.isTextual() && !query.asText().isBlank() ? query.asText() : null;
    }

    private String extractToolOutput(String outputData) {
        if (outputData == null || outputData.isBlank()) return null;
        try {
            JsonNode root = json.readTree(outputData);
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data.isTextual()) return data.asText();
            JsonNode result = data.path("result");
            if (result.isTextual()) return result.asText();
            JsonNode output = data.path("output");
            if (output.isTextual()) return output.asText();
            if (output.isArray()) {
                List<String> texts = new ArrayList<>();
                output.forEach(block -> {
                    JsonNode text = block.path("text");
                    if (text.isTextual()) texts.add(text.asText());
                    else if (block.path("result").isTextual()) texts.add(block.path("result").asText());
                });
                return String.join(" ", texts);
            }
            return null;
        } catch (Exception ignored) {
            return outputData;
        }
    }

    private JsonNode parsePayloadData(String payload) {
        try {
            JsonNode root = json.readTree(payload);
            return root != null && root.has("data") ? root.get("data") : root;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isEmptySearchResult(String output) {
        return output == null || output.isBlank() || EMPTY_HINT.matcher(output).find();
    }

    private void addEvidenceNote(BadCaseReport report, String note) {
        if (report.getEvidenceNotes() == null) report.setEvidenceNotes(new ArrayList<>());
        report.getEvidenceNotes().add(note);
    }

    private String rewritten(String data) {
        try {
            JsonNode node = json.readTree(data);
            for (String key : List.of("rewrittenQuery", "rewrittenQuestion", "rewritten_query", "rewrittenQueries")) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull()) return value.isTextual() ? value.asText() : value.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    static Set<String> tokens(String text) {
        String value = safe(text).toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        for (String part : value.split("[^\\p{IsHan}a-z0-9]+")) {
            if (part.matches("[a-z0-9]{3,}")) tokens.add(part);
            else if (part.matches("[\\p{IsHan}]+")) {
                for (int i = 0; i < part.length() - 1; i++) tokens.add(part.substring(i, i + 2));
            }
        }
        tokens.removeAll(Set.of("什么", "怎么", "如何", "请问", "帮我", "一下", "可以", "这个", "那个"));
        return tokens;
    }

    static double overlap(String query, String evidence) {
        Set<String> queryTokens = tokens(query), evidenceTokens = tokens(evidence);
        if (queryTokens.isEmpty()) return 1;
        return queryTokens.stream().filter(evidenceTokens::contains).count() / (double) queryTokens.size();
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
