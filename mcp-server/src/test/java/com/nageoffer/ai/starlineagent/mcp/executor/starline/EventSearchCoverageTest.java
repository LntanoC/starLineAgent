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
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventSearchCoverageTest {
    final ArtistEventSearchProperties props = new ArtistEventSearchProperties();
    final CityDictionary cities = new CityDictionary(props);
    final EventPageExtractor extractor = new EventPageExtractor(cities, props);
    final OfficialSourceRegistryClient.OfficialSource source = new OfficialSourceRegistryClient.OfficialSource("测试票务", "example.com", "TICKETING");
    EventSearchQuery query(String city, int size, String cursor) { return new EventSearchQuery("测试艺人", city, "year", false, size, cursor); }
    WebSearchClient.RawResult raw(String text) { return new WebSearchClient.RawResult("测试艺人巡演", "https://example.com/event?id=1", text, Instant.now(), "测试票务"); }
    @Test void yearlessTourKeepsTwoCitiesAndDoesNotLendVenueToWuxi() {
        var drafts = extractor.extract(raw("杭州站 11.21，无锡站 12.20，演出场馆：杭州奥体中心"), source, query(null, 20, null), List.of("q"));
        assertEquals(2, drafts.size());
        assertEquals(List.of("杭州", "无锡"), drafts.stream().map(d -> d.item().city()).toList());
        assertTrue(drafts.stream().allMatch(d -> d.dateUnknown() && d.item().eventDate() == null));
        assertNull(drafts.get(1).item().venueName());
    }
    @Test void shanghaiHangzhouPageFiltersAfterSplittingAndCountersCountPages() {
        var search = mock(WebSearchClient.class); var registry = registry();
        when(search.search(anyString(), isNull(), anyInt())).thenReturn(List.of(raw("上海站 " + EventSearchQuery.today().plusDays(4) + "，杭州站 " + EventSearchQuery.today().plusDays(9))));
        try (var ignored = new CloseService(EventSearchTestFactory.service(search, props, registry))) {
            var result = ignored.service.search(query("杭州", 20, null));
            var json = new ObjectMapper().valueToTree(result);
            assertEquals(1, json.path("items").size()); assertEquals("杭州", json.path("items").get(0).path("city").asText());
            var coverage = json.path("coverage");
            assertTrue(coverage.path("rawResults").asInt() >= coverage.path("afterSource").asInt());
            assertTrue(coverage.path("afterSource").asInt() >= coverage.path("afterRelevance").asInt());
            assertTrue(coverage.path("afterRelevance").asInt() >= coverage.path("afterDate").asInt());
        }
    }
    @Test void dictionaryUsesPositionsRatherThanListOrder() {
        assertEquals(List.of("无锡", "宁波", "佛山"), cities.find("无锡站，宁波站，佛山站").stream().map(CityDictionary.Hit::city).toList());
    }
    @Test void dateFormsRangesSaleAndInvalidDates() {
        var parser = new DateExpressionParser(); var q = query(null, 20, null);
        for (String date : List.of("2026-11-21", "2026.11.21", "2026/11/21", "2026年11月21日 周六"))
            assertEquals(LocalDate.of(2026, 11, 21), parser.parse(date, q).get(0).date());
        assertEquals(2, parser.parse("11月21日-22日", q).size());
        assertTrue(parser.parse("11.21-11.22", q).stream().allMatch(DateExpressionParser.ParsedDate::unknown));
        var range = parser.parse("2026-11-21 至 11-22", q);
        assertEquals(LocalDate.of(2026, 11, 22), range.get(1).date());
        assertEquals(LocalDate.of(2027, 1, 1), parser.parse("2026-12-31 至 01-01", q).get(1).date());
        assertTrue(parser.parse("2026-02-30", q).get(0).unknown());
        var sale = parser.parse("开票时间：2026-11-01 10:00，演出日期：2026-12-20", q);
        assertEquals(List.of(LocalDate.of(2026, 12, 20)), sale.stream().map(DateExpressionParser.ParsedDate::date).toList());
        var notice = parser.parse("公示日期：2026-11-01，演出日期：2026-12-20", q);
        assertEquals(List.of(LocalDate.of(2026, 12, 20)), notice.stream().map(DateExpressionParser.ParsedDate::date).toList());
    }
    @Test void redirectsNormalizeTrackingAndBlockUntrustedSchemesAndPrivateHosts() throws Exception {
        var resolver = new SourceResolver(uri -> new SourceResolver.Redirect(302, "https://m.example.com/show?id=123&spm=x&utm_source=y"));
        var result = resolver.resolve("https://short.example/x", List.of(source), true);
        assertEquals("https://m.example.com/show?id=123", result.url());
        assertEquals(source, result.source());
        assertEquals("INVALID_URL", resolver.resolve("file:///secret", List.of(source), true).reason());
        assertEquals("INVALID_URL", new SourceResolver(uri -> new SourceResolver.Redirect(302, "http://user:pass@example.com/x")).resolve("https://short.example/x", List.of(source), true).reason());
        assertEquals("REDIRECT_UNRESOLVED", new SourceResolver(uri -> new SourceResolver.Redirect(302, "/loop")).resolve("https://short.example/x", List.of(source), true).reason());
        for (String ip : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "::ffff:127.0.0.1")) assertFalse(SourceResolver.isPublic(InetAddress.getByName(ip)), ip);
    }
    @Test void nextPageReusesSnapshotAndDisabledSourceInvalidatesCursor() throws Exception {
        var registry = registry(); var search = mock(WebSearchClient.class);
        when(search.search(anyString(), isNull(), anyInt())).thenReturn(List.of(raw("杭州站 " + EventSearchQuery.today().plusDays(4)),
                new WebSearchClient.RawResult("测试艺人巡演", "https://example.com/2", "无锡站 " + EventSearchQuery.today().plusDays(5), Instant.now(), "测试票务")));
        var service = EventSearchTestFactory.service(search, props, registry);
        try {
            var first = service.search(query(null, 1, null));
            String cursor = (String) first.get("nextCursor"); assertNotNull(cursor);
            clearInvocations(search);
            var second = service.search(query(null, 1, cursor));
            assertNull(second.get("nextCursor")); assertNotEquals(first.get("items"), second.get("items"));
            verifyNoInteractions(search); // Stable cursor paging must not repeat any provider query.
            when(registry.listEnabledSources()).thenReturn(List.of());
            assertThrows(IllegalArgumentException.class, () -> service.search(query(null, 1, cursor)));
        } finally { service.close(); }
    }
    @Test void oneProviderFailsButOtherProviderEventsSurviveAndUiFixtureIsRealJavaOutput() throws Exception {
        props.setReadDetails(false);
        WebSearchClient good = new WebSearchClient() {
            public List<RawResult> search(String q, String f, int c) { return List.of(raw("杭州站 " + EventSearchQuery.today().plusDays(4) + "，无锡站 12.20")); }
            public String providerName() { return "good"; }
        };
        WebSearchClient bad = new WebSearchClient() {
            public List<RawResult> search(String q, String f, int c) { throw new IllegalStateException("timeout"); }
            public String providerName() { return "bad"; }
        };
        var service = new EventSearchService(List.of(good, bad), props, registry(), new EventQueryPlanner(), extractor, new EventMerger(), new SourceResolver(), new ProviderRunner());
        try {
            var result = service.search(query(null, 1, null)); var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);
            assertEquals("PARTIAL", json.path("coverage").path("completeness").asText());
            assertEquals(1, json.path("items").size()); assertTrue(json.path("notes").toString().contains("日期待核实"));
            assertTrue(json.path("notes").toString().contains("覆盖可能不完整"));
            assertTrue(json.path("items").get(0).hasNonNull("sourceUrl"));
            new ObjectMapper().findAndRegisterModules().writeValue(new java.io.File("target/event-search-ui-fixture.json"), result);
        } finally { service.close(); }
    }
    @Test void providerCacheSingleflightAndCircuitAvoidRepeatedCalls() {
        AtomicInteger calls = new AtomicInteger();
        WebSearchClient provider = new WebSearchClient() {
            public List<RawResult> search(String q, String f, int c) { calls.incrementAndGet(); return List.of(raw("无锡站")); }
            public String providerName() { return "cache"; }
        };
        var runner = new ProviderRunner(); var request = new SearchProvider.SearchRequest("q", null, 0, 20, null);
        runner.run(provider, request); runner.run(provider, request); assertEquals(1, calls.get());
    }
    private OfficialSourceRegistryClient registry() { var registry = mock(OfficialSourceRegistryClient.class); when(registry.listEnabledSources()).thenReturn(List.of(source)); return registry; }
    record CloseService(EventSearchService service) implements AutoCloseable { public void close() { service.close(); } }
}
