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
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripPlanInput;
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

import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.string;

@Slf4j
@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class TripPlanMcpExecutor {

    private static final String TOOL_ID = "plan_event_trip";
    private static final String DESCRIPTION = "根据演出时间与出发地倒推最晚出发时间并给出高德分段路线耗时。适用于“几点得从家走”“从车站到场馆要多久”“会不会迟到”；不适用于行程编排、景点推荐、订票订房。必须先调用 search_artist_events 核实演出；该工具目前只提供演出日期，不含结构化场馆和开场时间。若搜索结果或用户信息里缺少具体场馆或准确开场时间，先向用户确认，不得把演出日期当作开场时间或自行假设场馆。路线耗时来自高德路径规划，不含实时拥堵，并建议额外预留 15 分钟；城际火车或航班时刻不由本工具查询，也不得编造车次号、航班号或发车时间。未提供城际到达时间时只计算所填出发地至场馆的路线，latestDeparture 指该出发地；提供 intercityArrivalTime 和 intercityArrivalPlace 时只计算到达地点至场馆的路线，latestDeparture 指该到达地点，城际行程仍由用户自行确认。assumptions 必须原样转述；confidence=MEDIUM 表示高德路线覆盖自驾段但仍需预留路况误差；confidence=LOW 表示只包含市内路线且城际行程未包含。";

    private final RouteProvider routeProvider;
    private final TripPlanProperties properties;
    private final TripTimeCalculator calculator = new TripTimeCalculator();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Bean
    public McpServerFeatures.SyncToolSpecification tripPlanToolSpecification() {
        JsonSchema schema = McpToolSchema.object()
                .required(string("venue", "已核实的演出场馆名称或具体地址；若演出查询结果没有提供，请先向用户确认，不要只传城市"))
                .required(string("eventStartTime", "已核实的准确开场时间，ISO 本地时间，例如 2026-11-15T19:30:00；演出日期不等于开场时间"))
                .required(string("origin", "出发地地址，例如 上海市徐汇区某路；提供城际到达地点时仍需传入用户的出发城市或地址"))
                .optional(string("mode", "通行方式：driving（自驾，默认）、transit（同城公共交通）、walking（步行）")
                        .options(List.of("driving", "transit", "walking")).defaultTo("driving"))
                .optional(string("intercityArrivalTime", "用户已确认的城际到达时间，ISO 本地时间；本工具不查询火车或航班时刻"))
                .optional(string("intercityArrivalPlace", "用户已确认的城际到达地点，如无锡东站；与 intercityArrivalTime 成对传入"))
                .optional(integer("arrivalBufferMinutes", "提前到场分钟数，默认 30；不能小于 0"))
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
            String venue = StrUtil.trimToNull(MapUtil.getStr(args, "venue"));
            String origin = StrUtil.trimToNull(MapUtil.getStr(args, "origin"));
            String eventStartTime = StrUtil.trimToNull(MapUtil.getStr(args, "eventStartTime"));
            String arrivalTime = StrUtil.trimToNull(MapUtil.getStr(args, "intercityArrivalTime"));
            String arrivalPlace = StrUtil.trimToNull(MapUtil.getStr(args, "intercityArrivalPlace"));
            String mode = StrUtil.blankToDefault(MapUtil.getStr(args, "mode"), "driving");
            Integer buffer = MapUtil.getInt(args, "arrivalBufferMinutes");
            TripPlanInput input = new TripPlanInput(venue, origin, parseTime(eventStartTime),
                    arrivalTime == null ? null : parseTime(arrivalTime), arrivalPlace, mode, buffer);
            String json = objectMapper.writeValueAsString(calculator.calculate(input, routeProvider,
                    properties.getDefaultArrivalBufferMinutes()));
            log.info("MCP 工具调用完成, toolId={}, mode={}, elapsed={}ms",
                    TOOL_ID, mode, System.currentTimeMillis() - startedAt);
            return McpToolResults.success(json);
        } catch (DateTimeParseException e) {
            return McpToolResults.error("时间格式不合法，请使用 ISO 本地时间，例如 2026-11-15T19:30:00");
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms", TOOL_ID,
                    System.currentTimeMillis() - startedAt, e);
            return McpToolResults.failure("观演出行规划", e);
        }
    }

    private LocalDateTime parseTime(String value) {
        if (value == null) {
            throw new DateTimeParseException("missing time", "", 0);
        }
        return LocalDateTime.parse(value);
    }
}
