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

import com.nageoffer.ai.starlineagent.framework.convention.GroundingChunk;
import com.nageoffer.ai.starlineagent.framework.convention.SourceRef;
import lombok.*;
import java.util.Date;
import java.util.List;

/** 反馈发生时的分析快照；suspicions 是待人工核查的线索。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadCaseReport {
    private String messageId;
    private String conversationId;
    private String userId;
    private String messageType;
    private String runId;
    private boolean traceMatchedByFallback;
    private String traceMatchStatus;
    private Integer vote;
    private String reason;
    private String comment;
    private String userQuestion;
    private String assistantAnswer;
    private String thinkingContent;
    private String messageStatus;
    private String captureStatus;
    private Integer roundCount;
    private Integer toolCallCount;
    private List<SourceRef> sources;
    private List<GroundingChunk> retrievedChunks;
    private List<TraceNodeSummary> traceNodes;
    private List<String> suspicions;
    private List<String> evidenceNotes;
    private Date messageCreateTime;

    public record TraceNodeSummary(String nodeId, String parentNodeId, Integer depth,
                                   String nodeType, String nodeName, String status, Long durationMs,
                                   String errorMessage, Date startTime, String extraData,
                                   String inputData) {}
}
