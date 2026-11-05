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
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.EventItem;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** 黄金场景验证的是编排/事实契约，不是在线模型生成或真实票务覆盖率。 */
class JourneyPlanningTest {
    private final LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(20);
    private final LocalDateTime start = date.atTime(19, 30);
    private final StubRoutes routes = new StubRoutes();
    private final TripPlanProperties properties = new TripPlanProperties();
    private final JourneyPlanner planner = new JourneyPlanner(routes, properties, Clock.system(ZoneId.of("Asia/Shanghai")));
    private EventJourneyPlanner eventPlanner;
    @AfterEach void close() { planner.close(); if (eventPlanner != null) eventPlanner.close(); }
    private JourneyRequest request(Map<String, Object> args, boolean event) {
        Map<String, Object> values = new HashMap<>(args);
        if (event) { values.putIfAbsent("artist", "测试艺人"); values.putIfAbsent("city", "上海站"); }
        return JourneyMcpExecutor.parse(values, event, 30);
    }
    private EventItem event(String venue, LocalDateTime time, LocalDateTime end) {
        return new EventItem("测试艺人", "测试演唱会", "ON_SHELF", "TICKETING", "上海", date, null, null, false,
                "测试票务", "https://example.com/fixture-event", null, null, false, false, List.of(), venue, null, time, end,
                new PriceRange(null, null, "CNY", CostStatus.UNKNOWN, null), "https://example.com/fixture-event", time == null ? null : "https://example.com/fixture-event");
    }
    private JourneyPlan plan(Map<String, Object> args, List<EventItem> events) {
        eventPlanner = new EventJourneyPlanner((artist, city) -> { assertEquals("上海", city); return events; }, planner);
        return eventPlanner.plan(request(args, true));
    }
    @Test void artistCityOnlyAsksOnlyOriginWhenEventIsComplete() {
        var result = plan(Map.of(), List.of(event("测试馆", start, null)));
        assertEquals(PlanStatus.NEEDS_CLARIFICATION, result.status());
        assertEquals(1, result.questions().size()); assertEquals("origin", result.questions().get(0).field());
        assertEquals(0, routes.calls.get());
    }
    @Test void twoPeopleCrossCityRoundTripKeepsTicketAndReturnUnknown() {
        var result = plan(Map.of("origin", "南京测试地址", "partySize", 2, "roundTrip", true, "modes", List.of("driving")), List.of(event("测试馆", start, null)));
        assertEquals(PlanStatus.PARTIAL, result.status());
        assertTrue(result.request().roundTrip());
        assertTrue(result.options().get(0).cost().unknownCategories().containsAll(List.of("ticket", "return_transport")));
        assertEquals("UNKNOWN", result.options().get(0).returnJourney().status());
        assertNull(result.recommendedOptionId());
    }
    @Test void sameMunicipalityDoesNotBecomeIntercityAndDefaultsToOneWay() {
        var result = plan(Map.of("origin", "上海站", "modes", List.of("driving")), List.of(event("测试馆", start, null)));
        assertFalse(result.request().roundTrip());
        assertEquals("NOT_REQUESTED", result.options().get(0).returnJourney().status());
        assertTrue(routes.names.contains("上海站"));
    }
    @Test void generalRouteDoesNotSearchEventsOrRewriteStation() {
        var result = planner.plan(request(Map.of("origin", "上海站", "destination", "梅奔", "departAt", date.atTime(10, 0).toString(), "modes", List.of("driving")), false), null);
        assertNull(result.event()); assertEquals(1, result.options().size());
        assertTrue(routes.names.containsAll(List.of("上海站", "梅奔")));
    }
    @Test void dateWithoutVenueOrTimeNeverStartsRouteCalculation() {
        var result = plan(Map.of("origin", "南京"), List.of(event(null, null, null)));
        assertEquals(PlanStatus.NEEDS_CLARIFICATION, result.status());
        assertNull(result.event().showStart()); assertNull(result.event().venue());
        assertEquals(0, routes.calls.get());
    }
    @Test void multipleShowsUseAtMostTwoQuestionsAndThreeChoices() {
        var result = plan(Map.of(), List.of(event("馆A", start, null), event("馆B", start, null), event("馆C", start, null), event("馆D", start, null)));
        assertEquals(2, result.questions().size()); assertEquals(3, result.questions().get(0).options().size());
        assertEquals(0, routes.calls.get());
    }
    @Test void providerFailureKeepsOtherModesAsPartial() {
        routes.failTransit = true;
        var result = plan(Map.of("origin", "南京", "modes", List.of("transit", "driving")), List.of(event("测试馆", start, null)));
        assertEquals(PlanStatus.PARTIAL, result.status()); assertEquals(1, result.options().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.code().equals("AMAP_TIMEOUT")));
    }
    @Test void unknownFeesDoNotMakeBudgetAppearSufficient() {
        var result = plan(Map.of("origin", "南京", "partySize", 2, "budgetMinor", 1000, "modes", List.of("driving")), List.of(event("测试馆", start, null)));
        assertFalse(result.options().get(0).cost().unknownCategories().isEmpty());
        assertNotEquals("WITHIN_BUDGET", result.recommendReason());
        assertTrue(result.options().get(0).warnings().stream().anyMatch(w -> w.code().equals("OVER_BUDGET")));
    }
    @Test void missingEventIsFailedWithoutFabricatingVenue() {
        var result = plan(Map.of("origin", "南京"), List.of());
        assertEquals(PlanStatus.FAILED, result.status()); assertNull(result.event());
        assertEquals(0, routes.calls.get());
    }
    @Test void independentReturnFareIsNotCopiedFromOutbound() {
        var result = plan(Map.of("origin", "南京", "modes", List.of("driving"), "ticketPaidMinor", 38000, "parkingMinor", 1000), List.of(event("测试馆", start, start.plusHours(3))));
        var costs = result.options().get(0).cost();
        assertTrue(costs.items().stream().anyMatch(c -> c.label().startsWith("返程") && Long.valueOf(2000).equals(c.minMinor())));
        assertTrue(costs.items().stream().anyMatch(c -> !c.label().startsWith("返程") && Long.valueOf(1000).equals(c.minMinor())));
        assertEquals(PlanStatus.OK, result.status());
    }
    @Test void largePartyCannotBeRecommendedAsOneOrdinaryVehicle() {
        var result = plan(Map.of("origin", "南京", "partySize", 20, "modes", List.of("driving")), List.of(event("测试馆", start, start.plusHours(3))));
        assertEquals(Feasibility.UNKNOWN, result.options().get(0).feasibility());
        assertTrue(result.options().get(0).cost().unknownCategories().contains("additional_vehicles"));
        assertNull(result.recommendedOptionId());
    }
    @Test void missedLastServiceIsNotRecommended() {
        routes.missReturn = true;
        var result = plan(Map.of("origin", "南京", "modes", List.of("transit")), List.of(event("测试馆", start, start.plusHours(3))));
        assertEquals(Feasibility.INFEASIBLE, result.options().get(0).returnJourney().feasibility());
        assertNull(result.recommendedOptionId());
        assertNull(result.options().get(0).returnJourney().arriveAt());
    }
    @Test void structuredClarificationIsNotAnMcpErrorAndCreatesUiFixture() throws Exception {
        eventPlanner = new EventJourneyPlanner((artist, city) -> List.of(event("测试馆", start, null)), planner);
        var tool = new JourneyMcpExecutor(planner, eventPlanner, properties);
        var response = tool.handleCall(new CallToolRequest("plan_event_journey", Map.of("artist", "测试艺人", "city", "上海站")), true);
        assertFalse(Boolean.TRUE.equals(response.isError()));
        assertEquals("NEEDS_CLARIFICATION", new ObjectMapper().readTree(((TextContent) response.content().get(0)).text()).path("status").asText());
        var plan = eventPlanner.plan(request(Map.of("origin", "南京", "partySize", 2, "modes", List.of("driving")), true));
        Path output = Path.of("target/journey-ui-fixture.json"); Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValue(output.toFile(), plan);
        assertTrue(tool.journeyToolSpecification().tool().annotations().readOnlyHint());
        assertTrue(tool.eventJourneyToolSpecification().tool().annotations().readOnlyHint());
    }
    private class StubRoutes implements RouteGateway {
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
        boolean failTransit, missReturn;
        public Place resolve(String name, String city, boolean specific) {
            names.add(name);
            boolean nanjing = name.contains("南京");
            return new Place(name, name, nanjing ? "南京市" : "上海市", nanjing ? "320100" : name.equals("上海站") ? "310000" : "310101", "兴趣点",
                    new com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint(121.4, 31.2), "fixture", 1);
        }
        public List<RouteCandidate> routes(Place from, Place to, LocalDateTime at, String mode) {
            calls.incrementAndGet();
            if (failTransit && mode.equals("transit")) throw new JourneyProviderException("AMAP_TIMEOUT", "测试公交故障");
            boolean returning = to.name().contains("南京");
            long fare = returning ? 2000 : 1000;
            Segment segment = new Segment(mode.equals("transit") ? "bus" : mode, from, to, null, null, 60, 10000,
                    null, null, new CostItem("transport", "测试交通费用", fare, fare, "CNY", CostStatus.REFERENCE, CostScope.PER_PERSON, "fixture", null),
                    "fixture", false, null, returning && missReturn ? at.minusMinutes(1) : null);
            return List.of(new RouteCandidate(mode, mode, List.of(segment), List.of(), List.of(), 0));
        }
    }
}
