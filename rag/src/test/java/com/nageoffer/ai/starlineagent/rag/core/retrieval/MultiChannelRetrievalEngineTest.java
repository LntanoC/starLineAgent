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
import com.nageoffer.ai.starlineagent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineTest {

    @Test
    void givesEveryChannelTheActiveKnowledgeBaseCollections() {
        RetrievedChunk chunk = RetrievedChunk.builder().id("chunk-1").text("资料").build();
        KbCollectionProvider kbCollectionProvider = mock(KbCollectionProvider.class);
        when(kbCollectionProvider.listActiveCollections()).thenReturn(List.of("kb-a", "kb-b"));

        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn("vector");
        when(channel.getType()).thenReturn(SearchChannelType.VECTOR);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenReturn(SearchChannelResult.builder()
                .channelName("vector")
                .channelType(SearchChannelType.VECTOR)
                .chunks(List.of(chunk))
                .build());

        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                List.of(channel), List.of(), kbCollectionProvider, Runnable::run, new SearchChannelProperties());
        KnowledgeRetrievalResult result = engine.retrieveKnowledgeChannels("演出信息", RetrievalBudget.uniform(10));

        assertEquals(List.of(chunk), result.chunks());
        ArgumentCaptor<SearchContext> context = ArgumentCaptor.forClass(SearchContext.class);
        verify(channel).search(context.capture());
        assertEquals(List.of("kb-a", "kb-b"), context.getValue().getRetrievalScope().targetCollections());
    }
}
