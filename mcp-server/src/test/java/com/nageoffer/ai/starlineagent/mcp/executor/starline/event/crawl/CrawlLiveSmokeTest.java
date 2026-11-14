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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in external compatibility check; CI never depends on current public-site availability. */
@EnabledIfEnvironmentVariable(named="CRAWL_LIVE_SMOKE",matches="true")
class CrawlLiveSmokeTest {
    @Test void guangzhouHistoricalTableIsStillParseable() throws Exception {
        String url="https://wglj.gz.gov.cn/xxgk/bmwj/ywxx/wgl/content/post_10729254.html";
        var response=new CrawlHttp().get(url);
        var records=new GovernmentPageParser().guangzhou("薛之谦",url,response.text(),System.currentTimeMillis());
        assertFalse(records.isEmpty());assertEquals("440100522026000273",records.get(0).approvalNo());
        System.out.println("live_guangzhou_parsed="+records.size());
    }
    @Test void beijingPublicPdfIsStillParseable() throws Exception {
        String url="https://banshi.whlyj.beijing.gov.cn/publish/7852144022835279.pdf";
        var response=new CrawlHttp().get(url);String text=EventSourceCrawler.pdfText(response.body());
        var record=new GovernmentPageParser().beijing("薛之谦","7852144022835279",url,url,text,System.currentTimeMillis());
        assertEquals("110000522026000243",record.approvalNo());assertEquals("北京",record.city());
        System.out.println("live_beijing_pdf_parsed=true");
    }
    @Test void damaiChallengeIsExplicitlyClassified() {
        var crawler=new EventSourceCrawler(new CrawlHttp()::get,new EventCrawlProperties());
        var result=crawler.crawl("damai","薛之谦");
        assertFalse(result.status().equals("OK"));assertTrue(result.records().isEmpty());
        System.out.println("live_damai_status="+result.status());
    }
}
