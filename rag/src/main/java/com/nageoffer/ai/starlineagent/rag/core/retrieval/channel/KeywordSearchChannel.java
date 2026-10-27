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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.starlineagent.rag.core.keyword.KeywordRetrieverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 关键词检索通道
 * <p>
 * 基于全文检索引擎（ES）的 BM25 关键词召回，与向量通道互补：擅长精确词、编号、专有名词等
 * 仅当开启 ES 关键词检索（rag.keyword.type=es）时才注册，
 * 否则整个通道不存在，引擎自动退化为纯向量检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分；检索范围读引擎解析好的作用域，与向量通道同源
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class KeywordSearchChannel implements SearchChannel {

    private final KeywordRetrieverService keywordRetriever;
    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "KeywordSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeyword().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            RetrievalScope scope = context.getRetrievalScope();
            List<String> collections = scope.targetCollections();
            if (CollUtil.isEmpty(collections)) {
                log.info("关键词检索未解析到目标知识库，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            String question = context.getMainQuestion();
            List<RetrievedChunk> chunks = keywordRetriever.search(
                    question, collections, context.getBudget().recallBudget());

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词全局检索完成，覆盖 {} 库 {} 条，耗时 {}ms",
                    collections.size(), chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }
}
