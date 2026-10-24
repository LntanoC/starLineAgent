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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BadCaseRulesTest {
    private final BadCaseProperties properties = new BadCaseProperties();
    private final BadCaseRules rules = new BadCaseRules(new ObjectMapper(), properties);

    private BadCaseReport report(String type) {
        return BadCaseReport.builder().messageType(type).messageStatus("NORMAL")
                .userQuestion("查询薛之谦上海演唱会开票时间")
                .assistantAnswer("开票时间待公布。").sources(List.of()).retrievedChunks(List.of()).traceNodes(List.of())
                .evidenceNotes(new ArrayList<>()).build();
    }

    private BadCaseReport.TraceNodeSummary searchNode(String status, Long duration, String output, String input) {
        return new BadCaseReport.TraceNodeSummary("n", null, 1, "TOOL", "search_knowledge", status,
                duration, null, null, output, input);
    }

    @Test
    void agentWithNoSourcesDoesNotRunWorkflowRulesOrEnableHighFalsePositiveRuleByDefault() {
        var report = report("AGENT");
        report.setToolCallCount(0);
        report.setAssistantAnswer("这是一段足够具体的回答".repeat(12));
        assertThat(rules.evaluate(report, 3000)).noneMatch(v -> v.startsWith("R4") || v.startsWith("R7") || v.startsWith("A1"));
    }

    @Test
    void missingTraceDoesNotProveEmptyRetrieval() {
        assertThat(rules.evaluate(report("RAG"), 3000)).noneMatch(v -> v.startsWith("R4"));
    }

    @Test
    void errorInterruptedAndSlowRetrievalRemainSeparateRagSignals() {
        BadCaseReport report = report("RAG");
        report.setMessageStatus("INTERRUPTED");
        report.setTraceNodes(List.of(new BadCaseReport.TraceNodeSummary("n", null, 0, "RETRIEVE", "检索",
                "ERROR", 4001L, "timeout", null, null, null)));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("R1")).anyMatch(v -> v.startsWith("R2"))
                .anyMatch(v -> v.startsWith("R4")).anyMatch(v -> v.startsWith("R6"));
    }

    @Test
    void malformedRewriteEvidenceDoesNotBreakAnalysis() {
        BadCaseReport report = report("RAG");
        report.setTraceNodes(List.of(new BadCaseReport.TraceNodeSummary("n", null, 0, "REWRITE", "改写",
                "SUCCESS", 10L, null, null, "not-json", null)));
        assertThatCode(() -> rules.evaluate(report, 3000)).doesNotThrowAnyException();
    }

    @Test
    void normalShortAnswerWithoutFullStopDoesNotCreateTruncationAlarm() {
        BadCaseReport report = report("AGENT");
        report.setAssistantAnswer("待公布");
        assertThat(rules.evaluate(report, 3000)).noneMatch(v -> v.startsWith("R9"));
    }

    @Test
    void agentFailedKnowledgeSearchTriggersA2() {
        BadCaseReport report = report("AGENT");
        report.setTraceNodes(List.of(searchNode("ERROR", 50L, "检索异常", "{\"data\":{\"query\":\"薛之谦上海演唱会\"}}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A2"));
    }

    @Test
    void unrelatedKnowledgeSearchOutputTriggersA3() {
        BadCaseReport report = report("AGENT");
        report.setTraceNodes(List.of(searchNode("SUCCESS", 500L,
                "天气预报显示今天晴朗，气温适宜，适合户外活动。".repeat(10),
                "{\"data\":{\"query\":\"薛之谦上海演唱会开票时间\"}}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A3"));
    }

    @Test
    void answerUnrelatedToLongSearchOutputTriggersA4() {
        BadCaseReport report = report("AGENT");
        report.setAssistantAnswer("欧洲古典音乐发展经历漫长历史，交响乐团通常由多种乐器共同演奏。".repeat(6));
        report.setTraceNodes(List.of(searchNode("SUCCESS", 500L,
                "天气预报显示今天晴朗，气温适宜，适合户外活动。".repeat(12),
                "{\"data\":{\"query\":\"薛之谦上海演唱会开票时间\"}}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A4"));
    }

    @Test
    void slowKnowledgeSearchTriggersA5() {
        BadCaseReport report = report("AGENT");
        report.setTraceNodes(List.of(searchNode("SUCCESS", 5000L, "检索结果", "{}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A5"));
    }

    @Test
    void rewrittenKnowledgeSearchQueryTriggersA6AndMissingInputIsExplained() {
        BadCaseReport report = report("AGENT");
        report.setCaptureStatus("COMPLETE");
        report.setTraceNodes(List.of(searchNode("SUCCESS", 500L, "检索结果",
                "{\"data\":{\"query\":\"北京天气预报明天温度\"}}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A6"));

        BadCaseReport partial = report("AGENT");
        partial.setCaptureStatus("PARTIAL");
        partial.setTraceNodes(List.of(searchNode("SUCCESS", 500L, "检索结果", null)));
        rules.evaluate(partial, 3000);
        assertThat(partial.getEvidenceNotes()).anyMatch(v -> v.contains("A6 未执行"));
    }

    @Test
    void tooManyRoundsAndRepeatedToolCallsTriggerA7() {
        BadCaseReport report = report("AGENT");
        report.setRoundCount(8);
        report.setTraceNodes(List.of(
                searchNode("SUCCESS", 500L, "结果", "{}"), searchNode("SUCCESS", 500L, "结果", "{}"),
                searchNode("SUCCESS", 500L, "结果", "{}"), searchNode("SUCCESS", 500L, "结果", "{}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A7") && v.contains("模型轮次"))
                .anyMatch(v -> v.startsWith("A7") && v.contains("同一工具"));
    }

    @Test
    void successfulEmptyKnowledgeSearchTriggersA8() {
        BadCaseReport report = report("AGENT");
        report.setTraceNodes(List.of(searchNode("SUCCESS", 500L, "知识库中未找到相关内容", "{}")));
        assertThat(rules.evaluate(report, 3000)).anyMatch(v -> v.startsWith("A8"));
    }

    @Test
    void normalGroundedAgentAnswerDoesNotTriggerAgentRules() {
        String evidence = "薛之谦上海演唱会开票时间以官方票务页面公布为准，演出地点和开票状态请查询官方公告。".repeat(5);
        String answer = "薛之谦上海演唱会开票时间以官方票务页面公布为准，演出地点和开票状态请查询官方公告。".repeat(2);
        BadCaseReport report = report("AGENT");
        report.setAssistantAnswer(answer);
        report.setCaptureStatus("COMPLETE");
        report.setRoundCount(2);
        report.setToolCallCount(1);
        report.setTraceNodes(List.of(searchNode("SUCCESS", 500L, evidence,
                "{\"data\":{\"query\":\"薛之谦上海演唱会开票时间\"}}")));
        assertThat(rules.evaluate(report, 3000)).noneMatch(v -> v.startsWith("A"));
    }
}
