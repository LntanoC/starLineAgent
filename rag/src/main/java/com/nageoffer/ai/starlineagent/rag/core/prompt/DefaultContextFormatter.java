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

package com.nageoffer.ai.starlineagent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunkKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.starlineagent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    @Override
    public String formatKbContext(List<RetrievedChunk> rerankedChunks,
                                  int contextTopK) {
        if (CollUtil.isEmpty(rerankedChunks)) {
            return "";
        }
        List<RetrievedChunk> chunks = distinctChunks(rerankedChunks);
        if (chunks.isEmpty()) {
            return "";
        }

        return renderChunksGroupedByDoc(chunks, contextTopK);
    }

    private List<RetrievedChunk> distinctChunks(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.forEach(chunk -> distinct.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));
        return new ArrayList<>(distinct.values());
    }

    /**
     * 按文档聚合渲染 chunk 列表
     * <p>
     * 文档之间按相关性排序（各文档首个命中块在原列表中的顺序，即该文档最佳块的排名），
     * 文档内部按 {@code chunkIndex} 升序还原原文顺序；docId 缺失的块各自单独成组、留在原位
     */
    private String renderChunksGroupedByDoc(List<RetrievedChunk> chunks, int topK) {
        long limit = topK > 0 ? topK : Long.MAX_VALUE;
        List<RetrievedChunk> limited = chunks.stream().limit(limit).toList();
        if (limited.isEmpty()) {
            return "";
        }

        // 按 docId 分组：LinkedHashMap 保持首次出现顺序 = 文档间的相关性排序；docId 为空的块各自单独成组
        LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk chunk : limited) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + (anonymousSeq++);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        return groups.values().stream()
                .map(this::renderDocBlock)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个文档块：组内按序号排序后拼接，只带内部 docId 作为锚点
     * <p>
     * 刻意不注入文档标题：标题一旦进入上下文，模型就会写出"出自《XX》"之类的归因表述，
     * 而提示词层面的禁令压不住。资料之间的区分交给 {@code ref} 编号，文档名只在前端来源列表展示
     */
    private String renderDocBlock(List<RetrievedChunk> group) {
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String chunks = joinDocBody(ordered);
        String docId = sanitizeAttribute(resolveDocId(group));
        if (StrUtil.isNotBlank(docId)) {
            return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block", Map.of(
                    "doc_id", docId,
                    "chunks", chunks
            ));
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block-anonymous", Map.of(
                "chunks", chunks
        ));
    }

    /**
     * 组内拼接文本：同文档的块按 index 排好后用换行顺次拼接
     */
    private String joinDocBody(List<RetrievedChunk> ordered) {
        return ordered.stream()
                .map(RetrievedChunk::getText)
                .map(StrUtil::emptyIfNull)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清洗属性值里会破坏伪标签结构的字符（引号、尖括号）
     */
    private String sanitizeAttribute(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.replaceAll("[\"<>]", "").trim();
    }

    private String resolveDocId(List<RetrievedChunk> group) {
        return group.stream()
                .map(RetrievedChunk::getDocId)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
    }

}
