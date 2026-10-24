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
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BadCaseAnalyzerTest {
    private final BadCaseRules rules=new BadCaseRules(new ObjectMapper(),new BadCaseProperties());
    private final BadCaseRepository.Feedback feedback=new BadCaseRepository.Feedback("m","c","u",-1,null,null);
    @Test void singleAgentProviderPreservesExactAudit() {
        var report=BadCaseReport.builder().messageId("m").userId("u").conversationId("c").messageType("AGENT")
                .runId("exact-run").traceMatchStatus("AGENT_AUDIT").assistantAnswer("答案")
                .sources(List.of()).retrievedChunks(List.of()).traceNodes(List.of()).build();
        var actual=new BadCaseAnalyzer(List.of((id,user)->Optional.of(report)),rules).analyze(feedback,1000);
        assertThat(actual.getRunId()).isEqualTo("exact-run");
        assertThat(actual.getTraceMatchStatus()).isEqualTo("AGENT_AUDIT");
    }
    @Test void missingOwnedMessageReturnsExplicitMissing() {
        var actual=new BadCaseAnalyzer(List.of((id,user)->Optional.empty()),rules).analyze(feedback,1000);
        assertThat(actual.getMessageStatus()).isEqualTo("MISSING");
        assertThat(actual.getAssistantAnswer()).isNull();
    }
    @Test void absentProvidersDoNotThrowOrInventMessage() {
        var actual=new BadCaseAnalyzer(List.of(),rules).analyze(feedback,1000);
        assertThat(actual.getMessageType()).isEqualTo("UNKNOWN");
        assertThat(actual.getTraceMatchStatus()).isEqualTo("MISSING");
    }
}
