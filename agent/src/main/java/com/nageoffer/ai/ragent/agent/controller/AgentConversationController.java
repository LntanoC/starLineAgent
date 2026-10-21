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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 会话最小 CRUD，与 workflow 会话接口两套分立
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentConversationController {

    private final AgentConversationService agentConversationService;

    @GetMapping("/agent/v1/conversations")
    public Result<List<AgentConversationVO>> listConversations() {
        return Results.success(agentConversationService.listByUserId(UserContext.getUserId()));
    }

    @GetMapping("/agent/v1/conversations/{conversationId}/messages")
    public Result<List<AgentMessageVO>> listMessages(@PathVariable String conversationId) {
        return Results.success(agentConversationService.listMessages(conversationId, UserContext.getUserId()));
    }

    @PutMapping("/agent/v1/conversations/{conversationId}/title")
    public Result<Void> rename(@PathVariable String conversationId, @RequestBody TitleRequest request) {
        agentConversationService.rename(conversationId, UserContext.getUserId(),
                request == null ? null : request.title());
        return Results.success();
    }

    @DeleteMapping("/agent/v1/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        agentConversationService.delete(conversationId, UserContext.getUserId());
        return Results.success();
    }

    @PostMapping("/agent/v1/conversations/batch-delete")
    public Result<Void> batchDelete(@RequestBody BatchDeleteRequest request) {
        agentConversationService.deleteBatch(request == null ? List.of() : request.ids(), UserContext.getUserId());
        return Results.success();
    }

    public record TitleRequest(String title) {
    }

    public record BatchDeleteRequest(List<String> ids) {
    }
}
