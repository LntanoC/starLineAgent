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

package com.nageoffer.ai.ragent.agent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 流式对话服务
 */
public interface AgentChatService {

    /**
     * 发起 Agent 流式对话
     */
    void streamChat(String question, String conversationId, SseEmitter emitter);

    /**
     * 裁决挂起的写操作并续跑：同意则执行工具，拒绝则让模型带着「用户已取消」继续作答
     * 参数只带同意与否，待执行的工具与入参一律从 Agent 状态里取原件
     */
    void confirmPendingTool(String conversationId, String messageId, boolean approved, SseEmitter emitter);

    /**
     * 停止指定任务
     */
    void stopTask(String taskId);
}
