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

package com.nageoffer.ai.ragent.agent.enums;

/**
 * 长期记忆条目的写入来源，语义上只有容量淘汰按它分档（FLUSH 殿后）
 */
public enum AgentMemorySourceType {

    /**
     * 模型在对话里调 flush_memory 触发
     */
    FLUSH,

    /**
     * 轮次释放后的后台抽取
     */
    BACKGROUND,

    /**
     * 容量反压时的合并产物
     */
    CONSOLIDATION
}
