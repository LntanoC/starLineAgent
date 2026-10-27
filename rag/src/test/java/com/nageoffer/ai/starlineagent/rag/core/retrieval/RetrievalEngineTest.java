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

package com.nageoffer.ai.starlineagent.rag.core.retrieval;

import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.starlineagent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.starlineagent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.starlineagent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.starlineagent.rag.dto.RetrievalContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineTest {

    @Test
    void retrievesEachSubQuestionAcrossActiveKnowledgeBases() {
        RetrievedChunk chunkA = chunk("a", "A资料");
        RetrievedChunk chunkB = chunk("b", "B资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(anyString(), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(chunkA)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(chunkB)));
        when(contextFormatter.formatKbContext(any(), anyInt()))
                .thenReturn("A上下文", "B上下文");

        RetrievalContext result = engine(multiChannel, contextFormatter)
                .retrieve(new RewriteResult("主问题", List.of("子问题一", "子问题二")));

        assertEquals(List.of(chunkA, chunkB), result.getChunks());
        assertEquals(2, occurrences(result.getKbContext(), "<sub_question"));
        assertEquals(2, occurrences(result.getKbContext(), "上下文"));
        verify(multiChannel).retrieveKnowledgeChannels("子问题一", new RetrievalBudget(20, 40, 10));
        verify(multiChannel).retrieveKnowledgeChannels("子问题二", new RetrievalBudget(20, 40, 10));
    }

    @Test
    void fallsBackToRewrittenQuestionWhenNoSubQuestionsExist() {
        RetrievedChunk chunk = chunk("a", "知识资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(anyString(), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(chunk)));
        when(contextFormatter.formatKbContext(any(), anyInt())).thenReturn("格式化资料");

        RetrievalContext result = engine(multiChannel, contextFormatter)
                .retrieve(new RewriteResult("改写后的问题", List.of()));

        assertEquals("格式化资料", result.getKbContext());
        assertEquals(List.of(chunk), result.getChunks());
        verify(multiChannel).retrieveKnowledgeChannels("改写后的问题", new RetrievalBudget(20, 40, 10));
    }

    @Test
    void emptyRewriteReturnsWithoutRetrieving() {
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        RetrievalContext result = engine(multiChannel, mock(ContextFormatter.class))
                .retrieve(new RewriteResult(" ", List.of()));

        assertFalse(result.hasKb());
        verify(multiChannel, never()).retrieveKnowledgeChannels(anyString(), any());
    }

    private RetrievalEngine engine(MultiChannelRetrievalEngine multiChannel, ContextFormatter contextFormatter) {
        return new RetrievalEngine(new SearchChannelProperties(), contextFormatter,
                mock(PromptTemplateLoader.class), multiChannel, Runnable::run);
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }

    private int occurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
