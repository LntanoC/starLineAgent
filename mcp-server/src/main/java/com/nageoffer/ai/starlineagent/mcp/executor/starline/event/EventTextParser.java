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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative event-date parsing: omitted years need explicit, unambiguous context. */
public final class EventTextParser {
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(?:(20\\d{2})[年./-])?(\\d{1,2})[月./-](\\d{1,2})日?");
    private static final Pattern DAY = Pattern.compile("^\\s*(?:[（(][^）)\\n]{1,12}[）)]\\s*)?([、,，及和或至到~～—–-])\\s*(?:(20\\d{2})[年./-])?(?:(\\d{1,2})[月./-])?(\\d{1,2})日?");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?:年|[./-]\\d)");
    private static final Pattern COUNT = Pattern.compile("(?:连开|连演|共|连续)\\s*([一二三四五六七八九十\\d]+)\\s*场");
    private static final Pattern SALE = Pattern.compile("开票|预售|抢票|售票|开售|销售|发布|公示|有效期");
    private static final Pattern EVENT = Pattern.compile("演出时间|演出日期|活动时间|开唱|举办时间|场次|演出安排");
    private EventTextParser() { }

    public static Dates dates(String text) {
        Set<Integer> years = new LinkedHashSet<>();
        Matcher yearMatcher = YEAR.matcher(text);
        while (yearMatcher.find()) years.add(Integer.parseInt(yearMatcher.group(1)));
        Integer contextualYear = years.size() == 1 ? years.iterator().next() : null;
        Set<LocalDate> dates = new LinkedHashSet<>();
        boolean unresolved = false;
        for (String line : text.split("[\\n；;。]")) {
            Matcher matcher = DATE.matcher(line);
            int consumed = 0;
            Integer year = contextualYear;
            while (matcher.find()) {
                if (matcher.start() < consumed) continue;
                String prefix = line.substring(Math.max(0, matcher.start() - 32), matcher.start());
                if (isOtherDate(prefix)) continue;
                if (line.substring(matcher.end()).matches("^\\s*(?:\\d{1,2}:\\d{2})?\\s*(?:开启|开始)?(?:开票|预售|开售|发布|公示).*")) continue;
                if (matcher.group(1) != null) year = Integer.valueOf(matcher.group(1));
                if (year == null) { unresolved = true; continue; }
                LocalDate first = date(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
                if (first == null) { unresolved = true; continue; }
                List<LocalDate> group = new ArrayList<>();
                group.add(first);
                int end = matcher.end();
                LocalDate previous = first;
                boolean needsCount = false;
                boolean invalidRange = false;
                while (end < line.length()) {
                    Matcher tail = DAY.matcher(line.substring(end));
                    if (!tail.find()) break;
                    int nextYear = tail.group(2) == null ? previous.getYear() : Integer.parseInt(tail.group(2));
                    int month = tail.group(3) == null ? previous.getMonthValue() : Integer.parseInt(tail.group(3));
                    // Explicit December-to-January range/list establishes the year rollover.
                    if (tail.group(2) == null && previous.getMonthValue() == 12 && month == 1) nextYear++;
                    LocalDate next = date(nextYear, month, Integer.parseInt(tail.group(4)));
                    end += tail.end();
                    if (next == null) { unresolved = true; break; }
                    if ("至到~～—–-".contains(tail.group(1))) {
                        long span = ChronoUnit.DAYS.between(previous, next);
                        boolean continuous = line.contains("每天") || line.contains("每日");
                        if (span < 0 || span > 31) {
                            invalidRange = true;
                        } else {
                            for (int i = 1; i <= span; i++) group.add(previous.plusDays(i));
                            needsCount |= !continuous;
                        }
                    } else group.add(next);
                    previous = next;
                    year = next.getYear();
                }
                consumed = end;
                boolean countMatches = false;
                Matcher count = COUNT.matcher(line);
                while (count.find()) countMatches |= number(count.group(1)) == new LinkedHashSet<>(group).size();
                if (invalidRange || (needsCount && !countMatches)) unresolved = true;
                else dates.addAll(group);
            }
        }
        return new Dates(List.copyOf(dates), unresolved);
    }

    private static boolean isOtherDate(String prefix) {
        Matcher sale = SALE.matcher(prefix);
        Matcher event = EVENT.matcher(prefix);
        int saleEnd = -1, eventEnd = -1;
        while (sale.find()) saleEnd = sale.end();
        while (event.find()) eventEnd = event.end();
        return saleEnd > eventEnd;
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return "一二三四五六七八九十".indexOf(value) + 1; }
    }

    private static LocalDate date(int year, int month, int day) {
        try { return LocalDate.of(year, month, day); }
        catch (RuntimeException ignored) { return null; }
    }

    public record Dates(List<LocalDate> values, boolean unresolved) { }
}
