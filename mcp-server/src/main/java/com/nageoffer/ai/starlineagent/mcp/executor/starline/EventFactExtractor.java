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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

/** 仅提取官方摘录中带明确标签的事实；标题里的城市、开票时刻不能填作场馆和开场。 */
public final class EventFactExtractor {
    private EventFactExtractor() {}
    public record Facts(String venue, String address, LocalDateTime start, LocalDateTime end, PriceRange price) {}
    private static final Pattern VENUE = Pattern.compile("(?:演出场馆|演出地点|演出场地|场馆)\\s*[:：]\\s*([^，,。；;\\n|]{2,80})");
    private static final Pattern ADDRESS = Pattern.compile("(?:场馆地址|演出地址)\\s*[:：]\\s*([^，,。；;\\n|]{3,120})");
    private static final Pattern PRICE = Pattern.compile("(?:门票价格|票价|票档)\\s*[:：]\\s*([\\d¥￥元起至到、/,，.\\-~—–\\s]{1,120})");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(\\d{1,5}(?:\\.\\d{1,2})?)(?![\\d.])");

    public static Facts extract(String text, String tier, String source) {
        String venue = match(VENUE, text), address = match(ADDRESS, text);
        if (venue != null && (venue.contains("待定") || venue.contains("未公布") || venue.contains("以实际"))) venue = null;
        LocalDateTime start = dateTime(text, "(?:演出时间|开场时间|开演时间)"), end = dateTime(text, "(?:散场时间|演出结束时间)");
        PriceRange price = new PriceRange(null, null, "CNY", CostStatus.UNKNOWN, null);
        String priceText = "TICKETING".equals(tier) ? match(PRICE, text) : null;
        if (priceText != null) {
            List<Long> amounts = new ArrayList<>();
            var matcher = NUMBER.matcher(priceText);
            while (matcher.find()) {
                Long amount = com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.CostCalculator.minor(matcher.group(1));
                if (amount != null && amount > 0) amounts.add(amount);
            }
            if (!amounts.isEmpty()) {
                long min = amounts.stream().mapToLong(Long::longValue).min().orElseThrow();
                long max = amounts.stream().mapToLong(Long::longValue).max().orElseThrow();
                // “380 起”只证明下界，不把它伪造成单点总价。
                if (!priceText.contains("起")) price = new PriceRange(min, max, "CNY", CostStatus.REFERENCE, source);
            }
        }
        return new Facts(venue, address, start, end, price);
    }
    private static String match(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
    private static LocalDateTime dateTime(String text, String marker) {
        var pattern = Pattern.compile(marker + "\\s*[:：]?\\s*(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*(?:[（(][^）)]{1,8}[）)]\\s*)?([01]?\\d|2[0-3]):([0-5]\\d)");
        var matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        try {
            return LocalDateTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)));
        } catch (RuntimeException e) { return null; }
    }
}
