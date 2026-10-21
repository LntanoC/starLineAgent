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

package com.nageoffer.ai.ragent.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE block 事件载荷：一段文本流完了，把服务端量到的起止交给前端
 * <p>
 * 不带正文，前端手里的增量已经是全文，再发一遍只是把同一段文字传两趟
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTextBlockSeal(String kind, String at, Long startedAt, Long endedAt, Long durationMs) {

    /**
     * 直接从块投影：SSE 与落库两份要是各算一遍，刷新前后就成了两条不同的记录
     */
    public static AgentTextBlockSeal of(AgentBlock block) {
        return new AgentTextBlockSeal(block.getKind(), block.getAt(), block.getStartedAt(),
                block.getEndedAt(), block.getDurationMs());
    }
}
