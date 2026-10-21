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

package com.nageoffer.ai.ragent.rag.core.guidance;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmbiguityLLMCheckerTest {

    private final LLMService llmService = mock(LLMService.class);
    private final AmbiguityLLMChecker checker = new AmbiguityLLMChecker(
            llmService, new PromptTemplateLoader(new DefaultResourceLoader()));

    @Test
    void returnsTrueWhenLlmReportsAmbiguous() {
        when(llmService.chat(any(), any())).thenReturn("{\"ambiguous\": true, \"reason\": \"两个系统都可能\"}");

        assertTrue(checker.checkAmbiguity("数据安全怎么做", ranked()));
    }

    @Test
    void returnsFalseWhenLlmReportsUnambiguous() {
        when(llmService.chat(any(), any())).thenReturn("```json\n{\"ambiguous\": false, \"reason\": \"要求对比\"}\n```");

        assertFalse(checker.checkAmbiguity("两个系统有什么区别", ranked()));
    }

    @Test
    void fallsBackToFalseOnNonJsonResponse() {
        when(llmService.chat(any(), any())).thenReturn("我认为存在歧义");

        assertFalse(checker.checkAmbiguity("数据安全怎么做", ranked()));
    }

    @Test
    void fallsBackToFalseWhenAmbiguousFieldMissing() {
        when(llmService.chat(any(), any())).thenReturn("{\"reason\": \"无法判断\"}");

        assertFalse(checker.checkAmbiguity("数据安全怎么做", ranked()));
    }

    @Test
    void fallsBackToFalseWhenLlmThrows() {
        when(llmService.chat(any(), any())).thenThrow(new IllegalStateException("模型超时"));

        assertFalse(checker.checkAmbiguity("数据安全怎么做", ranked()));
    }

    @Test
    void sendsIdFullPathAndDescriptionToLlm() {
        when(llmService.chat(any(), any())).thenReturn("{\"ambiguous\": false}");

        checker.checkAmbiguity("数据安全怎么做", ranked());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(request.capture(), any(Tier.class));
        String prompt = request.getValue().getMessages().get(0).getContent();
        assertTrue(prompt.contains("数据安全怎么做"));
        assertTrue(prompt.contains("oa-sec"));
        assertTrue(prompt.contains("业务系统 > OA系统 > 数据安全"));
        assertTrue(prompt.contains("OA 系统的数据安全规范"));
        assertTrue(prompt.contains("ins-sec"));
        assertTrue(prompt.contains("业务系统 > 保险系统 > 数据安全"));
        assertTrue(prompt.contains("0.90"));
    }

    private static List<NodeScore> ranked() {
        IntentNode oaSecurity = IntentNode.builder()
                .id("oa-sec")
                .name("数据安全")
                .description("OA 系统的数据安全规范")
                .fullPath("业务系统 > OA系统 > 数据安全")
                .build();
        IntentNode insuranceSecurity = IntentNode.builder()
                .id("ins-sec")
                .name("数据安全")
                .fullPath("业务系统 > 保险系统 > 数据安全")
                .build();
        return List.of(new NodeScore(oaSecurity, 0.9D), new NodeScore(insuranceSecurity, 0.86D));
    }
}
