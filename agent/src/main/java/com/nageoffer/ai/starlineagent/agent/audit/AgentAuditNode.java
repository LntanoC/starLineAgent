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

package com.nageoffer.ai.starlineagent.agent.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import java.util.Date;

@Data
public class AgentAuditNode {
    private String id;
    private String runId;
    private String nodeId;
    private String parentNodeId;
    private Integer roundIndex;
    private String nodeType;
    private String nodeName;
    private String status;
    private Integer sequence;
    private String batchId;
    private String toolCallId;
    private String durationSource;
    @JsonRawValue private String inputPayload;
    @JsonRawValue private String outputPayload;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedTokens;
    private Long ttftMs;
    private Date startedAt;
    private Date endedAt;
    private Long durationMs;
    private String errorMessage;
    private Long revision;
    public AgentAuditNode copy() {
        AgentAuditNode copy = new AgentAuditNode();
        BeanUtils.copyProperties(this, copy);
        return copy;
    }
}
