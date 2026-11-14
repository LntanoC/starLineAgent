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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrawlSearchIntegrationTest {
    @TempDir Path temp;
    final OfficialSourceRegistryClient registry=mock(OfficialSourceRegistryClient.class);
    final List<OfficialSource> sources=List.of(new OfficialSource("文旅公示","gov.cn","GOV"));
    EventCrawlIndex index() {
        var props=new EventCrawlProperties();props.setEnabled(true);props.setDirectory(temp.toString());props.setArtists(List.of());
        return new EventCrawlIndex(props,registry,url->{throw new AssertionError("query cannot fetch crawl sites");});
    }
    CrawlRecord record(String id,int days) {
        var date=EventSearchQuery.today().plusDays(days);
        return new CrawlRecord("beijing",id,"测试艺人","测试艺人演唱会-北京站","北京","鸟巢",date,date,"P-"+id,"设立",
                "https://banshi.whlyj.beijing.gov.cn/cms/web/whj/content.jsp?id="+id,null,System.currentTimeMillis());
    }
    void update(EventCrawlIndex index,CrawlRecord... records) {index.update("测试艺人",new EventSourceCrawler.Batch("beijing","PARTIAL",List.of(records),List.of("PAGE_LIMIT"),System.currentTimeMillis()));}
    EventSearchService service(EventCrawlIndex index,List<WebSearchClient> providers) {
        var p=new ArtistEventSearchProperties();p.setReadDetails(false);
        return new EventSearchService(providers,p,registry,new EventQueryPlanner(),new EventPageExtractor(new CityDictionary(p),p),new EventMerger(),
                new SourceResolver(uri->{throw new IllegalStateException("no redirects");}),new ProviderRunner(),index);
    }
    @Test void crawlerResultsWorkWithoutAnySearchProvider() {
        when(registry.listEnabledSources()).thenReturn(sources);
        try(var index=index()) {
            update(index,record("1",4));var service=service(index,List.of());
            try {
                var json=new ObjectMapper().valueToTree(service.search(Map.of("artist","测试艺人")));
                assertEquals(1,json.path("items").size());assertEquals(1,json.path("coverage").path("crawledCandidates").asInt());
                assertEquals("PARTIAL",json.path("coverage").path("completeness").asText());assertFalse(json.path("items").get(0).path("canBuyTicket").asBoolean());
                assertTrue(json.path("notes").toString().contains("后台直接采集"));
            } finally {service.close();}
        }
    }
    @Test void providerFailureDoesNotEraseDirectResults() {
        when(registry.listEnabledSources()).thenReturn(sources);
        WebSearchClient provider=(q,f,c)->{throw new IllegalStateException("RATE_LIMIT");};
        try(var index=index()) {
            update(index,record("1",4));var service=service(index,List.of(provider));
            try {var result=service.search(Map.of("artist","测试艺人"));assertEquals(1,((List<?>)result.get("items")).size());}
            finally {service.close();}
        }
    }
    @Test void cursorKeepsOriginalSnapshotWhenBackgroundIndexChanges() {
        when(registry.listEnabledSources()).thenReturn(sources);
        try(var index=index()) {
            update(index,record("1",4),record("2",8));var service=service(index,List.of());
            try {
                var page=service.search(Map.of("artist","测试艺人","pageSize",1));String cursor=(String)page.get("nextCursor");assertNotNull(cursor);
                update(index,record("3",10));
                var next=service.search(Map.of("artist","测试艺人","pageSize",1,"cursor",cursor));assertEquals(1,((List<?>)next.get("items")).size());assertNull(next.get("nextCursor"));
                var fresh=service.search(Map.of("artist","测试艺人"));assertEquals(3,((List<?>)fresh.get("items")).size());
                when(registry.listEnabledSources()).thenReturn(List.of());
                assertThrows(IllegalArgumentException.class,()->service.search(Map.of("artist","测试艺人","pageSize",1,"cursor",cursor)));
                assertTrue(((List<?>)service.search(Map.of("artist","测试艺人")).get("items")).isEmpty());
            } finally {service.close();}
        }
    }
    @Test void directParsedNoticeReplacesHeuristicDateExpansionForSamePage() {
        when(registry.listEnabledSources()).thenReturn(sources);
        try(var index=index()) {
            var r=record("1",4);r=new CrawlRecord(r.sourceId(),r.documentId(),r.artist(),r.title(),r.city(),r.venue(),r.approvedFrom(),r.approvedTo().plusDays(3),r.approvalNo(),r.changeType(),r.sourceUrl(),null,r.fetchedAt());
            update(index,r);String url=r.sourceUrl();String text="测试艺人演唱会 北京站 准予许可 演出时间："+r.approvedFrom()+"至"+r.approvedTo();
            WebSearchClient provider=(q,f,c)->List.of(new WebSearchClient.RawResult("测试艺人演出许可",url,text,Instant.now(),"文旅"));
            var service=service(index,List.of(provider));
            try {
                var json=new ObjectMapper().valueToTree(service.search(Map.of("artist","测试艺人")));
                assertEquals(1,json.path("items").size());assertTrue(json.path("items").get(0).path("dateUnknown").asBoolean());assertTrue(json.path("items").get(0).path("eventDate").isNull());
                assertTrue(json.path("coverage").path("filtered").path("DIRECT_CRAWL_REPLACED").asInt()>0);
            } finally {service.close();}
        }
    }
    @Test void cancellationConflictSurvivesEventMerge() {
        when(registry.listEnabledSources()).thenReturn(sources);
        try(var index=index()) {
            var r=record("1",4);r=new CrawlRecord(r.sourceId(),r.documentId(),r.artist(),r.title(),r.city(),r.venue(),r.approvedFrom(),r.approvedTo(),r.approvalNo(),"取消",r.sourceUrl(),null,r.fetchedAt());
            update(index,r);var service=service(index,List.of());
            try {var json=new ObjectMapper().valueToTree(service.search(Map.of("artist","测试艺人")));assertTrue(json.path("items").get(0).path("conflict").asBoolean());assertFalse(json.path("items").get(0).path("canBuyTicket").asBoolean());}
            finally {service.close();}
        }
    }
    @Test void equivalentApprovalChangesMergeEvidenceWithoutCountingAnotherShow() {
        when(registry.listEnabledSources()).thenReturn(sources);
        try(var index=index()) {
            var a=record("1",4);var b=record("2",4);
            a=new CrawlRecord(a.sourceId(),a.documentId(),a.artist(),a.title(),a.city(),a.venue(),a.approvedFrom(),a.approvedTo().plusDays(5),a.approvalNo(),"设立",a.sourceUrl(),null,a.fetchedAt());
            b=new CrawlRecord(b.sourceId(),b.documentId(),b.artist(),b.title(),b.city(),b.venue(),b.approvedFrom(),b.approvedTo().plusDays(5),b.approvalNo(),"增加演员",b.sourceUrl(),null,b.fetchedAt());
            update(index,a,b);var service=service(index,List.of());
            try {var json=new ObjectMapper().valueToTree(service.search(Map.of("artist","测试艺人")));assertEquals(1,json.path("items").size());assertEquals(2,json.path("items").get(0).path("sourceEvidence").size());assertTrue(json.path("items").get(0).path("dateUnknown").asBoolean());}
            finally {service.close();}
        }
    }
}
