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
import com.nageoffer.ai.starlineagent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.starlineagent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.starlineagent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSearchChannelTest {

    @Test
    void searchesAllActiveCollectionsInOneGlobalQuery() {
        VectorRetrieverService retriever = mock(VectorRetrieverService.class);
        when(retriever.embedAndNormalize("演出信息")).thenReturn(new float[]{0.6F, 0.8F});
        when(retriever.supportsGlobalRetrieval()).thenReturn(true);
        when(retriever.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenReturn(List.of(
                chunk("lower", 0.4F), chunk("higher", 0.9F)));

        SearchContext context = context(List.of("kb-events", "kb-policy"));
        List<RetrievedChunk> result = new VectorSearchChannel(
                retriever, new SearchChannelProperties(), Runnable::run).search(context).getChunks();

        ArgumentCaptor<RetrieveRequest> request = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retriever).retrieveByVector(any(float[].class), request.capture());
        assertEquals(List.of("kb-events", "kb-policy"), request.getValue().getEffectiveCollectionNames());
        assertEquals(20, request.getValue().getTopK());
        assertEquals(List.of("higher", "lower"), result.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    void skipsRetrievalWhenThereAreNoActiveCollections() {
        VectorRetrieverService retriever = mock(VectorRetrieverService.class);
        List<RetrievedChunk> result = new VectorSearchChannel(
                retriever, new SearchChannelProperties(), Runnable::run)
                .search(context(List.of())).getChunks();

        assertTrue(result.isEmpty());
        verify(retriever, never()).retrieveByVector(any(float[].class), any(RetrieveRequest.class));
    }

    private SearchContext context(List<String> collections) {
        return SearchContext.builder()
                .originalQuestion("演出信息")
                .budget(new RetrievalBudget(20, 40, 10))
                .retrievalScope(new RetrievalScope(collections))
                .build();
    }

    private RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }
}
