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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded adapters. A challenge or HTTP access/rate error stops that source immediately. */
public class EventSourceCrawler {
    public record Batch(String sourceId, String status, List<CrawlRecord> records, List<String> warnings, long checkedAt) {}
    private final CrawlHttp.Fetch fetch;
    private final EventCrawlProperties props;
    private final GovernmentPageParser parser = new GovernmentPageParser();
    private long deadline;
    private int requests;
    public EventSourceCrawler(CrawlHttp.Fetch fetch, EventCrawlProperties props) { this.fetch=fetch; this.props=props; }
    private CrawlHttp.Response get(String url) throws Exception {
        CrawlHttp.validate(url);
        if (++requests > 24 || System.currentTimeMillis() >= deadline) throw new CrawlHttp.Failure("CRAWL_BUDGET_EXHAUSTED");
        var response=fetch.get(url);
        String type=response.contentType().toLowerCase(Locale.ROOT);
        if (!type.contains("pdf") && challenge(response.text())) throw new CrawlHttp.Failure("BLOCKED_CHALLENGE");
        return response;
    }
    private Document html(String url) throws Exception {
        var response=get(url);
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("text/html")) throw new CrawlHttp.Failure("UNEXPECTED_CONTENT_TYPE");
        return Jsoup.parse(new java.io.ByteArrayInputStream(response.body()), null, url);
    }
    public Batch crawl(String source, String artist) {
        deadline=System.currentTimeMillis()+90_000; requests=0;
        var records=new ArrayList<CrawlRecord>(); var warnings=new ArrayList<String>();
        try {
            switch(source) {
                case "beijing" -> beijing(artist, records, warnings);
                case "guangzhou" -> guangzhou(artist, records, warnings);
                case "damai" -> {
                    get("https://search.damai.cn/searchajax.html?"+ "keyword="+encode(artist)+"&currPage=1&pageSize=10");
                    warnings.add("DAMAI_SCHEMA_UNVALIDATED");
                }
                default -> throw new IllegalArgumentException("UNKNOWN_SOURCE");
            }
            return new Batch(source, warnings.isEmpty()?"OK":"PARTIAL",List.copyOf(records),List.copyOf(warnings),System.currentTimeMillis());
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            warnings.add(code(e));
            return new Batch(source, records.isEmpty()?code(e):"PARTIAL",List.copyOf(records),List.copyOf(warnings),System.currentTimeMillis());
        }
    }
    private void beijing(String artist,List<CrawlRecord> records,List<String> warnings) throws Exception {
        Set<String> seen=new LinkedHashSet<>(); boolean capped=false; int detailAttempts=0;
        for(int page=1;page<=Math.max(1,Math.min(3,props.getMaxListPages()));page++) {
            String url="https://banshi.whlyj.beijing.gov.cn/cms/web/whj/index.jsp?name="+encode(artist)+"&curPage="+page;
            var doc=html(url); int added=0;
            for(var link:doc.select("a[href*=content.jsp?id=]")) {
                if(!link.text().contains(artist)) continue;
                String detail=link.absUrl("href"); if(!seen.add(detail)) continue; added++;
                if(detailAttempts++>=Math.max(1,Math.min(12,props.getMaxDetails()))) { capped=true; break; }
                try {
                    var detailDoc=html(detail);
                    var pdf=detailDoc.select("a[href]").stream().filter(a -> a.attr("href").toLowerCase(Locale.ROOT).endsWith(".pdf")).findFirst();
                    if(pdf.isEmpty()) { warnings.add("PDF_LINK_MISSING"); continue; }
                    String attachment=pdf.get().absUrl("href"); byte[] body=get(attachment).body();
                    records.add(parser.beijing(artist, URI.create(detail).getRawQuery(), detail, attachment, pdfText(body), System.currentTimeMillis()));
                } catch(Exception e) { if(stop(e)) throw e; warnings.add(code(e)); }
            }
            if(added==0 || capped) break;
            int next=page+1;
            boolean hasNext=doc.select("a[href]").stream().anyMatch(a -> a.attr("href").matches(".*[?&]curPage="+next+"(?:&.*)?"));
            if(!hasNext) break;
            if(page==Math.max(1,Math.min(3,props.getMaxListPages()))) capped=true;
        }
        if(capped) warnings.add("PAGE_OR_DETAIL_LIMIT");
    }
    private void guangzhou(String artist,List<CrawlRecord> records,List<String> warnings) throws Exception {
        Set<String> notices=new LinkedHashSet<>();
        String list="https://wglj.gz.gov.cn/xxgk/bmwj/ywxx/wgl/";
        Set<String> visited=new HashSet<>();
        for(int page=1;page<=Math.max(1,Math.min(3,props.getMaxListPages()));page++) {
            if(!visited.add(list)) break;
            var doc=html(list); int added=0;
            for(var link:doc.select("a[href]")) {
                String title=link.text();
                if(!title.contains("行政许可事项") || !title.contains("营业性演出")) continue;
                String href=link.absUrl("href").replaceFirst("^http://wglj\\.gz\\.gov\\.cn/","https://wglj.gz.gov.cn/");
                if(!notices.add(href)) continue; added++;
                if(notices.size()>Math.max(1,Math.min(12,props.getMaxNotices()))) { warnings.add("NOTICE_LIMIT"); return; }
                try { records.addAll(parser.guangzhou(artist,href,html(href).outerHtml(),System.currentTimeMillis())); }
                catch(Exception e) { if(stop(e)) throw e; warnings.add(code(e)); }
            }
            if(added==0) break;
            var next=doc.select("a[href]").stream().filter(a -> a.text().contains("下一页") || a.text().equals("下页")).findFirst();
            if(next.isEmpty()) { warnings.add("LIST_PAGINATION_UNAVAILABLE"); break; }
            list=next.get().absUrl("href").replaceFirst("^http://wglj\\.gz\\.gov\\.cn/","https://wglj.gz.gov.cn/");
            if(page==Math.max(1,Math.min(3,props.getMaxListPages()))) warnings.add("PAGE_LIMIT");
        }
    }
    public static String pdfText(byte[] bytes) throws Exception {
        if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw new CrawlHttp.Failure("INVALID_PDF");
        try(var document=Loader.loadPDF(bytes)) {
            if(document.getNumberOfPages()>50) throw new CrawlHttp.Failure("PDF_PAGE_LIMIT");
            var stripper=new PDFTextStripper(); stripper.setEndPage(Math.min(3,document.getNumberOfPages()));
            String text=stripper.getText(document);
            if(text.isBlank()) throw new CrawlHttp.Failure("OCR_REQUIRED");
            return text;
        }
    }
    public static boolean challenge(String text) {
        String s=text.toLowerCase(Locale.ROOT);
        return List.of("x5sec", "_____tmd_____", "rgv587", "验证码", "captcha").stream().anyMatch(s::contains);
    }
    private static boolean stop(Exception e) {
        if(e instanceof InterruptedException) return true;
        return e instanceof CrawlHttp.Failure f && (f.code.startsWith("HTTP_") || List.of("BLOCKED_CHALLENGE","CRAWL_BUDGET_EXHAUSTED","URL_REJECTED","PRIVATE_ADDRESS").contains(f.code));
    }
    private static String code(Exception e) {
        if(e instanceof CrawlHttp.Failure f) return f.code;
        if(e instanceof java.net.SocketTimeoutException) return "FETCH_TIMEOUT";
        if(e instanceof javax.net.ssl.SSLException) return "TLS_FAILED";
        if(e instanceof IllegalArgumentException) return "PARSER_REJECTED";
        if(e instanceof java.io.IOException) return "FETCH_OR_PDF_FAILED";
        return "CRAWL_FAILED";
    }
    private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
}
