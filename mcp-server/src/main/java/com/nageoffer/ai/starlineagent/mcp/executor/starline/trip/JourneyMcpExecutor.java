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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolResults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.*;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Slf4j
@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class JourneyMcpExecutor {
    public static final String JOURNEY_DESCRIPTION = "规划城市或地址之间的普通出行，返回 trip-plan.v3 分段时间轴、交通方式、费用明细、人均/同行合计、可行性与来源。必须提供 departAt 或 arriveBy 二选一；城市定位点会明确标注。支持铁路/市内公共交通、自驾、打车和短途步行；航班与大巴专用时刻尚未接入。未知金额为 null，不能说成零；参考价不是实价。FAILED 才表示无可用结果；NEEDS_CLARIFICATION 请按 questions 追问，PARTIAL 仍应展示可用方案。观演场景调用 plan_event_journey，不订票、不承诺余票。";
    public static final String EVENT_DESCRIPTION = "为艺人+城市的观演出行一次查询事件并规划交通、时间轴、往返和费用，返回 trip-plan.v3。上海站在 city 槽位表示上海场，火车站放 origin。origin 可从已知用户信息/记忆补充，不知道则省略并按 questions 追问，最多两问；多场先选日期。场馆、开场和票价只从官方来源或用户提供信息获得，演出日期与开票时刻不能当开场。跨城默认含返程，散场或末班车未知会明确标记。只解释工具结果，不自行算钱或倒推时间；INFEASIBLE 必须明说赶不上，UNKNOWN 不保证来得及。";
    private final JourneyPlanner planner;
    private final EventJourneyPlanner events;
    private final TripPlanProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ConcurrentHashMap<String, Semaphore> users = new ConcurrentHashMap<>();
    private static final List<String> MODES = List.of("rail", "transit", "driving", "taxi", "walk", "flight", "coach");

    @Bean public McpServerFeatures.SyncToolSpecification journeyToolSpecification() { return specification(false); }
    @Bean public McpServerFeatures.SyncToolSpecification eventJourneyToolSpecification() { return specification(true); }
    private McpServerFeatures.SyncToolSpecification specification(boolean event) {
        McpToolSchema schema = McpToolSchema.object();
        if (event) schema.required(string("artist", "艺人正式姓名")).required(string("city", "演出城市，如上海；上海站按巡演上海场处理"))
                .optional(string("origin", "出发城市或具体地址；可从用户已确认的画像/记忆补充，未知时省略"))
                .optional(string("eventDate", "指定场次日期，YYYY-MM-DD；多场时按 questions 让用户选择"))
                .optional(string("venue", "用户确认的场馆；否则从官方事件来源获取，禁止猜测"))
                .optional(string("showStartTime", "用户确认的开场时间，ISO 本地时间，Asia/Shanghai；不能只传日期"))
                .optional(string("showEndTime", "用户确认的散场时间，未知不填"))
                .optional(integer("ticketPaidMinor", "用户提供的人均门票实付，人民币分；未知不填"))
                .optional(integer("arrivalBufferMinutes", "提前到场分钟数，默认30，范围0至360"));
        else schema.required(string("origin", "出发城市或具体地址，火车站名保持原样"))
                .required(string("destination", "目的城市或具体地址"))
                .optional(string("departAt", "出发时刻，ISO本地时间，Asia/Shanghai；与arriveBy二选一"))
                .optional(string("arriveBy", "最晚到达时刻，ISO本地时间；与departAt二选一"));
        schema.optional(integer("partySize", "同行人数，默认1，1至20").defaultTo(1))
                .optional(strings("modes", "可选交通方式；未填时按同城/跨城生成候选", MODES))
                .optional(bool("roundTrip", "是否计算返程；普通问路默认false，观演跨城默认true"))
                .optional(string("returnDepartAt", "明确的返程出发时刻；观演缺省使用散场加20分钟，散场未知不推测"))
                .optional(integer("budgetMinor", "同行合计预算，人民币分；未知不填"))
                .optional(integer("parkingMinor", "用户提供的每车停车费，人民币分；未知不填"));
        String id = event ? "plan_event_journey" : "plan_journey";
        Tool tool = Tool.builder().name(id).description(event ? EVENT_DESCRIPTION : JOURNEY_DESCRIPTION)
                .inputSchema(schema.build()).annotations(McpToolAnnotations.READ_ONLY).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> handleCall(request, event)).build();
    }

    public CallToolResult handleCall(CallToolRequest call, boolean event) {
        String user = Optional.ofNullable(McpToolResults.userId(call)).orElse("anonymous");
        if (users.size() >= 1024 && !users.containsKey(user)) return McpToolResults.error("规划请求繁忙，请稍后重试");
        Semaphore limit = users.computeIfAbsent(user, ignored -> new Semaphore(2));
        if (!limit.tryAcquire()) return McpToolResults.error("当前已有出行规划正在处理，请稍后重试");
        try {
            JourneyRequest request = parse(McpToolResults.args(call), event, properties.getDefaultArrivalBufferMinutes());
            JourneyPlan result = event ? events.plan(request) : planner.plan(request, null);
            return CallToolResult.builder().content(List.of(new TextContent(mapper.writeValueAsString(result))))
                    .isError(result.status() == PlanStatus.FAILED).build();
        } catch (IllegalArgumentException error) {
            try {
                JourneyPlan result = JourneyPlanner.result(PlanStatus.FAILED, null, null, List.of(), null, null, List.of(), List.of(new Warning("INVALID_ARGUMENT", error.getMessage())));
                return CallToolResult.builder().content(List.of(new TextContent(mapper.writeValueAsString(result)))).isError(true).build();
            } catch (Exception ignored) { return McpToolResults.error("出行参数无效，请检查输入"); }
        } catch (Exception error) {
            log.warn("journey tool failed type={}", error.getClass().getSimpleName());
            return McpToolResults.error("本次出行规划暂时不可用，请稍后重试");
        } finally { limit.release(); }
    }

    static JourneyRequest parse(Map<String, Object> args, boolean event, int defaultBuffer) {
        String city = event ? TourStopParser.city(text(args, "city")) : null;
        LocalDateTime depart = dateTime(args, "departAt"), arrive = dateTime(args, "arriveBy");
        if (depart != null && arrive != null) throw new IllegalArgumentException("departAt 与 arriveBy 不能同时提供");
        int party = Math.toIntExact(number(args, "partySize", 1L, 1, 20));
        int buffer = Math.toIntExact(number(args, "arrivalBufferMinutes", (long) defaultBuffer, 0, 360));
        Boolean round = null;
        if (args.get("roundTrip") != null) {
            if (!(args.get("roundTrip") instanceof Boolean value)) throw new IllegalArgumentException("roundTrip 须为布尔值");
            round = value;
        }
        List<String> modes = null;
        if (args.get("modes") != null) {
            if (!(args.get("modes") instanceof List<?> list) || list.isEmpty() || list.size() > 8 || list.stream().anyMatch(v -> !(v instanceof String) || !MODES.contains(v)))
                throw new IllegalArgumentException("modes 须为有效的交通方式数组");
            modes = list.stream().map(Object::toString).distinct().toList();
        }
        LocalDate date = null;
        if (text(args, "eventDate") != null) {
            try { date = LocalDate.parse(text(args, "eventDate")); }
            catch (RuntimeException e) { throw new IllegalArgumentException("eventDate 须为 YYYY-MM-DD"); }
        }
        return new JourneyRequest(text(args, "artist"), city, text(args, "origin"), text(args, "destination"), date,
                text(args, "venue"), dateTime(args, "showStartTime"), dateTime(args, "showEndTime"), depart, arrive,
                dateTime(args, "returnDepartAt"), party, round, modes, number(args, "budgetMinor", null, 0, 1000000000L),
                number(args, "ticketPaidMinor", null, 0, 100000000L), buffer, number(args, "parkingMinor", null, 0, 100000000L));
    }
    private static String text(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 250) throw new IllegalArgumentException(field + " 须为不超过250字的文本");
        return text.isBlank() ? null : text.trim();
    }
    private static LocalDateTime dateTime(Map<String, Object> args, String field) {
        String value = text(args, field);
        if (value == null) return null;
        try { return LocalDateTime.parse(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException(field + " 须包含完整日期和时间，例如 2026-11-21T19:30:00（北京时间）"); }
    }
    private static Long number(Map<String, Object> args, String field, Long fallback, long min, long max) {
        Object raw = args.get(field);
        if (raw == null) return fallback;
        try {
            if (!(raw instanceof Number)) throw new ArithmeticException();
            long number = new BigDecimal(raw.toString()).longValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return number;
        } catch (ArithmeticException e) { throw new IllegalArgumentException(field + " 须为 " + min + " 到 " + max + " 之间的整数"); }
    }
}
