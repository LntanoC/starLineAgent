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

package com.nageoffer.ai.starlineagent.starline.service;

import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineEventDO;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class StarlineIcsBuilder {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private StarlineIcsBuilder() {
    }

    public static String build(List<StarlineEventDO> events) {
        StringBuilder out = new StringBuilder(2048);
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Starline//Calendar Subscription//CN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:" + escape("星序开票提醒"));
        line(out, "X-WR-TIMEZONE:Asia/Shanghai");
        appendTimeZone(out);
        for (StarlineEventDO event : events) {
            if (event.getTicketTime() != null && event.getId() != null) {
                appendEvent(out, event);
            }
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    private static void appendTimeZone(StringBuilder out) {
        line(out, "BEGIN:VTIMEZONE");
        line(out, "TZID:Asia/Shanghai");
        line(out, "X-LIC-LOCATION:Asia/Shanghai");
        line(out, "BEGIN:STANDARD");
        line(out, "DTSTART:19700101T000000");
        line(out, "TZOFFSETFROM:+0800");
        line(out, "TZOFFSETTO:+0800");
        line(out, "TZNAME:CST");
        line(out, "END:STANDARD");
        line(out, "END:VTIMEZONE");
    }

    private static void appendEvent(StringBuilder out, StarlineEventDO event) {
        LocalDateTime modified = event.getUpdateTime() == null ? event.getCreatedTime() : event.getUpdateTime();
        if (modified == null) {
            modified = event.getTicketTime();
        }
        String modifiedUtc = modified.atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).format(UTC);
        long sequence = Math.max(0, modified.atZone(ZONE).toEpochSecond());

        line(out, "BEGIN:VEVENT");
        line(out, "UID:starline-event-" + event.getId() + "@starline");
        line(out, "DTSTAMP:" + modifiedUtc);
        line(out, "LAST-MODIFIED:" + modifiedUtc);
        line(out, "SEQUENCE:" + sequence);
        line(out, "DTSTART;TZID=Asia/Shanghai:" + event.getTicketTime().format(LOCAL));
        line(out, "SUMMARY:" + escape(summary(event)));
        line(out, "DESCRIPTION:" + escape(description(event)));
        String url = safeUrl(event.getSourceUrl());
        if (url != null) {
            line(out, "URL:" + url);
        }
        boolean cancelled = "CANCELLED".equals(event.getReminderStatus());
        line(out, cancelled ? "STATUS:CANCELLED" : "STATUS:CONFIRMED");
        line(out, "TRANSP:TRANSPARENT");
        if (!cancelled) {
            line(out, "BEGIN:VALARM");
            line(out, "ACTION:DISPLAY");
            line(out, "TRIGGER:-PT10M");
            line(out, "DESCRIPTION:" + escape(summary(event) + "（10 分钟后开票）"));
            line(out, "END:VALARM");
        }
        line(out, "END:VEVENT");
    }

    private static String summary(StarlineEventDO event) {
        String artist = event.getArtistName() == null || event.getArtistName().isBlank() ? "演出" : event.getArtistName();
        String name = event.getEventName() == null || event.getEventName().isBlank() ? "" : " " + event.getEventName();
        return artist + name + " 开票";
    }

    private static String description(StarlineEventDO event) {
        StringBuilder text = new StringBuilder();
        text.append("艺人：").append(defaultValue(event.getArtistName())).append('\n');
        if (event.getEventName() != null && !event.getEventName().isBlank()) {
            text.append("活动：").append(event.getEventName()).append('\n');
        }
        text.append("开票时间：")
                .append(event.getTicketTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("（北京时间）\n");
        if (event.getSourceName() != null && !event.getSourceName().isBlank()) {
            text.append("来源：").append(event.getSourceName()).append('\n');
        }
        if (event.getSourceUrl() != null && !event.getSourceUrl().isBlank()) {
            text.append("来源链接：").append(event.getSourceUrl()).append('\n');
        }
        text.append("请以官方来源的最新信息为准。");
        return text.toString();
    }

    private static String safeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getScheme() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }

    private static void line(StringBuilder out, String value) {
        out.append(foldLine(value)).append("\r\n");
    }

    /** Fold by UTF-8 octets without splitting Unicode code points. */
    private static String foldLine(String value) {
        StringBuilder folded = new StringBuilder(value.length() + 16);
        int octets = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String part = new String(Character.toChars(codePoint));
            int length = part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (octets + length > 75) {
                folded.append("\r\n ");
                octets = 1;
            }
            folded.append(part);
            octets += length;
            offset += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    private static String defaultValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
