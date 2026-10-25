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

package com.nageoffer.ai.starlineagent.agent.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.starlineagent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.starlineagent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseIntake;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.starlineagent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.starlineagent.rag.dao.mapper.MessageFeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * Agent 对话的点踩反馈：校验消息归属后写入统一反馈表。
 */
@Service
@RequiredArgsConstructor
public class AgentMessageFeedbackService {

    private final AgentMessageMapper agentMessageMapper;
    private final MessageFeedbackMapper messageFeedbackMapper;
    private final BadCaseIntake badCaseIntake;

    public void submitDislike(String messageId, String userId, MessageFeedbackRequest request) {
        if (!StringUtils.hasText(userId)) {
            throw new ClientException("未获取到当前登录用户");
        }
        if (!StringUtils.hasText(messageId)) {
            throw new ClientException("消息ID不能为空");
        }
        if (request == null || request.getVote() == null || request.getVote() != -1) {
            throw new ClientException("当前接口只接受点踩反馈");
        }

        AgentMessageDO message = agentMessageMapper.selectOne(
                Wrappers.lambdaQuery(AgentMessageDO.class)
                        .eq(AgentMessageDO::getId, messageId)
                        .eq(AgentMessageDO::getUserId, userId)
                        .eq(AgentMessageDO::getRole, "assistant")
                        .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.NORMAL.name())
                        .eq(AgentMessageDO::getDeleted, 0)
        );
        if (message == null) {
            throw new ClientException("仅支持对当前用户已完成的助手回答点踩");
        }

        if ((request.getReason() != null && request.getReason().length() > 255)
                || (request.getComment() != null && request.getComment().length() > 1024)) {
            throw new ClientException("反馈原因或说明超过长度限制");
        }
        Date now = new Date();
        MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                .id(IdWorker.getIdStr())
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .userId(userId)
                .vote(-1)
                .reason(request.getReason())
                .comment(request.getComment())
                .createTime(now)
                .updateTime(now)
                .build();
        messageFeedbackMapper.upsertActiveFeedback(feedback);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelDislike(String messageId, String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new ClientException("未获取到当前登录用户");
        }
        if (!StringUtils.hasText(messageId)) {
            throw new ClientException("消息ID不能为空");
        }

        AgentMessageDO message = agentMessageMapper.selectOne(
                Wrappers.lambdaQuery(AgentMessageDO.class)
                        .eq(AgentMessageDO::getId, messageId)
                        .eq(AgentMessageDO::getUserId, userId)
                        .eq(AgentMessageDO::getRole, "assistant")
                        .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.NORMAL.name())
                        .eq(AgentMessageDO::getDeleted, 0)
        );
        if (message == null) {
            throw new ClientException("仅支持撤销当前用户已完成的助手回答点踩");
        }

        Date now = new Date();
        MessageFeedbackDO tombstone = MessageFeedbackDO.builder()
                .id(IdWorker.getIdStr())
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .userId(userId)
                .createTime(now)
                .updateTime(now)
                .build();
        messageFeedbackMapper.upsertCancelledFeedback(tombstone);
        badCaseIntake.withdrawUserFeedback(message.getId(), userId);
    }
}