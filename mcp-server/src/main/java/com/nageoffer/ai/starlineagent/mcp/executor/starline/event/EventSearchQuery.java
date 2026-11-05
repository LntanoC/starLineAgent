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
import java.time.ZoneId;
import java.util.Map;

public record EventSearchQuery(String artist, String city, String window, boolean includeEnded, int pageSize, String cursor) {
    public static EventSearchQuery from(Map<String, Object> args) {
        String artist = value(args, "artist", "").trim();
        if (artist.isBlank() || artist.length() > 80) throw new IllegalArgumentException("请提供1至80字的艺人正式姓名 artist");
        String city = value(args, "city", "").trim();
        if (city.length() > 40) throw new IllegalArgumentException("city 过长");
        String legacy = value(args, "freshness", "year");
        String window = value(args, "window", "month".equals(legacy) || "week".equals(legacy) ? "recent" : "year");
        if (!java.util.List.of("recent", "year", "all").contains(window)) throw new IllegalArgumentException("window 可选 recent、year、all");
        Object ended = args.getOrDefault("includeEnded", false);
        if (!(ended instanceof Boolean)) throw new IllegalArgumentException("includeEnded 必须是布尔值");
        Object size = args.getOrDefault("pageSize", 20);
        if (!(size instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 50)
            throw new IllegalArgumentException("pageSize 必须为1至50的整数");
        String cursor = value(args, "cursor", "");
        if (cursor.length() > 300) throw new IllegalArgumentException("cursor 不合法");
        return new EventSearchQuery(artist, city.isEmpty() ? null : CityDictionary.normalize(city), window,
                (Boolean) ended, ((Number) size).intValue(), cursor.isBlank() ? null : cursor);
    }
    private static String value(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " 必须是字符串");
        return text;
    }
    public LocalDate fromDate() { return today().minusDays(includeEnded ? 30 : 0); }
    public LocalDate toDate() { return today().plusDays(switch(window) { case "recent" -> 90; case "all" -> 730; default -> 365; }); }
    public static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    public String key() { return artist + "|" + city + "|" + window + "|" + includeEnded; }
}
