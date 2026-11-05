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

import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.EventItem;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Service
@Conditional(AmapApiKeyConfiguredCondition.class)
public class EventJourneyPlanner {
    private final OfficialEventGateway events;
    private final JourneyPlanner planner;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> { Thread t = new Thread(runnable, "journey-event"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public EventJourneyPlanner(OfficialEventGateway events, JourneyPlanner planner) { this.events = events; this.planner = planner; }
    @PreDestroy public void close() { workers.shutdownNow(); }

    public JourneyPlan plan(JourneyRequest request) {
        List<Clarification> questions = new ArrayList<>();
        if (request.artist() == null) questions.add(new Clarification("artist", "要看哪位艺人的演出？", List.of("请提供艺人正式姓名"), true));
        if (request.city() == null) questions.add(new Clarification("city", "要去哪个城市的演出？", List.of("例如：上海场", "例如：北京场"), true));
        if (!questions.isEmpty()) return JourneyPlanner.result(PlanStatus.NEEDS_CLARIFICATION, request, null, List.of(), null, null, questions, List.of());
        Future<List<EventItem>> task = null;
        List<EventItem> found;
        try {
            task = workers.submit(() -> events.search(request.artist(), request.city()));
            found = task.get(4, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (task != null) task.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return JourneyPlanner.result(PlanStatus.FAILED, request, null, List.of(), null, null, List.of(),
                    List.of(new Warning("EVENT_SEARCH_UNAVAILABLE", "官方演出查询未完成，请稍后重试或核对官方票务页")));
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<EventItem> candidates = found.stream().filter(e -> request.city().equals(TourStopParser.city(e.city())))
                .filter(e -> e.eventDate() != null && !e.eventDate().isBefore(today) && !"ENDED".equals(e.stage()))
                .filter(e -> request.eventDate() == null || request.eventDate().equals(e.eventDate())).toList();
        if (request.venue() != null) {
            List<EventItem> matching = candidates.stream().filter(e -> request.venue().equals(e.venueName()) || request.venue().equals(e.venueAddress())).toList();
            if (!matching.isEmpty()) candidates = matching;
        }
        if (candidates.stream().anyMatch(e -> "TICKETING".equals(e.tier())))
            candidates = candidates.stream().filter(e -> "TICKETING".equals(e.tier())).toList();
        if (candidates.isEmpty()) return JourneyPlanner.result(PlanStatus.FAILED, request, null, List.of(), null, null, List.of(),
                List.of(new Warning("EVENT_NOT_FOUND", "本次白名单搜索未找到匹配的近期场次，不代表没有演出；请核对官方票务渠道")));
        if (candidates.size() > 1) {
            questions.add(new Clarification("eventDate", "查到多场，请选择日期和场馆。", candidates.stream().limit(3)
                    .map(e -> e.eventDate() + " / " + (e.venueName() == null ? "场馆待公布" : e.venueName()) + " / " + e.sourceUrl()).toList(), true));
            if (request.origin() == null) questions.add(originQuestion());
            return JourneyPlanner.result(PlanStatus.NEEDS_CLARIFICATION, request, null, List.of(), null, null, questions, List.of());
        }
        EventItem item = candidates.get(0);
        boolean usableFacts = !item.isStale() && !item.conflict() && "TICKETING".equals(item.tier());
        String venue = request.venue() != null ? request.venue() : usableFacts ? item.venueName() : null;
        LocalDateTime start = request.showStartTime() != null ? request.showStartTime() : usableFacts ? item.showStartTime() : null;
        LocalDateTime end = request.showEndTime() != null ? request.showEndTime() : usableFacts ? item.showEndTime() : null;
        if (start != null && !start.toLocalDate().equals(item.eventDate()))
            return JourneyPlanner.clarify(request, null, "showStartTime", "提供的开场时刻与所选演出日期不一致，请核对。", List.of(item.eventDate().toString()));
        if (end != null && (start == null || !end.isAfter(start))) throw new IllegalArgumentException("散场时间须晚于开场时间");
        if (request.origin() == null) questions.add(originQuestion());
        if (venue == null || start == null) questions.add(new Clarification(venue == null && start == null ? "venueAndShowStartTime" : venue == null ? "venue" : "showStartTime",
                "已查到 " + item.eventDate() + " 的" + request.city() + "场；请确认" + (venue == null ? "具体场馆" : "")
                        + (venue == null && start == null ? "和" : "") + (start == null ? "准确开场时间" : "") + "。",
                List.of("提供官方票务页信息", "提供本人票面上的场馆与开场时间"), true));
        List<Evidence> evidence = new ArrayList<>(List.of(new Evidence("official-search-excerpt", item.sourceUrl(), Instant.now().toString(), "白名单官方来源摘录，开票时间不作为开场时间")));
        if (request.venue() != null || request.showStartTime() != null) evidence.add(new Evidence("user", null, Instant.now().toString(), "场馆或开场时刻含用户提供信息"));
        PriceRange price = usableFacts ? item.priceRange() : new PriceRange(null, null, "CNY", CostStatus.UNKNOWN, null);
        Place place = venue == null ? null : new Place(venue, request.venue() != null || item.venueAddress() == null ? venue : item.venueAddress(),
                request.city(), null, "待定位", null, request.venue() == null ? item.venueSource() : "user", 0.7);
        EventAnchor event = new EventAnchor(request.artist(), item.title(), request.city(), place, start, end, price, evidence);
        if (!questions.isEmpty()) return JourneyPlanner.result(PlanStatus.NEEDS_CLARIFICATION, request, event, List.of(), null, null, questions.stream().limit(2).toList(),
                !usableFacts ? List.of(new Warning("EVENT_FACTS_UNCONFIRMED", "事件来源过时、冲突或仅获批，场馆和开场时间须重新确认")) : List.of());
        return planner.plan(request, event);
    }
    private static Clarification originQuestion() {
        return new Clarification("origin", "你从哪个城市或地址出发？", List.of("例如：南京市鼓楼区某地址", "例如：上海虹桥站"), true);
    }
}
