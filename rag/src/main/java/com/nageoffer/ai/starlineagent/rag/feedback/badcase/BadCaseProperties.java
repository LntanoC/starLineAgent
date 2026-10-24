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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.feedback.bad-case")
public class BadCaseProperties {
    private boolean enabled = true;
    private long scanDelayMs = 300000;
    private int lookbackMinutes = 60;
    private int batchSize = 50;
    private int retentionDays = 90;
    private long retrieveSlowThresholdMs = 3000;
    /** Agent 检索结果与问题的最低词面重合度；低于该值仅作为人工复核线索。 */
    private double retrieveOverlapMin = 0.15;
    /** Agent 回答与检索结果的最低词面重合度；低于该值仅作为人工复核线索。 */
    private double answerOverlapMin = 0.25;
    /** Agent 知识检索工具耗时告警阈值。 */
    private long toolSlowThresholdMs = 3000;
    /** Agent 单次运行允许的模型轮次预警线。 */
    private int maxRounds = 6;
    /** Agent 单次运行同名工具调用预警线。 */
    private int maxSameToolCalls = 3;
    /** 是否启用误报较高的“未调用工具却给出具体回答”线索。 */
    private boolean enableNoToolCallRule = false;
}
