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

import com.nageoffer.ai.starlineagent.mcp.executor.starline.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventCrawlIndexTest {
    @TempDir Path temp;
    final List<OfficialSource> sources=List.of(new OfficialSource("文旅","gov.cn","GOV"),new OfficialSource("大麦","damai.cn","TICKETING"));
    EventCrawlProperties props() {var p=new EventCrawlProperties();p.setEnabled(true);p.setDirectory(temp.toString());p.setArtists(List.of());return p;}
    EventSearchQuery query(String city) {return new EventSearchQuery("测试艺人",city,"year",false,20,null);}
    CrawlRecord record(String id,LocalDate from,LocalDate to,long fetched) {
        return new CrawlRecord("beijing",id,"测试艺人","测试艺人巡演-北京站","北京","鸟巢",from,to,"APP-"+id,"增加演员",
                "https://banshi.whlyj.beijing.gov.cn/cms/web/whj/content.jsp?id="+id,"https://banshi.whlyj.beijing.gov.cn/publish/"+id+".pdf",fetched);
    }
    EventSourceCrawler.Batch batch(String status,List<CrawlRecord> records) {return new EventSourceCrawler.Batch("beijing",status,records,List.of(),System.currentTimeMillis());}
    @Test void approvalRangeDoesNotExpandToDailySessionsAndNeverAllowsBuying() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError("no network on read");})) {
            var from=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",from,from.plusDays(15),System.currentTimeMillis()))));
            var view=index.read(query(null),sources);assertEquals(1,view.drafts().size());var d=view.drafts().get(0);
            assertTrue(d.dateUnknown());assertNull(d.item().eventDate());assertFalse(d.item().canBuyTicket());assertTrue(d.item().approvedNote().contains(from.toString()));
            assertNull(d.item().showStartTime()); assertEquals("GOV",d.item().tier());
        }
    }
    @Test void exactSingleDateIsReturnedButStillIsOnlyAnApproval() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",date,date,System.currentTimeMillis()))));
            var item=index.read(query(null),sources).drafts().get(0).item(); assertEquals(date,item.eventDate());assertEquals("APPROVED",item.stage());assertFalse(item.canBuyTicket());
        }
    }
    @Test void disabledSourceImmediatelyHidesCachedDataAndBlockedAttachmentIsRejected() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",date,date,System.currentTimeMillis()))));
            assertTrue(index.read(query(null),List.of()).drafts().isEmpty());
            assertTrue(index.read(query(null),List.of(new OfficialSource("错误层级","gov.cn","TICKETING"))).drafts().isEmpty());
            assertEquals(1,index.read(query(null),sources).drafts().size());
        }
    }
    @Test void cityAndWindowFilteringUsesApprovalIntervalOverlap() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var today=EventSearchQuery.today();
            index.update("测试艺人",batch("OK",List.of(record("past",today.minusDays(5),today.minusDays(1),1),record("far",today.plusYears(3),today.plusYears(3),1),record("overlap",today.minusDays(2),today.plusDays(3),1))));
            assertEquals(1,index.read(query(null),sources).drafts().size());assertTrue(index.read(query("上海"),sources).drafts().isEmpty());
        }
    }
    @Test void failedRefreshPreservesOldRecordsAndLastSuccessButExposesFailureAndStaleness() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",date,date,1))));
            long success=(long)index.read(query(null),sources).sources().get(0).get("lastSuccessAt");
            index.update("测试艺人",batch("HTTP_403",List.of()));var view=index.read(query(null),sources);
            assertEquals(1,view.drafts().size());assertTrue(view.drafts().get(0).item().isStale());assertEquals("HTTP_403",view.sources().get(0).get("status"));
            assertEquals(success,view.sources().get(0).get("lastSuccessAt"));
        }
    }
    @Test void partialUpdatesMergeByDocumentWithoutDeletingUnscannedRecords() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",date,date,1),record("2",date.plusDays(2),date.plusDays(2),1))));
            index.update("测试艺人",batch("PARTIAL",List.of(record("1",date,date,2))));
            assertEquals(2,index.read(query(null),sources).drafts().size());
        }
    }
    @Test void restartRestoresPersistedIndex() {
        var props=props();var registry=mock(OfficialSourceRegistryClient.class);var date=EventSearchQuery.today().plusDays(4);
        try(var first=new EventCrawlIndex(props,registry,url->{throw new AssertionError();})) {first.update("测试艺人",batch("OK",List.of(record("1",date,date,System.currentTimeMillis()))));}
        try(var next=new EventCrawlIndex(props,registry,url->{throw new AssertionError();})) {assertEquals(1,next.read(query(null),sources).drafts().size());assertEquals("OK",next.status().get("storageStatus"));}
    }
    @Test void corruptIndexIsReportedAndNotOverwritten() throws Exception {
        Files.writeString(temp.resolve("index.json"),"not json");
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            index.read(query(null),sources);assertEquals("STORE_READ_FAILED",index.status().get("storageStatus"));assertEquals("not json",Files.readString(temp.resolve("index.json")));
        }
    }
    @Test void persistenceFailureIsVisibleInsteadOfPretendingDurability() throws Exception {
        Path file=temp.resolve("file");Files.writeString(file,"x");var props=props();props.setDirectory(file.toString());
        try(var index=new EventCrawlIndex(props,mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            index.read(query(null),sources);assertEquals("STORE_WRITE_FAILED",index.status().get("storageStatus"));
        }
    }
    @Test void disabledCrawlerDoesNotWriteFilesOrPerformNetwork() {
        var props=props();props.setEnabled(false);
        try(var index=new EventCrawlIndex(props,mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            index.tick();assertTrue(index.read(query(null),sources).sources().isEmpty());assertFalse(Files.exists(temp.resolve("index.json")));
        }
    }
    @Test void watchLimitAndSafeSingleFilename() {
        var props=props();props.setMaxArtists(1);
        try(var index=new EventCrawlIndex(props,mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            index.read(query(null),sources);
            var second=index.read(new EventSearchQuery("../../艺人",null,"year",false,20,null),sources);
            assertEquals("WATCH_LIMIT",second.sources().get(0).get("status"));assertEquals(1,index.status().get("watchCount"));
        }
    }
    @Test void registryFailureDoesNotEraseRecordsOrTriggerRequests() {
        var registry=mock(OfficialSourceRegistryClient.class);when(registry.listEnabledSources()).thenThrow(new IllegalStateException());
        try(var index=new EventCrawlIndex(props(),registry,url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);index.update("测试艺人",batch("OK",List.of(record("1",date,date,1))));index.tick();
            assertEquals(1,index.read(query(null),sources).drafts().size());
        }
    }
    @Test void tickHonorsSourceDisableAndRetryCooldown() {
        var registry=mock(OfficialSourceRegistryClient.class);when(registry.listEnabledSources()).thenReturn(sources);var calls=new AtomicInteger();
        var props=props();props.setDamaiEnabled(true);
        try(var index=new EventCrawlIndex(props,registry,url->{calls.incrementAndGet();throw new CrawlHttp.Failure("HTTP_429");})) {
            index.read(query(null),sources);index.tick();assertEquals(3,calls.get());index.tick();assertEquals(3,calls.get());
            assertTrue(index.read(query(null),sources).sources().stream().allMatch(s->s.get("status").equals("HTTP_429")));
        }
    }
    @Test void unsafePersistedUrlsCannotBeReturned() {
        try(var index=new EventCrawlIndex(props(),mock(OfficialSourceRegistryClient.class),url->{throw new AssertionError();})) {
            var date=EventSearchQuery.today().plusDays(4);
            var bad=new CrawlRecord("beijing","evil","测试艺人","演出","北京","场馆",date,date,"1","设立","https://evil.com/a",null,1);
            index.update("测试艺人",batch("OK",List.of(bad)));assertTrue(index.read(query(null),sources).drafts().isEmpty());
        }
    }
}
