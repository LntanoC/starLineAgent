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

package com.nageoffer.ai.starlineagent.core.ingest;

import com.nageoffer.ai.starlineagent.core.chunk.model.Chunk;

import java.util.List;

/**
 * 预览阶段的解析与切块结果；不包含嵌入向量，也不代表已经写入任何索引。
 */
public record IngestionPreview(
        String mimeType,
        String parserType,
        int blockCount,
        List<Chunk> chunks
) {

    public IngestionPreview {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public int chunkCount() {
        return chunks.size();
    }
}
