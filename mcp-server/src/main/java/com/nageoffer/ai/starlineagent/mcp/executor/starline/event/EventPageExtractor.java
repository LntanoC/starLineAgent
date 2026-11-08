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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event;

import com.nageoffer.ai.starlineagent.mcp.executor.starline.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class EventPageExtractor {
    public record Draft(EventItem item, boolean dateUnknown, boolean cityUnknown, boolean noise,
                        List<LocalDate> dateCandidates, String dateExpression, List<String> queries) {}
    private final CityDictionary cities;
    private final ArtistEventSearchProperties properties;
    private final DateExpressionParser dates = new DateExpressionParser();
    public EventPageExtractor(CityDictionary cities, ArtistEventSearchProperties properties) { this.cities = cities; this.properties = properties; }
    public List<Draft> extract(WebSearchClient.RawResult raw, OfficialSource source, EventSearchQuery query, List<String> queries) {
        String excerpt = Objects.toString(raw.excerpt(), "");
        String text = Objects.toString(raw.title(), "") + "。" + excerpt;
        // A summary title must not lend Shanghai's venue to Hangzhou's local block.
        if (cities.find(excerpt).stream().map(CityDictionary.Hit::city).distinct().count() > 1) text = excerpt;
        List<CityDictionary.Hit> hits = cities.find(text);
        String content = text;
        List<CityDictionary.Hit> stations = hits.stream().filter(hit -> content.substring(hit.end()).startsWith("站")).toList();
        if (stations.stream().map(CityDictionary.Hit::city).distinct().count() > 1) hits = stations;
        List<CityDictionary.Hit> boundaries = new ArrayList<>();
        for (var hit : hits) if (boundaries.isEmpty() || !boundaries.get(boundaries.size() - 1).city().equals(hit.city())) boundaries.add(hit);
        List<Draft> result = new ArrayList<>();
        if (boundaries.isEmpty()) return block(text, null, true, raw, source, query, queries);
        for (int i = 0; i < boundaries.size(); i++) {
            var hit = boundaries.get(i);
            String block = text.substring(i == 0 ? 0 : hit.start(), i + 1 == boundaries.size() ? text.length() : boundaries.get(i + 1).start());
            result.addAll(block(block, hit.city(), hit.unknown(), raw, source, query, queries));
        }
        return result;
    }
    private List<Draft> block(String text, String city, boolean cityUnknown, WebSearchClient.RawResult raw,
                              OfficialSource source, EventSearchQuery query, List<String> queries) {
        EventFactExtractor.Facts facts = EventFactExtractor.extract(text, source.tier(), raw.url());
        String venue = facts.venue();
        if (venue != null && cities.find(venue).stream().anyMatch(hit -> !Objects.equals(city, hit.city()))) venue = null;
        boolean stale = raw.publishTime() == null || raw.publishTime().isBefore(Instant.now().minus(Duration.ofHours(properties.getStaleHours())));
        boolean noise = List.of("周边", "壁纸", "翻唱", "头像", "同款").stream().anyMatch(text::contains);
        LocalDateTime sale = saleTime(text);
        if (!"TICKETING".equals(source.tier())) sale = null;
        List<Draft> drafts = new ArrayList<>();
        for (var date : dates.parse(text, query)) {
            LocalDateTime start = facts.start() != null && Objects.equals(facts.start().toLocalDate(), date.date()) ? facts.start() : null;
            if (start == null && date.date() != null) {
                var time = Pattern.compile("(?:开场时间|开演时间|演出时间)\\s*[:：]\\s*([01]?\\d|2[0-3]):([0-5]\\d)").matcher(text);
                if (time.find()) start = date.date().atTime(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)));
            }
            LocalDateTime end = start == null ? null : facts.end();
            String stage = date.date() != null && date.date().isBefore(EventSearchQuery.today()) ? "ENDED"
                    : !"TICKETING".equals(source.tier()) ? "APPROVED"
                    : has(text, "售罄", "已售完", "缺货登记") ? "SOLD_OUT"
                    : has(text, "售票中", "正在售票", "立即购买", "立即预订", "选座购买") ? "ON_SALE" : "ON_SHELF";
            EventItem item = new EventItem(query.artist(), Objects.toString(raw.title(), query.artist() + "演出"), stage, source.tier(), city,
                    date.date(), sale, sale == null ? null : ChronoUnit.DAYS.between(EventSearchQuery.today(), sale.toLocalDate()),
                    "ON_SALE".equals(stage) && !stale && date.date() != null && !cityUnknown, source.platformName(), raw.url(),
                    "GOV".equals(source.tier()) ? source.platformName() + " · 文旅许可公示" : null,
                    "GOV".equals(source.tier()) ? raw.url() : null, stale, false,
                    List.of(new SourceEvidence(source.platformName(), source.tier(), raw.url(), stage)), venue,
                    venue == null ? null : facts.address(), start, end, facts.price(), venue == null ? null : raw.url(), start == null ? null : raw.url());
            drafts.add(new Draft(item, date.unknown(), cityUnknown, noise, date.candidates(), date.expression(), queries));
        }
        return drafts;
    }
    private static boolean has(String text, String... terms) { return Arrays.stream(terms).anyMatch(text::contains); }
    private static LocalDateTime saleTime(String text) {
        var matcher = Pattern.compile("(?:开票|开售|预售|抢票|售票)(?:时间|日期)?\\s*[:：]?\\s*(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})").matcher(text);
        Set<LocalDateTime> times=new LinkedHashSet<>();
        while (matcher.find()) try { times.add(LocalDateTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)))); } catch (DateTimeException ignored) { }
        return times.size()==1?times.iterator().next():null;
    }
}
