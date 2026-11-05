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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;
import static org.junit.jupiter.api.Assertions.*;

class AmapContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode fixture(String file) throws Exception {
        try (var stream = getClass().getResourceAsStream("/amap/" + file + ".json")) { return mapper.readTree(stream); }
    }
    private final Place from = new Place("测试起点", "测试地址", "南京市", "320100", "兴趣点", new com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint(118.8, 32), "fixture", 1);
    private final Place to = new Place("测试终点", "测试地址", "上海市", "310100", "兴趣点", new com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint(121.5, 31.2), "fixture", 1);
    @Test void geocodeLevelsAndPoiRemainDistinct() throws Exception {
        assertTrue(AmapPlaceClient.broad(AmapPlaceClient.parse(fixture("geocode-city").path("geocodes").get(0), "上海", false)));
        assertTrue(AmapPlaceClient.broad(AmapPlaceClient.parse(fixture("geocode-broad").path("geocodes").get(0), "中国", false)));
        assertEquals("兴趣点", AmapPlaceClient.parse(fixture("geocode-poi").path("pois").get(0), "测试体育馆", true).level());
    }
    @Test void municipalityProvinceLevelIsStillUsableAsAnExplicitCity() throws Exception {
        String body = fixture("geocode-municipality").toString();
        AtomicInteger requests = new AtomicInteger();
        var http = new AmapHttpClient(new MockEnvironment().withProperty("AMAP_API_KEY", "fixture"), new TripPlanProperties(), request -> {
            requests.incrementAndGet(); return new AmapHttpClient.Response(200, body);
        });
        Place result = new AmapPlaceClient(http).resolve("上海市", null, false);
        assertEquals("市", result.level());
        assertTrue(AmapPlaceClient.broad(result));
        assertTrue(result.confidence() < 0.8);
        assertEquals(1, requests.get());
    }
    @Test void localTransitKeepsFareAndStationServiceWindow() throws Exception {
        var result = AmapTransitClient.parse(fixture("transit-local"), from, to, LocalDate.of(2030, 11, 21)).get(0);
        assertEquals(3, result.segments().size());
        assertEquals(600, result.extraCosts().get(0).minMinor());
        assertEquals("2030-11-21T23:00", result.segments().get(1).lastServiceDeparture().toString());
    }
    @Test void railwayUsesOnlyProviderServiceAndCrossDayTimesWithSeparateFare() throws Exception {
        var result = AmapTransitClient.parse(fixture("transit-cross-city"), from, to, LocalDate.of(2030, 11, 21)).get(0);
        Segment rail = result.segments().stream().filter(s -> "rail".equals(s.mode())).findFirst().orElseThrow();
        assertEquals("FIXTURE-G1", rail.serviceNo());
        assertEquals("2030-11-22T00:30", rail.arriveAt().toString());
        assertEquals(60, rail.durationMinutes());
        assertEquals(13550, rail.fare().minMinor());
        assertEquals(20000, rail.fare().maxMinor());
        assertTrue(result.extraCosts().stream().anyMatch(c -> c.status() == CostStatus.UNKNOWN));
    }
    @Test void noTransitIsAnEmptyCandidateSetAndMissingTollStaysUnknown() throws Exception {
        assertEquals(List.of(), AmapTransitClient.parse(fixture("transit-empty"), from, to, LocalDate.of(2030, 11, 21)));
        var result = AmapDrivingClient.parse(fixture("driving"), from, to, "driving", new TripPlanProperties()).get(0);
        assertNull(result.extraCosts().get(0).minMinor());
        assertEquals(CostStatus.UNKNOWN, result.extraCosts().get(0).status());
        assertEquals(3850, AmapDrivingClient.parse(fixture("driving-tolls"), from, to, "driving", new TripPlanProperties()).get(0).extraCosts().get(0).minMinor());
    }
    @Test void httpRetriesIdempotentFailuresOnceAndThenCaches() {
        AtomicInteger calls = new AtomicInteger();
        var client = new AmapHttpClient(new MockEnvironment().withProperty("AMAP_API_KEY", "test-only"), new TripPlanProperties(), request ->
                new AmapHttpClient.Response(calls.incrementAndGet() == 1 ? 503 : 200, "{\"status\":\"1\",\"infocode\":\"10000\"}"));
        client.get("/geocode/geo", Map.of("address", "测试"));
        client.get("/geocode/geo", Map.of("address", "测试"));
        assertEquals(2, calls.get());
    }
    @Test void keyRejectionDoesNotRetryOrExposeCredentials() {
        AtomicInteger calls = new AtomicInteger();
        var client = new AmapHttpClient(new MockEnvironment().withProperty("AMAP_API_KEY", "test-secret"), new TripPlanProperties(), request -> {
            calls.incrementAndGet(); return new AmapHttpClient.Response(200, "{\"status\":\"0\",\"infocode\":\"10001\",\"info\":\"test-secret\"}");
        });
        var error = assertThrows(JourneyProviderException.class, () -> client.get("/geocode/geo", Map.of("address", "敏感地址")));
        assertEquals(1, calls.get());
        assertFalse(error.getMessage().contains("test-secret"));
        assertFalse(error.getMessage().contains("敏感地址"));
    }
    @Test void circuitOpensAfterFiveFailures() {
        AtomicInteger calls = new AtomicInteger();
        var client = new AmapHttpClient(new MockEnvironment().withProperty("AMAP_API_KEY", "fixture"), new TripPlanProperties(), request -> {
            calls.incrementAndGet(); throw new java.io.IOException("must not leak");
        });
        for (int i = 0; i < 5; i++) assertThrows(JourneyProviderException.class, () -> client.get("/geocode/geo", Map.of("address", "测试")));
        assertEquals("AMAP_CIRCUIT_OPEN", assertThrows(JourneyProviderException.class, () -> client.get("/geocode/geo", Map.of("address", "测试"))).code());
        assertEquals(10, calls.get());
    }
}
