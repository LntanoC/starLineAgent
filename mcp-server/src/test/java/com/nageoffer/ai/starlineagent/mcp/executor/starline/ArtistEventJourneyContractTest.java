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

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtistEventJourneyContractTest {
    @Test void sameDateDifferentVenuesStaySeparateAndFactsKeepTheirSources() {
        WebSearchClient search = mock(WebSearchClient.class);
        OfficialSourceRegistryClient registry = mock(OfficialSourceRegistryClient.class);
        when(registry.listEnabledSources()).thenReturn(List.of(new OfficialSourceRegistryClient.OfficialSource("测试票务", "example.com", "TICKETING")));
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(20);
        when(search.search(anyString(), isNull(), anyInt())).thenReturn(List.of(
                raw(date, "测试馆A", "/one"), raw(date, "测试馆B", "/two"), raw(date, "测试馆A", "/three")));
        var client = new ArtistEventSearchMcpExecutor(EventSearchTestFactory.service(search, new ArtistEventSearchProperties(), registry));
        var items = client.searchEvents("测试艺人", "上海");
        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(e -> e.showStartTime().equals(date.atTime(19, 30))));
        assertTrue(items.stream().allMatch(e -> e.venueSource().startsWith("https://example.com/")));
        assertTrue(items.stream().allMatch(e -> e.priceRange().minMinor().equals(38000L)));
        assertTrue(items.stream().anyMatch(e -> e.sourceEvidence().size() == 2));
    }
    private WebSearchClient.RawResult raw(LocalDate date, String venue, String path) {
        return new WebSearchClient.RawResult("测试艺人上海演唱会", "https://example.com" + path,
                "测试艺人上海演唱会，演出时间：" + date + " 19:30，演出场馆：" + venue + "，票价：380/680元", Instant.now(), "测试票务");
    }
}
