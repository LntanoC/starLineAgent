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
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventDiscoveryRefinementTest {
    private final ArtistEventSearchProperties props=new ArtistEventSearchProperties();
    private final OfficialSourceRegistryClient registry=mock(OfficialSourceRegistryClient.class);
    private final OfficialSourceRegistryClient.OfficialSource source=new OfficialSourceRegistryClient.OfficialSource("票务","tickets.example","TICKETING");

    @Test void unofficialCityLeadTriggersOfficialVerificationButNeverBecomesACard() {
        when(registry.listEnabledSources()).thenReturn(List.of(source));
        var date=EventSearchQuery.today().plusDays(20);
        WebSearchClient client=(query,freshness,count) -> query.contains("赣州")
                ? List.of(new WebSearchClient.RawResult("测试艺人赣州站演唱会","https://tickets.example/show","演出日期："+date,Instant.now(),"票务"))
                : List.of(new WebSearchClient.RawResult("测试艺人赣州站演唱会","https://news.example/lead","演出线索",Instant.now(),"媒体"));
        var service=EventSearchTestFactory.service(client,props,registry);
        try {
            var result=new ObjectMapper().valueToTree(service.search(new EventSearchQuery("测试艺人",null,"year",false,20,null)));
            assertEquals(1,result.path("items").size());
            assertEquals("赣州",result.path("items").get(0).path("city").asText());
            assertTrue(result.path("items").get(0).path("sourceUrl").asText().startsWith("https://tickets.example/"));
            assertTrue(result.path("coverage").path("queries").toString().contains("赣州"));
            assertTrue(result.path("coverage").path("filtered").path("REDIRECT_UNRESOLVED").asInt()>0);
        } finally { service.close(); }
    }

    @Test void countedConsecutiveShowsReachTheCurrentExtractor() {
        var extractor=new EventPageExtractor(new CityDictionary(props),props);
        var query=new EventSearchQuery("测试艺人",null,"year",false,20,null);
        var raw=new WebSearchClient.RawResult("测试艺人赣州站演唱会","https://tickets.example/show",
                "演出日期：2026年12月4日（周五）—12月6日（周日），连开3场",Instant.now(),"票务");
        var drafts=extractor.extract(raw,source,query,List.of("query"));
        assertEquals(3,drafts.size());
        assertTrue(drafts.stream().noneMatch(EventPageExtractor.Draft::dateUnknown));
    }

    @Test void unknownPublicationAndMultipleSaleBatchesCannotSupplyReminderTime() {
        var extractor=new EventPageExtractor(new CityDictionary(props),props);
        var query=new EventSearchQuery("测试艺人",null,"year",false,20,null);
        var date=EventSearchQuery.today().plusDays(20);
        var raw=new WebSearchClient.RawResult("测试艺人赣州站演唱会","https://tickets.example/show",
                "开票时间："+date.minusDays(5)+" 17:17；预售时间："+date.minusDays(3)+" 10:00；演出日期："+date,null,"票务");
        var drafts=extractor.extract(raw,source,query,List.of("query"));
        assertTrue(drafts.get(0).item().isStale());
        assertNull(drafts.get(0).item().saleStartTime());
        assertFalse(drafts.get(0).item().canBuyTicket());
    }
    @Test void sparsePagesReturnUnusedRawBudgetToLaterQueries() {
        props.setMaxRawResults(40);
        when(registry.listEnabledSources()).thenReturn(List.of(source));
        WebSearchClient client=(query,freshness,count) -> List.of(new WebSearchClient.RawResult(
                "测试艺人赣州站演唱会","https://tickets.example/show","演出日期："+EventSearchQuery.today().plusDays(20),Instant.now(),"票务"));
        var service=EventSearchTestFactory.service(client,props,registry);
        try {
            var result=new ObjectMapper().valueToTree(service.search(new EventSearchQuery("测试艺人",null,"year",false,20,null)));
            assertTrue(result.path("coverage").path("queries").size()>4);
            assertTrue(result.path("coverage").path("rawResults").asInt()<40);
            assertTrue(result.path("coverage").path("failures").isEmpty(),result.path("coverage").path("failures").toString());
        } finally { service.close(); }
    }

    @Test void endedRecapsDoNotBecomeUpcomingUnknownDateCardsOrFollowupCities() {
        when(registry.listEnabledSources()).thenReturn(List.of(source));
        WebSearchClient client=(q,f,n) -> List.of(new WebSearchClient.RawResult("测试艺人北京演唱会圆满收官", "https://tickets.example/review", "演出回顾",Instant.now(),"票务"));
        var service=EventSearchTestFactory.service(client,props,registry);
        try {
            var result=new ObjectMapper().valueToTree(service.search(new EventSearchQuery("测试艺人",null,"recent",false,20,null)));
            assertEquals(0,result.path("items").size());
            assertTrue(result.path("coverage").path("filtered").path("ENDED_RECAP").asInt()>0);
            assertFalse(result.path("coverage").path("queries").toString().contains("北京"));
        } finally { service.close(); }
    }

}
