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
import com.nageoffer.ai.starlineagent.rag.core.vector.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 向量检索通道
 * <p>
 * 在所有启用知识库中执行一次向量检索，候选统一交给下游 RRF 与 Rerank。
 */
@Slf4j
@Component
public class VectorSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final VectorRetrieverService retrieverService;
    private final CollectionParallelRetriever globalRetriever;

    public VectorSearchChannel(VectorRetrieverService retrieverService,
                               SearchChannelProperties properties,
                               Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.globalRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 一条通道一个开关；启用后内部总有一条作用域可走
        return properties.getChannels().getVector().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            RetrievalScope scope = context.getRetrievalScope();
            List<RetrievedChunk> chunks = retrieveGlobal(context, scope);

            long latency = System.currentTimeMillis() - startTime;
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .metadata(Map.of("scope", "global"))
                    .build();

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR;
    }

    /**
     * 全局作用域：跨全部有效库检索
     * 取数深度与其他通道同源、只受 recallBudget 管——候选池上限是 RRF 之后的闸门而非取数目标
     */
    private List<RetrievedChunk> retrieveGlobal(SearchContext context, RetrievalScope scope) {
        if (scope.targetCollections().isEmpty()) {
            log.warn("未找到任何 KB collection，跳过全局检索");
            return List.of();
        }
        String question = context.getMainQuestion();
        List<RetrievedChunk> chunks = retrieveOver(question, retrieverService.embedAndNormalize(question),
                scope.targetCollections(), context.getBudget().recallBudget());

        log.info("向量检索完成（全局），{} 库 {} 条（最高余弦 {}）",
                scope.targetCollections().size(), chunks.size(), ChunkRanking.topScoreOf(chunks));
        return chunks;
    }

    /**
     * 在给定 collection 范围内取一路候选：按相关性降序、条数不超过 budget
     * <p>
     * 后端支持跨库过滤（PG / Milvus 共享库）时一次查询带总预算即可；否则逐库并行 fan-out 兜底，
     * 每库各取 budget 再统一截断——多取是为了拿到真正的全局前 budget 条（哪个库有好料事前不知道），
     * 但截断不能省：省掉它 budget 就从「总量」悄悄变成「每库上限」，补充路名额被放大成 库数 × 名额
     * <p>
     * 排序在截断之前，且后端返回序不能直接信：PG 开了 {@code hnsw.iterative_scan=relaxed_order}，
     * pgvector 在该模式下允许轻微乱序且规划器不补 Sort 节点，先排后截才是取全局最优的前 budget 条
     */
    private List<RetrievedChunk> retrieveOver(String question, float[] queryVector, List<String> collections, int budget) {
        if (collections.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> chunks = retrieverService.supportsGlobalRetrieval()
                ? retrieverService.retrieveByVector(queryVector, RetrieveRequest.builder()
                .collectionNames(collections)
                .query(question)
                .topK(budget)
                .build())
                : globalRetriever.executeParallelRetrieval(question, collections, budget, queryVector);
        List<RetrievedChunk> sorted = ChunkRanking.sortedByScore(chunks);
        return sorted.size() > budget ? List.copyOf(sorted.subList(0, budget)) : sorted;
    }
}
