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

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Missing years stay uncertain; candidate years are evidence, never a bookable date. */
public class DateExpressionParser {
    public record ParsedDate(LocalDate date, boolean unknown, List<LocalDate> candidates, String expression) {}
    private static final Pattern DATE = Pattern.compile("(?<![\\d.])(20\\d{2}[-/.年])?(\\d{1,2})[-/.月](\\d{1,2})日?(?!\\d)");
    private static final Pattern SHORT_END = Pattern.compile("^\\s*(?:至|到|[-~—–])\\s*(\\d{1,2})日");
    public List<ParsedDate> parse(String text, EventSearchQuery query) {
        // Remove only explicit sale clauses, so a later show clause survives on the same line.
        text = text.replaceAll("(?:开票|预售|抢票|售票)(?:时间|日期)?\\s*[:：]?\\s*(?:20\\d{2}[-/.年])?\\d{1,2}[-/.月]\\d{1,2}日?(?:\\s*\\d{1,2}:\\d{2})?", "");
        text = text.replaceAll("(?:发布|公示|批准|批复|许可|备案)(?:时间|日期)\\s*[:：]?\\s*(?:20\\d{2}[-/.年])?\\d{1,2}[-/.月]\\d{1,2}日?(?:\\s*\\d{1,2}:\\d{2})?", "");
        List<ParsedDate> dates = new ArrayList<>();
        var matcher = DATE.matcher(text);
        Integer explicitYear = null;
        while (matcher.find()) {
            String rawYear = matcher.group(1);
            Integer year = rawYear == null ? null : Integer.parseInt(rawYear.substring(0, 4));
            // A full date may lend its year only to an immediately connected range endpoint.
            boolean rangeEndpoint = false;
            if (year == null && explicitYear != null && !dates.isEmpty()) {
                int previous = text.lastIndexOf(dates.get(dates.size() - 1).expression(), matcher.start());
                String between = previous < 0 ? "" : text.substring(previous + dates.get(dates.size() - 1).expression().length(), matcher.start());
                if (between.matches("\\s*(?:至|到|[-~—–])\\s*")) { year = explicitYear; rangeEndpoint = true; }
            }
            explicitYear = year;
            ParsedDate parsed = create(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), matcher.group(), query);
            if (rangeEndpoint && parsed.date() != null && !dates.isEmpty() && dates.get(dates.size() - 1).date() != null
                    && parsed.date().isBefore(dates.get(dates.size() - 1).date()))
                parsed = create(year + 1, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), matcher.group(), query);
            dates.add(parsed);
            var end = SHORT_END.matcher(text.substring(matcher.end()));
            if (end.find()) dates.add(create(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(end.group(1)), end.group(), query));
        }
        if (dates.isEmpty()) dates.add(new ParsedDate(null, true, List.of(), "未找到演出日期"));
        return dates.stream().distinct().toList();
    }
    private ParsedDate create(Integer year, int month, int day, String expression, EventSearchQuery query) {
        if (year != null) {
            try { return new ParsedDate(LocalDate.of(year, month, day), false, List.of(), expression); }
            catch (DateTimeException ignored) { return new ParsedDate(null, true, List.of(), expression); }
        }
        List<LocalDate> candidates = new ArrayList<>();
        for (int y = query.fromDate().getYear(); y <= query.toDate().getYear(); y++) {
            try { LocalDate date = LocalDate.of(y, month, day); if (!date.isBefore(query.fromDate()) && !date.isAfter(query.toDate())) candidates.add(date); }
            catch (DateTimeException ignored) { /* Invalid calendar dates stay unknown. */ }
        }
        return new ParsedDate(null, true, candidates, expression);
    }
}
