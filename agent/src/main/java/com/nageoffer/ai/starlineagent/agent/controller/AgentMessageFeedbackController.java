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

package com.nageoffer.ai.starlineagent.agent.controller;

import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.starlineagent.agent.service.AgentMessageFeedbackService;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.rag.controller.request.MessageFeedbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 已完成消息的反馈入口。
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMessageFeedbackController {

    private final AgentMessageFeedbackService feedbackService;

    @PostMapping("/agent/v1/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        feedbackService.submitDislike(messageId, UserContext.getUserId(), request);
        return Results.success();
    }

    @DeleteMapping("/agent/v1/messages/{messageId}/feedback")
    public Result<Void> cancelFeedback(@PathVariable String messageId) {
        feedbackService.cancelDislike(messageId, UserContext.getUserId());
        return Results.success();
    }
}