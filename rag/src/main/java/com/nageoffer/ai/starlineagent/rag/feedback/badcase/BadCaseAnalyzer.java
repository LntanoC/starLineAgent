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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BadCaseAnalyzer {
    private final List<BadCaseMessageProvider> providers;
    private final BadCaseRules rules;

    public BadCaseReport analyze(BadCaseRepository.Feedback feedback,long retrieveP95) {
        BadCaseReport r=providers.stream().map(p->p.find(feedback.messageId(),feedback.userId()))
                .flatMap(Optional::stream).findFirst().orElseGet(()->BadCaseReport.builder()
                        .messageId(feedback.messageId()).conversationId(feedback.conversationId()).userId(feedback.userId())
                        .messageType("UNKNOWN").messageStatus("MISSING").sources(List.of()).retrievedChunks(List.of())
                        .evidenceNotes(new ArrayList<>(List.of("原消息已删除或不可访问，未读取其他用户的消息。"))).build());
        r.setVote(feedback.vote()); r.setReason(feedback.reason()); r.setComment(feedback.comment());
        if(r.getEvidenceNotes()==null) r.setEvidenceNotes(new ArrayList<>());
        if(!"AGENT".equals(r.getMessageType())) { r.setTraceMatchStatus("MISSING"); r.setTraceNodes(List.of()); }
        r.setSuspicions(rules.evaluate(r,retrieveP95));
        return r;
    }
}
