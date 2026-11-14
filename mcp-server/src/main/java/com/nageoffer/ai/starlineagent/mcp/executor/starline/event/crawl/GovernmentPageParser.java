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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Source-specific field boundaries: notice/approval dates cannot become show dates. */
public final class GovernmentPageParser {
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    public record Range(LocalDate from, LocalDate to) {}
    public Range range(String text) {
        var matcher = DATE.matcher(text.replaceAll("\\s+", ""));
        List<LocalDate> dates = new ArrayList<>();
        while (matcher.find()) dates.add(LocalDate.of(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2)),Integer.parseInt(matcher.group(3))));
        if (dates.isEmpty() || dates.size() > 2) throw new IllegalArgumentException("DATE_STRUCTURE_CHANGED");
        LocalDate from = dates.get(0), to = dates.get(dates.size()-1);
        if (to.isBefore(from)) throw new IllegalArgumentException("INVALID_DATE_RANGE");
        return new Range(from,to);
    }
    public CrawlRecord beijing(String artist, String document, String url, String attachment, String text, long now) {
        String compact = text.replaceAll("\\s+", "");
        var event = Pattern.compile("拟于(.+?)在(.+?)举办的名为(.+?)(?:[（(]共|营业性演出)").matcher(compact);
        if (!event.find() || !event.group(3).contains(artist) || !compact.contains("准予行政许可")) throw new IllegalArgumentException("APPROVAL_STRUCTURE_CHANGED");
        Range dates = range(event.group(1));
        var number = Pattern.compile("许可[（(]备案[）)]号[:：]([A-Za-z0-9\\-]+)").matcher(compact);
        String change = change(event.group(3));
        String title = event.group(3).replaceAll("[（(].*?[）)]", "");
        String city = title.contains("北京站") ? "北京" : null;
        return new CrawlRecord("beijing", document, artist, title, city, event.group(2), dates.from(), dates.to(),
                number.find() ? number.group(1) : document, change, url, attachment, now);
    }
    public List<CrawlRecord> guangzhou(String artist, String url, String html, long now) {
        var doc = Jsoup.parse(html, url);
        List<CrawlRecord> output = new ArrayList<>(); boolean sawTable = false;
        for (Element table : doc.select("table")) {
            List<String> header = null;
            for (Element row : table.select("tr")) {
                var cells = row.children().stream().filter(c -> c.tagName().equals("td") || c.tagName().equals("th")).toList();
                var values = cells.stream().map(Element::text).map(s -> s.replaceAll("\\s+", "")).toList();
                if (values.contains("许可编号") && values.contains("演出时间")) { header = values; sawTable = true; continue; }
                if (header == null || !row.text().contains(artist)) continue;
                if (values.size() != header.size() || cells.stream().anyMatch(c -> (!c.attr("colspan").isEmpty() && !c.attr("colspan").equals("1"))
                        || (!c.attr("rowspan").isEmpty() && !c.attr("rowspan").equals("1")))) throw new IllegalArgumentException("TABLE_STRUCTURE_CHANGED");
                Map<String,String> fields = new HashMap<>(); for(int i=0;i<header.size();i++) fields.put(header.get(i),values.get(i));
                String title = fields.getOrDefault("许可事项", "");
                if (!title.contains(artist)) continue;
                if (!fields.keySet().containsAll(List.of("演出地址","许可编号","业务类型"))) throw new IllegalArgumentException("TABLE_STRUCTURE_CHANGED");
                Range dates = range(fields.get("演出时间"));
                String venue = fields.get("演出地址");
                var eventTitle = Pattern.compile("[（(]([^（）()]*" + Pattern.quote(artist) + "[^（）()]*)[）)]").matcher(title);
                if (eventTitle.find()) title = eventTitle.group(1);
                String number = fields.get("许可编号");
                output.add(new CrawlRecord("guangzhou", number, artist, title,
                        venue.contains("广州") || title.contains("广州") ? "广州" : null, venue, dates.from(), dates.to(),
                        number, fields.get("业务类型"), url, null, now));
            }
        }
        if (!sawTable) throw new IllegalArgumentException("TABLE_HEADER_MISSING");
        return output;
    }
    private static String change(String text) {
        return List.of("取消", "撤销", "增加场次", "增加演员", "变更").stream().filter(text::contains).findFirst().orElse("设立");
    }
}
