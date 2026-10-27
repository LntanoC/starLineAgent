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

package com.nageoffer.ai.starlineagent.rag.dto;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 知识库检索上下文
 */
@Data
@Builder
public class RetrievalContext {

    /**
     * KB 召回的上下文
     */
    private String kbContext;

    /** 最终证据块，按检索相关性保序并去重。 */
    @Builder.Default
    private List<RetrievedChunk> chunks = List.of();

    /**
     * 是否存在 KB 上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }

    /**
     * 是否无任何上下文
     */
    public boolean isEmpty() {
        return !hasKb();
    }
}
