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

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;
import static org.junit.jupiter.api.Assertions.*;

class JourneyCalculatorTest {
    private final LocalDateTime day = LocalDateTime.parse("2030-11-21T19:30:00");
    private final Place place = new Place("测试地点", "测试地址", "上海", "310101", "兴趣点", null, "fixture", 1);
    private Segment leg(String mode, int minutes, LocalDateTime departure, LocalDateTime arrival) {
        return new Segment(mode, place, place, departure, arrival, minutes, null, null, null, null, "fixture", departure != null, null, null);
    }
    private CostItem amount(long min, long max, CostScope scope) { return new CostItem("transport", "测试费用", min, max, "CNY", CostStatus.REFERENCE, scope, "fixture", null); }

    @Test void backwardAndForwardSupportMidnightAndZeroBuffer() {
        var calculator = new JourneyTimeCalculator();
        var backward = calculator.calculate(List.of(leg("driving", 60, null, null)), null, day.toLocalDate().atTime(0, 15), 0, null);
        assertEquals(day.toLocalDate().minusDays(1).atTime(23, 15), backward.departure());
        assertEquals(60, backward.travelMinutes());
        assertEquals(Feasibility.TIGHT, backward.feasibility());
        var forward = calculator.calculate(List.of(leg("driving", 60, null, null)), day, null, 0, null);
        assertEquals(day.plusHours(1), forward.arrival());
        assertEquals(Feasibility.FEASIBLE, forward.feasibility());
    }
    @Test void scheduledMultiLegAddsStationBufferAndActualWaiting() {
        var result = new JourneyTimeCalculator().calculate(List.of(leg("walk", 10, null, null),
                leg("rail", 60, day.plusHours(1), day.plusHours(2)), leg("walk", 10, null, null)), day, null, 30, null);
        assertEquals(130, result.travelMinutes());
        assertEquals(20, result.segments().stream().filter(s -> "wait".equals(s.mode())).findFirst().orElseThrow().durationMinutes());
        assertEquals(30, result.segments().stream().filter(s -> "station_buffer".equals(s.mode())).findFirst().orElseThrow().durationMinutes());
    }
    @Test void backwardSchedulesKeepSourceTimesAndCanBeInfeasible() {
        var result = new JourneyTimeCalculator().calculate(List.of(leg("rail", 60, day, day.plusHours(1)), leg("walk", 10, null, null)), null, day.plusMinutes(50), 30, null);
        assertEquals(Feasibility.INFEASIBLE, result.feasibility());
        assertEquals(-20, result.slackMinutes());
        assertEquals(day, result.segments().stream().filter(s -> "rail".equals(s.mode())).findFirst().orElseThrow().departAt());
    }
    @Test void unknownScheduleAndPastDepartureAreExplicit() {
        assertEquals(Feasibility.UNKNOWN, new JourneyTimeCalculator().calculate(List.of(leg("metro", 20, null, null)), day, null, 0, null).feasibility());
        assertEquals(Feasibility.INFEASIBLE, new JourneyTimeCalculator().calculate(List.of(leg("driving", 20, null, null)), day, null, 0, day.plusMinutes(1)).feasibility());
    }
    @Test void feasibilityBoundariesAreNotCopiedFromContradictoryExample() {
        assertEquals(Feasibility.FEASIBLE, JourneyTimeCalculator.evaluate(15, false, false));
        assertEquals(Feasibility.TIGHT, JourneyTimeCalculator.evaluate(14, false, false));
        assertEquals(Feasibility.TIGHT, JourneyTimeCalculator.evaluate(0, false, false));
        assertEquals(Feasibility.INFEASIBLE, JourneyTimeCalculator.evaluate(-1, true, false));
        assertEquals(Feasibility.UNKNOWN, JourneyTimeCalculator.evaluate(15, true, false));
    }
    @Test void arrivingThirtySecondsLateIsStillInfeasible() {
        var result = new JourneyTimeCalculator().calculate(List.of(leg("rail", 60, day, day.plusHours(1))),
                null, day.plusHours(1).minusSeconds(30), 0, null);
        assertEquals(-1, result.slackMinutes());
        assertEquals(Feasibility.INFEASIBLE, result.feasibility());
    }
    @Test void partyAndVehicleCostsRangesAndUnknownsAreSeparated() {
        var result = CostCalculator.summarize(List.of(amount(10000, 12000, CostScope.PER_PERSON), amount(33000, 33000, CostScope.PER_VEHICLE),
                unknown("ticket", "门票", CostScope.PER_PERSON, "缺少来源")), 2);
        assertEquals(53000, result.minTotalMinor());
        assertEquals(57000, result.maxTotalMinor());
        assertEquals(26500, result.perPersonMinMinor());
        assertEquals(List.of("ticket"), result.unknownCategories());
        assertNull(result.items().get(2).minMinor());
    }
    @Test void allUnknownDoesNotBecomeFreeAndCurrenciesCannotMix() {
        var result = CostCalculator.summarize(List.of(unknown("rail", "铁路", CostScope.PER_PERSON, "未核实")), 2);
        assertNull(result.minTotalMinor()); assertNull(result.perPersonMinMinor());
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.summarize(List.of(new CostItem("rail", "USD", 100L, 100L, "USD", CostStatus.REFERENCE, CostScope.PER_PERSON, "fixture", null)), 1));
        assertThrows(IllegalArgumentException.class, () -> new CostItem("rail", "未知", 0L, 0L, "CNY", CostStatus.UNKNOWN, CostScope.PER_PERSON, null, null));
        assertThrows(IllegalArgumentException.class, () -> amount(-1, 0, CostScope.PER_PARTY));
    }
    @Test void fuelFormulaAndYuanConversionAreExact() {
        assertEquals(6400, CostCalculator.fuel(100000, new BigDecimal("8"), new BigDecimal("8")).minMinor());
        assertEquals(13550, CostCalculator.minor("135.50"));
        assertNull(CostCalculator.minor("未知")); assertNull(CostCalculator.minor("1.001"));
    }
    @Test void tourStopDoesNotRewriteRailwayOrigin() {
        assertEquals("上海", TourStopParser.city("上海站"));
        assertEquals("上海", TourStopParser.city("上海场"));
        var request = JourneyMcpExecutor.parse(java.util.Map.of("origin", "上海站", "destination", "梅奔", "departAt", day.toString()), false, 30);
        assertEquals("上海站", request.origin());
        assertThrows(IllegalArgumentException.class, () -> TourStopParser.city("从上海站出发"));
    }
    @Test void badParametersAreNotSilentlyTruncatedOrDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> JourneyMcpExecutor.parse(java.util.Map.of("partySize", 1.5), false, 30));
        assertThrows(IllegalArgumentException.class, () -> JourneyMcpExecutor.parse(java.util.Map.of("partySize", 0), false, 30));
        assertThrows(IllegalArgumentException.class, () -> JourneyMcpExecutor.parse(java.util.Map.of("departAt", day.toString(), "arriveBy", day.toString()), false, 30));
        assertThrows(IllegalArgumentException.class, () -> JourneyMcpExecutor.parse(java.util.Map.of("showStartTime", "2030-11-21"), true, 30));
    }
}
