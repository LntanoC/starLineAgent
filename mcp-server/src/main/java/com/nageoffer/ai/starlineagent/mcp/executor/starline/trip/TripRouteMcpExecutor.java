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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.trip;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolResults;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.string;

@Slf4j
@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class TripRouteMcpExecutor {

    private static final String TOOL_ID = "plan_trip";
    private static final String DESCRIPTION = "规划城市或详细地址之间的普通出行，适用于“我从南京去上海怎么走”“从车站到酒店怎么去”等问路，不要求与演出有关。可返回高德公共交通（含跨城火车、公交、地铁）和自驾路线；只给城市名时按高德城市定位点规划，说明不是门到门路线。出发时间可选；未提供完整出发日期和时间时查询路线概览，不得编造车次、发车时间或余票。结果来自高德路径规划，费用仅为参考且不代表可购票；航班时刻和订票不在范围内。观演到场倒推使用 plan_event_trip。";

    private final TripRouteProvider routeProvider;
    private final ObjectMapper objectMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification tripRouteToolSpecification() {
        JsonSchema schema = McpToolSchema.object()
                .required(string("origin", "出发城市或详细地址，例如 南京市，或 南京市鼓楼区某路"))
                .required(string("destination", "目的城市或详细地址，例如 上海市，或 上海市浦东新区某路"))
                .optional(string("departureAt", "可选出发日期时间，ISO 本地格式，例如 2026-10-30T09:00:00；只有日期没有时间时不要填写，不按具体时刻筛选班次"))
                .optional(string("mode", "出行方式：all（公共交通和自驾，默认）、transit（公共交通，含火车/公交/地铁）、driving（自驾）")
                        .options(List.of("all", "transit", "driving")).defaultTo("all"))
                .build();
        Tool tool = Tool.builder().name(TOOL_ID).description(DESCRIPTION).inputSchema(schema)
                .annotations(McpToolAnnotations.READ_ONLY).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handleCall(request)).build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String origin = StrUtil.trimToNull(MapUtil.getStr(args, "origin"));
            String destination = StrUtil.trimToNull(MapUtil.getStr(args, "destination"));
            String departureAt = StrUtil.trimToNull(MapUtil.getStr(args, "departureAt"));
            String mode = StrUtil.blankToDefault(MapUtil.getStr(args, "mode"), "all");
            LocalDateTime departTime = departureAt == null ? null : LocalDateTime.parse(departureAt);
            String json = objectMapper.writeValueAsString(routeProvider.plan(origin, destination, departTime, mode));
            log.info("MCP 工具调用完成, toolId={}, mode={}, elapsed={}ms",
                    TOOL_ID, mode, System.currentTimeMillis() - startedAt);
            return McpToolResults.success(json);
        } catch (DateTimeParseException e) {
            return McpToolResults.error("出发时间格式不合法，请使用 ISO 本地时间，例如 2026-10-30T09:00:00");
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms", TOOL_ID,
                    System.currentTimeMillis() - startedAt, e);
            return McpToolResults.failure("跨城出行规划", e);
        }
    }
}
