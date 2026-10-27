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

package com.nageoffer.ai.starlineagent.rag.core.retrieval.channel;

import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.starlineagent.rag.core.keyword.KeywordRetrieverService;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.RetrievalBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeywordSearchChannelTest {

    @Test
    void searchesAllActiveCollectionsTogether() {
        KeywordRetrieverService retriever = mock(KeywordRetrieverService.class);
        RetrievedChunk chunk = RetrievedChunk.builder().id("chunk-1").text("票务资料").score(2.0F).build();
        when(retriever.search(anyString(), anyList(), anyInt())).thenReturn(List.of(chunk));

        List<RetrievedChunk> result = channel(retriever)
                .search(context(List.of("kb-events", "kb-policy"))).getChunks();

        ArgumentCaptor<List<String>> collections = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
        verify(retriever).search(org.mockito.ArgumentMatchers.eq("演出信息"), collections.capture(), topK.capture());
        assertEquals(List.of("kb-events", "kb-policy"), collections.getValue());
        assertEquals(20, topK.getValue());
        assertEquals(List.of(chunk), result);
    }

    @Test
    void skipsQueryWhenNoActiveCollectionsExist() {
        KeywordRetrieverService retriever = mock(KeywordRetrieverService.class);
        List<RetrievedChunk> result = channel(retriever).search(context(List.of())).getChunks();

        assertTrue(result.isEmpty());
        verify(retriever, never()).search(anyString(), anyList(), anyInt());
    }

    private KeywordSearchChannel channel(KeywordRetrieverService retriever) {
        return new KeywordSearchChannel(retriever, new SearchChannelProperties());
    }

    private SearchContext context(List<String> collections) {
        return SearchContext.builder()
                .originalQuestion("演出信息")
                .budget(new RetrievalBudget(20, 40, 10))
                .retrievalScope(new RetrievalScope(collections))
                .build();
    }
}
