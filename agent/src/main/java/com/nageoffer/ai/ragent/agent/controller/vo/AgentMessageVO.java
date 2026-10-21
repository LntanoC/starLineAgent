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

package com.nageoffer.ai.ragent.agent.controller.vo;

import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * Agent 消息视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentMessageVO {

    /**
     * 消息ID
     */
    private String id;

    /**
     * user / assistant
     */
    private String role;

    /**
     * 消息正文
     */
    private String content;

    /**
     * 思考内容
     */
    private String thinkingContent;

    /**
     * 运行轨迹块，回放还原时间线；旧数据为 null 时由前端按 content/thinking 合成
     */
    private List<AgentBlock> blocks;

    /**
     * NORMAL / INTERRUPTED
     */
    private String messageStatus;

    /**
     * 本轮 run 的服务端耗时，assistant 才有，旧数据为 null
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private Date createTime;
}
