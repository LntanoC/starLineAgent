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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 待确认的工具调用，展示工具名和参数供用户确认
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentConfirmCall {

    /**
     * 工具调用 ID，与模型上下文中的 tool_use id 一致
     */
    private String toolCallId;

    /**
     * 工具名
     */
    private String name;

    /**
     * 工具展示名，取自意图树配置
     */
    private String displayName;

    /**
     * 入参列表（schema 声明序），卡片主视图展示用
     */
    private List<AgentConfirmField> fields;

    /**
     * 完整调用参数 JSON，卡片折叠区展示
     */
    private String arguments;
}
