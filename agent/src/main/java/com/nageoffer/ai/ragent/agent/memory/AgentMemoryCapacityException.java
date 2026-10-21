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

package com.nageoffer.ai.ragent.agent.memory;

import lombok.Getter;

/**
 * 合并与淘汰都用尽仍装不下，抛它回滚事务；专用类型避免与链路上的 IllegalStateException 混淆
 */
@Getter
class AgentMemoryCapacityException extends RuntimeException {

    private final int projectedChars;

    private final int maxChars;

    AgentMemoryCapacityException(String extractionId, int projectedChars, int maxChars) {
        super("长期记忆容量装不下, extractionId: " + extractionId
                + ", 预演字符: " + projectedChars + ", 上限: " + maxChars);
        this.projectedChars = projectedChars;
        this.maxChars = maxChars;
    }
}
