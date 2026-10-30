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

import com.nageoffer.ai.starlineagent.mcp.executor.McpToolException;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripLeg;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripPlan;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripPlanInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripTimeCalculatorTest {

    private final TripTimeCalculator calculator = new TripTimeCalculator();
    private final LocalDateTime eventStart = LocalDateTime.parse("2026-11-15T19:30:00");

    @Test
    void calculatesLatestDepartureForLocalRoute() {
        TripPlan plan = calculator.calculate(input(eventStart, null, null, null), route(42), 30);

        assertThat(plan.arriveVenueBy()).isEqualTo(LocalDateTime.parse("2026-11-15T19:00:00"));
        assertThat(plan.latestDeparture()).isEqualTo(LocalDateTime.parse("2026-11-15T18:18:00"));
        assertThat(plan.intercityLeg()).isNull();
    }

    @Test
    void usesUserProvidedArrivalPlaceWithoutInventingIntercityDuration() {
        TripPlan plan = calculator.calculate(input(eventStart, LocalDateTime.parse("2026-11-15T18:30:00"),
                "无锡东站", null), route(42), 30);

        assertThat(plan.localLeg().from()).isEqualTo("无锡东站");
        assertThat(plan.intercityLeg()).isNull();
        assertThat(plan.latestDeparture()).isEqualTo(LocalDateTime.parse("2026-11-15T18:18:00"));
        assertThat(plan.assumptions()).anyMatch(value -> value.contains("城际到达时间晚于市内最晚出发时间"));
    }

    @Test
    void handlesDepartureOnPreviousDay() {
        LocalDateTime midnightEvent = LocalDateTime.parse("2026-11-15T00:30:00");
        TripPlan plan = calculator.calculate(input(midnightEvent, null, null, null), route(60), 30);

        assertThat(plan.latestDeparture()).isEqualTo(LocalDateTime.parse("2026-11-14T23:00:00"));
    }

    @Test
    void neverReplacesUnavailableRouteWithAnEstimate() {
        assertThatThrownBy(() -> calculator.calculate(input(eventStart, null, null, null),
                (from, to, mode, departAt) -> { throw new McpToolException("route unavailable"); }, 30))
                .isInstanceOf(McpToolException.class).hasMessage("route unavailable");
    }

    @Test
    void acceptsZeroArrivalBuffer() {
        TripPlan plan = calculator.calculate(input(eventStart, null, null, 0), route(42), 30);

        assertThat(plan.arriveVenueBy()).isEqualTo(eventStart);
        assertThat(plan.latestDeparture()).isEqualTo(LocalDateTime.parse("2026-11-15T18:48:00"));
    }

    @Test
    void acceptsBufferLongerThanRouteDuration() {
        TripPlan plan = calculator.calculate(input(eventStart, null, null, 120), route(42), 30);

        assertThat(plan.latestDeparture()).isEqualTo(LocalDateTime.parse("2026-11-15T16:48:00"));
    }

    private RouteProvider route(int durationMinutes) {
        return (from, to, mode, departAt) -> new TripLeg(from, to, mode, durationMinutes, 13_400, "amap");
    }

    private TripPlanInput input(LocalDateTime start, LocalDateTime arrivalTime, String arrivalPlace, Integer buffer) {
        return new TripPlanInput("无锡大剧院", "无锡市", start, arrivalTime, arrivalPlace, "driving", buffer);
    }
}
