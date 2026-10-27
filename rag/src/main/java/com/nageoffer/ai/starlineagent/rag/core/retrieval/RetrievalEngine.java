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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.starlineagent.framework.trace.RagTraceNode;
import com.nageoffer.ai.starlineagent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.starlineagent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.starlineagent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.starlineagent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.starlineagent.rag.dto.KbResult;
import com.nageoffer.ai.starlineagent.rag.dto.RetrievalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.starlineagent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

/**
 * 检索引擎：对改写后的主问题及拆分问题执行全局多通道检索，并组装回答上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;

    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(RewriteResult rewriteResult) {
        if (rewriteResult == null || StrUtil.isBlank(rewriteResult.rewrittenQuestion())) {
            return RetrievalContext.builder().build();
        }

        List<String> questions = CollUtil.isEmpty(rewriteResult.subQuestions())
                ? List.of(rewriteResult.rewrittenQuestion())
                : rewriteResult.subQuestions().stream().filter(StrUtil::isNotBlank).toList();
        if (questions.isEmpty()) {
            questions = List.of(rewriteResult.rewrittenQuestion());
        }

        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget budget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        List<CompletableFuture<SubQuestionContext>> tasks = questions.stream()
                .map(question -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return buildSubQuestionContext(question, budget);
                    } catch (Exception e) {
                        log.error("子问题上下文构建失败，降级为空上下文，question：{}", question, e);
                        return new SubQuestionContext(question, "", List.of());
                    }
                }, ragContextExecutor))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream().map(CompletableFuture::join).toList();

        String kbContext;
        if (contexts.size() == 1) {
            kbContext = StrUtil.emptyIfNull(contexts.get(0).kbContext()).trim();
        } else {
            StringBuilder builder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                if (StrUtil.isNotBlank(context.kbContext())) {
                    appendSection(builder, ++globalIndex, context.question(), context.kbContext());
                }
            }
            kbContext = builder.toString().trim();
        }

        Map<String, RetrievedChunk> uniqueChunks = new LinkedHashMap<>();
        contexts.stream()
                .flatMap(context -> context.chunks().stream())
                .forEach(chunk -> uniqueChunks.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));

        return RetrievalContext.builder()
                .kbContext(kbContext)
                .chunks(List.copyOf(uniqueChunks.values()))
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(String question, RetrievalBudget budget) {
        KnowledgeRetrievalResult result = multiChannelRetrievalEngine.retrieveKnowledgeChannels(question, budget);
        List<RetrievedChunk> chunks = result.chunks();
        String formattedContext = contextFormatter.formatKbContext(chunks, budget.contextTopK());
        return new SubQuestionContext(question, formattedContext, chunks);
    }

    private void appendSection(StringBuilder builder, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "sub-question-kb-wrapper", Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    private record SubQuestionContext(String question, String kbContext, List<RetrievedChunk> chunks) {
    }
}
