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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GovernmentCrawlerTest {
    final GovernmentPageParser parser=new GovernmentPageParser();
    static final String BJ="https://banshi.whlyj.beijing.gov.cn/cms/web/whj/content.jsp?id=1";
    static final String GZ="https://wglj.gz.gov.cn/xxgk/bmwj/ywxx/wgl/content/post_1.html";
    static String notice(String dates) {
        return "北京市文化和旅游局 准予行政许可（备案）决定 许可（备案）号：110000522026000243 本行政机关于2026年07月02日受理你单位提出的拟于"
                +dates+"在国家体育场（鸟巢）举办的名为测试艺人巡回演唱会-北京站（增加演员）（共12场）营业性演出申请。";
    }
    static String table(String dates) {
        return "<table><tr><th>许可日期</th><th>许可编号</th><th>业务类型</th><th>许可事项</th><th>演出地址</th><th>演出时间</th></tr>"
                +"<tr><td>2026/3/10</td><td>4401</td><td>非内地变更</td><td>中国艺人测试艺人等一行37人（测试艺人巡回演唱会-广州站）</td><td>广州天河体育中心体育场</td><td>"+dates+"</td></tr></table>";
    }
    static CrawlHttp.Response html(String text) { return new CrawlHttp.Response(text.getBytes(StandardCharsets.UTF_8),"text/html;charset=utf-8"); }
    @Test void pdfDateBoundaryExcludesAcceptanceDateAndPreservesRange() {
        var r=parser.beijing("测试艺人","1",BJ,BJ+".pdf",notice("2026年07月10日至2026年08月02日"),10);
        assertEquals(LocalDate.of(2026,7,10),r.approvedFrom()); assertEquals(LocalDate.of(2026,8,2),r.approvedTo());
        assertEquals("国家体育场（鸟巢）",r.venue()); assertEquals("110000522026000243",r.approvalNo());
        assertEquals("增加演员",r.changeType()); assertEquals("测试艺人巡回演唱会-北京站",r.title());
    }
    @Test void pdfWhitespaceAndLineBreaksAreAccepted() {
        var r=parser.beijing("测试艺人","1",BJ,null,notice("2026 年 07 月 10 日").replace("测试艺人","测试艺\n人"),10);
        assertEquals(r.approvedFrom(),r.approvedTo()); assertEquals("北京",r.city());
    }
    @Test void pdfUnrelatedArtistAndMissingApprovalAreRejected() {
        assertThrows(IllegalArgumentException.class,()->parser.beijing("别的艺人","1",BJ,null,notice("2026年07月10日"),10));
        assertThrows(IllegalArgumentException.class,()->parser.beijing("测试艺人","1",BJ,null,notice("2026年07月10日").replace("准予行政许可","申请材料"),10));
    }
    @Test void tableExtractsColumnsAndDoesNotUseApprovalDate() {
        var rows=parser.guangzhou("测试艺人",GZ,table("2026年03月20日至2026年04月05日"),20);
        assertEquals(1,rows.size()); var r=rows.get(0);
        assertEquals("4401",r.approvalNo()); assertEquals("广州天河体育中心体育场",r.venue());
        assertEquals(LocalDate.of(2026,3,20),r.approvedFrom()); assertEquals(LocalDate.of(2026,4,5),r.approvedTo());
        assertEquals("测试艺人巡回演唱会-广州站",r.title());
    }
    @Test void tableSupportsReorderedColumns() {
        String text="<table><tr><td>演出时间</td><td>许可事项</td><td>业务类型</td><td>许可编号</td><td>演出地址</td></tr>"
                +"<tr><td>2026-12-20</td><td>测试艺人演出</td><td>设立</td><td>42</td><td>广州音乐厅</td></tr></table>";
        assertEquals("42",parser.guangzhou("测试艺人",GZ,text,20).get(0).approvalNo());
    }
    @Test void tableDoesNotReturnOtherArtists() { assertTrue(parser.guangzhou("其他艺人",GZ,table("2026-12-20"),20).isEmpty()); }
    @ParameterizedTest @ValueSource(strings={"2026-02-30","2026-12-20至2026-12-10","无日期","2026-12-20、2026-12-21、2026-12-22"})
    void invalidOrAmbiguousRangesAreRejected(String dates) { assertThrows(RuntimeException.class,()->parser.range(dates)); }
    @ParameterizedTest @ValueSource(strings={"2026-12-20","2026年12月20日","2026/12/20","2026.12.20"})
    void explicitSingleDatesAreAccepted(String dates) { assertEquals(LocalDate.of(2026,12,20),parser.range(dates).from()); }
    @Test void tableShapeChangesAndMergedCellsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->parser.guangzhou("测试艺人",GZ,"<p>测试艺人</p>",20));
        assertThrows(IllegalArgumentException.class,()->parser.guangzhou("测试艺人",GZ,table("2026-12-20").replace("<td>4401","<td colspan='2'>4401"),20));
    }
    @ParameterizedTest @ValueSource(strings={"http://wglj.gz.gov.cn/a","https://wglj.gz.gov.cn.evil.com/a","https://127.0.0.1/a","file:///etc/passwd","https://user@wglj.gz.gov.cn/a","https://wglj.gz.gov.cn:444/a","https://wglj.gz.gov.cn/a#x"})
    void unsafeUrlsRejected(String url) { assertThrows(Exception.class,()->CrawlHttp.validate(url)); }
    @Test void chunkedBodyAndMalformedChunks() throws Exception {
        assertEquals("hello",new String(CrawlHttp.chunks("5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)),StandardCharsets.US_ASCII));
        assertThrows(Exception.class,()->CrawlHttp.chunks("5\r\nhe".getBytes()));
        assertThrows(Exception.class,()->CrawlHttp.chunks("-1\r\n".getBytes()));
    }
    @ParameterizedTest @ValueSource(strings={"x5sec=token","_____tmd_____/punish","RGV587","请完成验证码","<script>captcha()</script>"})
    void challengesAreFailuresRatherThanEmptyEvents(String text) {
        var crawler=new EventSourceCrawler(url->html(text),new EventCrawlProperties());
        var b=crawler.crawl("damai","测试艺人"); assertEquals("BLOCKED_CHALLENGE",b.status()); assertTrue(b.records().isEmpty());
    }
    @Test void http403StopsSourceWithoutRetryingOrFollowingOtherLinks() {
        var count=new AtomicInteger(); var crawler=new EventSourceCrawler(url->{count.incrementAndGet();throw new CrawlHttp.Failure("HTTP_403");},new EventCrawlProperties());
        assertEquals("HTTP_403",crawler.crawl("beijing","测试艺人").status()); assertEquals(1,count.get());
    }
    @Test void repeatingPaginationDoesNotLoop() {
        var count=new AtomicInteger();
        var crawler=new EventSourceCrawler(url->{count.incrementAndGet();return html("<a href='index.jsp?curPage=1'>下一页</a>");},new EventCrawlProperties());
        assertEquals("OK",crawler.crawl("beijing","测试艺人").status()); assertEquals(1,count.get());
    }
    @Test void malformedPdfIsReportedAndCannotBecomeAnEvent() {
        var crawler=new EventSourceCrawler(url->{
            if(url.contains("index.jsp")) return html("<a href='content.jsp?id=1'>测试艺人演出</a>");
            if(url.contains("content.jsp")) return html("<a href='/publish/1.pdf'>批文</a>");
            return new CrawlHttp.Response("not pdf".getBytes(),"application/pdf");
        },new EventCrawlProperties());
        var result=crawler.crawl("beijing","测试艺人"); assertTrue(result.records().isEmpty()); assertEquals("PARTIAL",result.status()); assertTrue(result.warnings().contains("INVALID_PDF"));
    }
    @Test void failedDetailsAlsoConsumeConfiguredDetailBudget() {
        var props=new EventCrawlProperties();props.setMaxDetails(2);var details=new AtomicInteger();
        var crawler=new EventSourceCrawler(url->{
            if(url.contains("index.jsp")) return html("<a href='content.jsp?id=1'>测试艺人</a><a href='content.jsp?id=2'>测试艺人</a><a href='content.jsp?id=3'>测试艺人</a>");
            details.incrementAndGet();return html("<p>附件缺失</p>");
        },props);
        var result=crawler.crawl("beijing","测试艺人");assertEquals(2,details.get());assertTrue(result.warnings().contains("PAGE_OR_DETAIL_LIMIT"));
    }
    @Test void offHostAttachmentIsBlockedBeforeTransport() {
        var calls=new AtomicInteger();
        var crawler=new EventSourceCrawler(url->{calls.incrementAndGet();return url.contains("index.jsp")?html("<a href='content.jsp?id=1'>测试艺人</a>"):
                html("<a href='https://evil.com/private.pdf'>附件</a>");},new EventCrawlProperties());
        assertEquals("URL_REJECTED",crawler.crawl("beijing","测试艺人").status()); assertEquals(2,calls.get());
    }
    @Test void pdfWithNoTextLayerRequiresOcr() throws Exception {
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {pdf.addPage(new PDPage());pdf.save(out);bytes=out.toByteArray();}
        var error=assertThrows(CrawlHttp.Failure.class,()->EventSourceCrawler.pdfText(bytes)); assertEquals("OCR_REQUIRED",error.code);
    }
    @Test void validPdfTextLayerIsRead() throws Exception {
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var content=new PDPageContentStream(pdf,page)) {content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,700);content.showText("Event approval sample");content.endText();}
            pdf.save(out);bytes=out.toByteArray();
        }
        assertTrue(EventSourceCrawler.pdfText(bytes).contains("Event approval sample"));
    }
}
