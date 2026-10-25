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
    private Intake intake = new Intake();
    private java.util.Map<String,Detector> detectors = new java.util.HashMap<>();
    private Behavior behavior = new Behavior();
    private Sampling sampling = new Sampling();
    private AutoReview autoReview = new AutoReview();
    private Alerts alerts = new Alerts();
    @Data public static class Intake {
        private String mode="ON"; private int dailyLimit=500;
        private int perConversationHourlyLimit=5; private int evidenceMaxChars=32000;
        public String getMode(){return normalizeMode(mode);}
    }
    @Data public static class Detector { private String mode="SHADOW";public String getMode(){return normalizeMode(mode);} }
    @Data public static class Behavior {
        private int repeatWindowMinutes=10; private double repeatSimilarityThreshold=.82;
        private int clarificationMinRounds=3; private int noFollowupAfterHours=24;
        private int dailyLimitPerRule=50;
    }
    @Data public static class Sampling {
        private int rateBp=200; private int dailyLimit=50; private int minMessageAgeMinutes=30;
        private int measurementWeeklyLimit=20; private String seed="starline-v1";
    }
    @Data public static class AutoReview {
        private boolean enabled=false; private String tier="FAST"; private String modelId="";
        private int timeoutSeconds=20; private int maxInputChars=24000;
        private int dailyLimit=100; private int maxRetries=2;
    }
    @Data public static class Alerts { private boolean enabled=false; private int windowMinutes=15;private int s2Threshold=3; }
    public static String normalizeMode(String value) {
        if(value==null)return "OFF";
        return switch(value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TRUE","ON" -> "ON";
            case "FALSE","OFF" -> "OFF";
            case "SHADOW" -> "SHADOW";
            default -> "OFF";
        };
    }
    public String detectorMode(String code) {
        if("user-feedback".equals(code))return "ON";
        Detector detector=detectors.get(code);
        return detector==null?(java.util.Set.of("behavior","sampling").contains(code)?"OFF":"SHADOW"):detector.getMode().toUpperCase(java.util.Locale.ROOT);
    }
}
