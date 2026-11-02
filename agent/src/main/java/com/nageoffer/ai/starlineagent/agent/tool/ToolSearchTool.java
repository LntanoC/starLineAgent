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

package com.nageoffer.ai.starlineagent.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.service.ToolRagService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Lets a running Agent request the tool schemas for its next task step. */
@Slf4j
@RequiredArgsConstructor
public class ToolSearchTool implements AgentTool {

    public static final String TOOL_NAME = "search_tools";
    public static final String DISPLAY_NAME = "刷新当前阶段工具";

    private final AgentToolRoutingService routingService;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "任务进入新的阶段，或当前可见工具无法完成下一步时，先用本工具按当前子目标检索可用 MCP 工具。query 只写新阶段的独立目标和必要的已确认条件，不要复制整段对话。工具会在下一轮推理中刷新匹配的工具 schema；若结果提示需加载技能，先调用 load_skill。该工具只检索已启用且可分发的工具，不能绕过权限或确认规则。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "当前阶段的独立目标，例如：根据已确认的场馆和开场时间倒推最晚出发时间",
                        "maxLength", 1000)),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolExecutionTracker.track(param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String callId = Optional.ofNullable(param)
                .map(ToolCallParam::getToolUseBlock)
                .map(ToolUseBlock::getId)
                .orElse(null);
        if (param == null || param.getInput() == null) {
            return result(callId, "工具参数 query 不能为空", ToolResultState.ERROR);
        }
        Object rawQuery = param.getInput().get("query");
        String query = rawQuery instanceof String value ? value.strip() : null;
        if (StrUtil.isBlank(query) || query.length() > 1000) {
            return result(callId, "query 不能为空且不能超过 1000 个字符", ToolResultState.ERROR);
        }
        RuntimeContext context = param.getRuntimeContext();
        try {
            ToolRagService.RecallResult recall = routingService.updateQuery(context, query);
            if (TaskCancellation.isCancelled()) {
                return result(callId, "用户已停止，本次工具目录刷新未完成", ToolResultState.INTERRUPTED);
            }
            List<String> matches = recall.selectedTools().stream()
                    .map(this::renderTool)
                    .toList();
            String response = matches.isEmpty()
                    ? "当前子目标没有匹配到可用 MCP 工具。下一轮只保留常驻工具；请确认需求是否超出当前能力，或进一步改写当前子目标。"
                    : "当前子目标的工具候选已更新，下一轮推理会收到匹配 schema：\n"
                    + matches.stream().collect(Collectors.joining("\n"));
            return result(callId, response, ToolResultState.SUCCESS);
        } catch (Exception e) {
            if (TaskCancellation.isCancellation(e)) {
                return result(callId, "用户已停止，本次工具目录刷新未完成", ToolResultState.INTERRUPTED);
            }
            log.warn("当前阶段工具召回失败, reason={}", e.getClass().getSimpleName());
            return result(callId, "暂时无法检索当前阶段工具；系统不会因此开放全部 MCP 工具，请稍后重试或向用户说明。",
                    ToolResultState.ERROR);
        }
    }

    private String renderTool(McpToolDO tool) {
        return "- %s（%s）".formatted(StrUtil.blankToDefault(tool.getDisplayName(), tool.getToolId()),
                tool.getToolId());
    }

    private ToolResultBlock result(String callId, String text, ToolResultState state) {
        return ToolResultBlock.builder()
                .id(callId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(state)
                .build();
    }
}
