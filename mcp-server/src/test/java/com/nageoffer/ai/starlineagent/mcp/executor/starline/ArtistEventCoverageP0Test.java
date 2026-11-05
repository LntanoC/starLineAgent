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
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtistEventCoverageP0Test {
    @Test void twelveResultsPreserveThreeCitiesAndExposePagination() throws Exception {
        var mapper = new ObjectMapper();
        var search = mock(WebSearchClient.class);
        var registry = mock(OfficialSourceRegistryClient.class);
        when(registry.listEnabledSources()).thenReturn(List.of(new OfficialSourceRegistryClient.OfficialSource("测试票务", "example.com", "TICKETING")));
        List<WebSearchClient.RawResult> rows = new ArrayList<>();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/event-search/twelve-results.json"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (var entry : fixture) {
            String city = entry.path("city").asText();
            rows.add(new WebSearchClient.RawResult("测试艺人" + city + "演唱会", "https://example.com/event/" + rows.size(),
                    "演出日期：" + today.plusDays(entry.path("dayOffset").asInt()), Instant.now(), "测试票务"));
        }
        when(search.search(anyString(), isNull(), anyInt())).thenReturn(rows);
        var executor = new ArtistEventSearchMcpExecutor(EventSearchTestFactory.service(search, new ArtistEventSearchProperties(), registry));
        var response = executor.handleCall(new CallToolRequest("search_artist_events", Map.of("artist", "测试艺人", "window", "year", "pageSize", 10)));
        var result = mapper.readTree(((TextContent) response.content().get(0)).text());
        assertEquals(10, result.path("items").size());
        Set<String> cities = new HashSet<>();
        result.path("items").forEach(item -> cities.add(item.path("city").asText()));
        assertEquals(Set.of("杭州", "上海", "无锡"), cities);
        assertEquals(12, result.path("coverage").path("merged").asInt());
        assertEquals(2, result.path("coverage").path("truncated").asInt());
        assertEquals("PARTIAL", result.path("coverage").path("completeness").asText());
        assertTrue(result.path("notes").toString().contains("还有 2 条"));
    }
}
