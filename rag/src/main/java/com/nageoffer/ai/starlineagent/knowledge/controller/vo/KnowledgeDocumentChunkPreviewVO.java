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

package com.nageoffer.ai.starlineagent.knowledge.controller.vo;

import java.util.List;

/**
 * 上传前切块预览结果。
 */
public record KnowledgeDocumentChunkPreviewVO(
        String fileName,
        String mimeType,
        String parserType,
        int blockCount,
        int chunkCount,
        List<String> warnings,
        List<Chunk> chunks
) {

    public KnowledgeDocumentChunkPreviewVO {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public record Chunk(
            int index,
            String content,
            String embeddingText,
            int contentCharCount,
            int embeddingCharCount,
            List<String> outlinePath
    ) {
        public Chunk {
            outlinePath = outlinePath == null ? List.of() : List.copyOf(outlinePath);
        }
    }
}
