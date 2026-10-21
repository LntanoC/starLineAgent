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

package com.nageoffer.ai.ragent.agent.confirm;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户取消工具执行后的口径修正
 */
@Component
@ConditionalOnAgentEngine
public class AgentConfirmDenialMiddleware implements MiddlewareBase {

    /**
     * 框架 ReActAgent.applyConfirmResults 的默认拒绝文案
     */
    private static final String FRAMEWORK_DENIAL = "Permission denied by user";

    /**
     * 只改写用户取消的拒绝文案，规则拒绝不动
     */
    private static final String DENIAL_EXPLANATION = """
            用户在确认卡片上点了取消，这次操作没有执行。这不是权限不足，也不是系统故障。
            请如实告诉用户操作已取消；等用户确认后再重新发起。""";

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(rewrite(input));
    }

    /**
     * 有被拒工具时才重建消息列表
     */
    private static ReasoningInput rewrite(ReasoningInput input) {
        List<Msg> messages = input.messages();
        if (messages.stream().noneMatch(AgentConfirmDenialMiddleware::isUserDenial)) {
            return input;
        }
        List<Msg> rebuilt = messages.stream()
                .map(msg -> isUserDenial(msg) ? explain(msg) : msg)
                .toList();
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }

    private static boolean isUserDenial(Msg msg) {
        return msg.getRole() == MsgRole.TOOL && msg.getContent().stream()
                .anyMatch(block -> block instanceof ToolResultBlock result && isUserDenial(result));
    }

    private static boolean isUserDenial(ToolResultBlock block) {
        return block.getState() == ToolResultState.DENIED && FRAMEWORK_DENIAL.equals(plainText(block));
    }

    /**
     * 只替换拒绝块的正文，id 和 name 不动以保持与调用的配对关系
     */
    private static Msg explain(Msg msg) {
        List<ContentBlock> rebuilt = msg.getContent().stream()
                .map(block -> block instanceof ToolResultBlock result && isUserDenial(result)
                        ? new ToolResultBlock(result.getId(), result.getName(),
                        List.of(TextBlock.builder().text(DENIAL_EXPLANATION).build()),
                        result.getMetadata(), result.getState())
                        : block)
                .toList();
        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(rebuilt)
                .metadata(msg.getMetadata())
                .build();
    }

    private static String plainText(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining())
                .trim();
    }
}
