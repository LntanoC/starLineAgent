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

package com.nageoffer.ai.ragent.rag.controller.request;

import lombok.Data;

import java.util.List;

/**
 * 技能保存请求，新增与编辑共用
 */
@Data
public class AgentSkillSaveRequest {

    /**
     * 技能标识，模型按此名加载正文，创建后不可改
     */
    private String skillCode;

    /**
     * 技能展示名
     */
    private String name;

    /**
     * 适用场景，写清楚什么时候该用这个技能
     */
    private String description;

    /**
     * 技能正文 Markdown
     */
    private String content;

    /**
     * 加载本技能后才解锁的 MCP 工具 ID，取值只能来自意图树的 MCP 节点
     */
    private List<String> toolIds;

    /**
     * 排序，越小越靠前
     */
    private Integer sortOrder;

    /**
     * 是否启用，不传按启用处理
     */
    private Boolean enabled;
}
