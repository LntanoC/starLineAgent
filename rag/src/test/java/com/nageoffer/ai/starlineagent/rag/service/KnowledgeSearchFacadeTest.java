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

package com.nageoffer.ai.starlineagent.rag.service;

import com.nageoffer.ai.starlineagent.framework.convention.ChatMessage;
import com.nageoffer.ai.starlineagent.infra.chat.LLMService;
import com.nageoffer.ai.starlineagent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.starlineagent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.starlineagent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.starlineagent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.starlineagent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.starlineagent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.starlineagent.rag.dto.RetrievalContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Knowledge search facade contract. */
class KnowledgeSearchFacadeTest {

    private static final String QUESTION = "报销标准是多少";
    private static final String KB_CONTEXT = """
            <content data-starlineagent-doc-id="doc-a">
            差旅报销上限 800 元
            </content>
            """;

    private final QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
    private final RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
    private final RAGPromptService promptService = mock(RAGPromptService.class);
    private final LLMService llmService = mock(LLMService.class);

    @Test
    void removesInternalDocumentIdsBeforeHandingContextToModel() {
        KnowledgeSearchFacade facade = facade();
        stubRetrievalHit();

        facade.search(QUESTION);

        ArgumentCaptor<PromptContext> promptContext = ArgumentCaptor.forClass(PromptContext.class);
        verify(promptService).buildStructuredMessages(
                promptContext.capture(), anyList(), anyString(), anyList(), anyBoolean());
        String kbContext = promptContext.getValue().getKbContext();
        assertFalse(kbContext.contains("data-starlineagent-doc-id"), "内部文档 ID 不得进入模型可见文本");
        assertFalse(kbContext.contains("ref="), "门面无来源编号，不得注入角标");
        assertTrue(kbContext.contains("差旅报销上限 800 元"), "正文不应被改写规则误伤");
    }

    @Test
    void passesNoHistoryToRewriteNorSynthesis() {
        KnowledgeSearchFacade facade = facade();
        stubRetrievalHit();

        facade.search("它的上限是多少");

        ArgumentCaptor<List<ChatMessage>> rewriteHistory = ArgumentCaptor.forClass(List.class);
        verify(queryRewriteService).rewriteWithSplit(anyString(), rewriteHistory.capture());
        assertTrue(rewriteHistory.getValue().isEmpty(), "改写阶段不得拿到会话历史");

        ArgumentCaptor<List<ChatMessage>> promptHistory = ArgumentCaptor.forClass(List.class);
        verify(promptService).buildStructuredMessages(
                any(PromptContext.class), promptHistory.capture(), anyString(), anyList(), anyBoolean());
        assertTrue(promptHistory.getValue().isEmpty(), "合成阶段只依据本次证据");
    }

    private KnowledgeSearchFacade facade() {
        return new KnowledgeSearchFacade(queryRewriteService, retrievalEngine,
                new CitationContextEnricher(new RAGConfigProperties()), promptService, llmService);
    }

    private void stubRetrievalHit() {
        RewriteResult rewriteResult = new RewriteResult(QUESTION, List.of(QUESTION));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList())).thenReturn(rewriteResult);
        when(retrievalEngine.retrieve(any(RewriteResult.class)))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("差旅报销上限 800 元");
    }
}
