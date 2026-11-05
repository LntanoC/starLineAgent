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

package com.nageoffer.ai.starlineagent.mcp.executor.starline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.starlineagent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.starlineagent.mcp.executor.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.*;

/** All consumers use the same typed discovery service. */
@Component
@RequiredArgsConstructor
public class ArtistEventSearchMcpExecutor {
    private final EventSearchService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String DESCRIPTION = "发现艺人的演出/巡演安排，city可省略；未指定城市时不补填城市，不调用plan_event_journey。仅从数据库启用的公开白名单检索，默认window=year（未来365天），recent=未来90天，all=未来730天；includeEnded默认false。多查询、多城市、一页多场次；pageSize默认20上限50，nextCursor可加载更多。coverage和notes说明窗口、查询/来源数、过滤、截断及提供方限制，只能说本次检索到的，不得称全部或保证覆盖。dateUnknown表示日期待核实，不能用候选年份当确定演出日；未知场馆/开场不能补造。GOV/APPROVED仅证明许可；TICKETING提供ON_SHELF待开票、ON_SALE已开票、SOLD_OUT售罄、ENDED已结束状态，只有canBuyTicket=true才能说现在可以买；isStale/conflict时必须核验，不自动创建提醒。total=0不表示没有演出。相对时间先用current_date。前端渲染卡片，只给一句总述和覆盖限制，不重复卡片，也不声称已创建提醒。艺人生日/作品/八卦问题不要调用。";
    @Bean
    public McpServerFeatures.SyncToolSpecification artistEventSearchToolSpecification() {
        var schema = object().required(string("artist", "艺人正式姓名"))
                .optional(string("city", "用户明确指定城市时传；发现模式省略"))
                .optional(string("window", "演出日期窗口，默认year未来365天").options(List.of("recent", "year", "all")).defaultTo("year"))
                .optional(bool("includeEnded", "是否包含近30天已结束场次").defaultTo(false))
                .optional(integer("pageSize", "每页1至50条，默认20").defaultTo(20))
                .optional(string("cursor", "上一页nextCursor，其他条件保持一致"))
                .optional(string("freshness", "历史参数；week/month映射recent，year映射year").options(List.of("week", "month", "year")))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(Tool.builder().name("search_artist_events").description(DESCRIPTION)
                .inputSchema(schema).annotations(McpToolAnnotations.READ_ONLY).build()).callHandler((exchange, request) -> handleCall(request)).build();
    }
    CallToolResult handleCall(CallToolRequest request) {
        try { return McpToolResults.success(mapper.writeValueAsString(service.search(McpToolResults.args(request)))); }
        catch (IllegalArgumentException error) { return McpToolResults.error(error.getMessage()); }
        catch (Exception error) { return McpToolResults.failure("演出查询", error); }
    }
    public List<EventItem> searchEvents(String artist, String city) { return service.searchEvents(artist, city); }
    public record SourceEvidence(String sourceName, String tier, String sourceUrl, String stage) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record EventItem(String artistName, String title, String stage, String tier, String city,
                            LocalDate eventDate, LocalDateTime saleStartTime, Long daysToSale,
                            boolean canBuyTicket, String sourceName, String sourceUrl,
                            String approvedNote, String approvedUrl, boolean isStale, boolean conflict,
                            List<SourceEvidence> sourceEvidence, String venueName, String venueAddress,
                            LocalDateTime showStartTime, LocalDateTime showEndTime,
                            com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.PriceRange priceRange,
                            String venueSource, String timeSource) {}
}
