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

package com.nageoffer.ai.starlineagent.rag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证唯一支持的 Agent 入口及旧配置的拒绝行为。
 */
class OrchestrationPropertiesTest {

    @Test
    void defaultTypeIsAgent() {
        assertEquals(OrchestrationMode.AGENT, new OrchestrationProperties().getMode());
    }

    @Test
    void acceptsAgentIgnoringCaseAndPadding() {
        assertEquals(OrchestrationMode.AGENT, of("agent").getMode());
        assertEquals(OrchestrationMode.AGENT, of("AGENT").getMode());
        assertEquals(OrchestrationMode.AGENT, of(" agent ").getMode());
    }

    @Test
    void rejectsRetiredWorkflowAndUnknownModes() {
        assertThrows(IllegalStateException.class, () -> of("workflow").getMode());
        assertThrows(IllegalStateException.class, () -> of("unknown").getMode());
    }

    private OrchestrationProperties of(String type) {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.setType(type);
        return properties;
    }
}
