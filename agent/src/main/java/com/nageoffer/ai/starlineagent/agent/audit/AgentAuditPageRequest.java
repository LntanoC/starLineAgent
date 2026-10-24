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

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

@Data
public class AgentAuditPageRequest {
    private long current=1;
    private long size=20;
    private String runId;
    private String conversationId;
    private String messageId;
    private String runStatus;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime beginTime;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime endTime;
    private Boolean slowOnly;
    private Long minInputTokens;
}
