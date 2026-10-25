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

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.starlineagent.mcp.config.YuanbaoApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolResults;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.string;

/** Search artist events and move source, freshness, relevance and sale-state labels into code. */
@Slf4j
@Component
@Conditional(YuanbaoApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class ArtistEventSearchMcpExecutor {

    private static final String TOOL_ID = "search_artist_events";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_FETCH_COUNT = 20;
    private static final List<String> FRESHNESS = List.of("week", "month", "year");
    private static final List<String> NOISE_MARKERS = List.of(
            "同款", "翻唱", "模仿秀", "应援", "周边", "壁纸", "头像", "歌词", "表情包");
    private static final List<String> EVENT_MARKERS = List.of(
            "演唱会", "巡演", "音乐会", "见面会", "开票", "门票", "场馆", "演出");
    private static final List<String> SALE_MARKERS = List.of("开票", "预售", "抢票", "售票");
    private static final List<String> EVENT_DATE_MARKERS = List.of("演出时间", "演出日期", "举办", "开唱", "演出将", "活动时间");
    private static final List<String> CITY_NAMES = List.of(
            "乌鲁木齐", "呼和浩特", "哈尔滨", "石家庄", "海口", "三亚", "北京", "上海", "广州", "深圳",
            "杭州", "成都", "武汉", "南京", "西安", "重庆", "长沙", "天津", "苏州", "郑州", "青岛",
            "大连", "厦门", "昆明", "合肥", "福州", "济南", "沈阳", "长春", "太原", "南昌", "贵阳", "南宁", "兰州");
    private static final Pattern EVENT_DATE = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private static final Pattern SALE_TIME = Pattern.compile(
            "(?:(?:20\\d{2}[年/-])?\\d{1,2}[月/-]\\d{1,2}日?\\s*)?[0-2]?\\d:[0-5]\\d");

    @Qualifier("yuanbaoWebSearchClient")
    private final WebSearchClient searchClient;
    private final ArtistEventSearchProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static final String DESCRIPTION = "查询某艺人近期的演出安排，包括演唱会、巡演、场次、城市、场馆、演出日期、开票时间与售票状态。"
            + "内部联网检索并返回结构化结果，标注 sourceType（OFFICIAL 官方票务平台 / UNVERIFIED 未核实）、"
            + "isStale（发布信息是否过期）和 saleStatus（售票状态）。"
            + "\n\n适用：询问具体艺人的近期演出、巡演、城市场次、开票时间或售票状态。"
            + "不适用：艺人生日、作品、八卦或其他与演出安排无关的问题。"
            + "\n\ntotal=0 只表示本次没有检索到可信结果，不代表该艺人没有演出；不得编造场次、日期或城市。"
            + "UNVERIFIED 只能作为未核实线索转述，不能作为确定结论或提醒依据。isStale=true 时必须提示信息可能已变化。"
            + "saleStatus=UNKNOWN 时不得断言还能买或已经售罄。涉及最近、今年、下个月等相对时间时，先调用 current_date。"
            + "本工具只查询，不创建提醒。";

    @Bean
    public McpServerFeatures.SyncToolSpecification artistEventSearchToolSpecification() {
        JsonSchema schema = McpToolSchema.object()
                .required(string("artist", "艺人正式姓名，例如薛之谦；昵称或别名请转换为正式姓名后传入"))
                .optional(string("city", "限定城市，例如北京、上海；用户未指定时不要补填"))
                .optional(string("freshness", "检索跨度：week（过去 7 天至未来 30 天）、month（过去 30 天至未来 90 天，默认）、year（过去 90 天至未来 365 天）")
                        .options(FRESHNESS)
                        .defaultTo("month"))
                .build();
        Tool tool = Tool.builder()
                .name(TOOL_ID)
                .description(DESCRIPTION)
                .inputSchema(schema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String artist = StrUtil.trimToNull(MapUtil.getStr(args, "artist"));
            if (artist == null) {
                return McpToolResults.error("请提供艺人姓名 artist");
            }
            String city = StrUtil.trimToNull(MapUtil.getStr(args, "city"));
            String freshness = StrUtil.blankToDefault(MapUtil.getStr(args, "freshness"), "month");
            if (!FRESHNESS.contains(freshness)) {
                return McpToolResults.error("freshness 参数不合法，可选值：week、month、year");
            }

            SearchWindow window = window(freshness);
            LocalDate today = LocalDate.now(ZONE);
            LocalDate from = today.minusDays(window.pastDays());
            LocalDate to = today.plusDays(window.futureDays());
            String query = artist + (city == null ? "" : " " + city) + " 演唱会 巡演 开票 官方";
            int fetchCount = Math.max(1, Math.min(MAX_FETCH_COUNT, properties.getMaxItems() * 3));
            // This window filters event dates below. Tencent Freshness filters page publish dates and cannot include future dates.
            List<WebSearchClient.RawResult> raw = searchClient.search(query, null, fetchCount);

            Instant now = Instant.now();
            List<EventItem> items = new ArrayList<>();
            int filtered = 0;
            for (WebSearchClient.RawResult result : raw) {
                if (!isAboutArtist(result, artist)) {
                    filtered++;
                    continue;
                }
                EventItem item = toEventItem(result, today, now);
                if (item.eventDate() != null && (item.eventDate().isBefore(from) || item.eventDate().isAfter(to))) {
                    filtered++;
                    continue;
                }
                items.add(item);
            }
            int maxItems = Math.max(1, properties.getMaxItems());
            items.sort(Comparator.comparingInt((EventItem item) -> phaseRank(item.relativePhase()))
                    .thenComparingInt(item -> "OFFICIAL".equals(item.sourceType()) ? 0 : 1)
                    .thenComparing(EventItem::eventDate, Comparator.nullsLast(Comparator.naturalOrder())));
            if (items.size() > maxItems) {
                items = new ArrayList<>(items.subList(0, maxItems));
            }

            Map<String, Object> result = buildResponse(artist, query, now, from, to, items, raw.size(), filtered);
            log.info("MCP 工具调用完成, toolId={}, resultCount={}, elapsed={}ms",
                    TOOL_ID, items.size(), System.currentTimeMillis() - startedAt);
            return McpToolResults.success(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms", TOOL_ID,
                    System.currentTimeMillis() - startedAt, e);
            return McpToolResults.failure("演出查询", e);
        }
    }

    private boolean isAboutArtist(WebSearchClient.RawResult result, String artist) {
        String text = text(result.title()) + " " + text(result.excerpt());
        if (!text.contains(artist)) {
            return false;
        }
        boolean noise = NOISE_MARKERS.stream().anyMatch(text::contains);
        boolean eventContext = EVENT_MARKERS.stream().anyMatch(text::contains);
        return !noise || eventContext;
    }

    private EventItem toEventItem(WebSearchClient.RawResult result, LocalDate today, Instant now) {
        String text = text(result.title()) + " " + text(result.excerpt());
        String domain = domain(result.url());
        LocalDate eventDate = extractEventDate(text);
        StaleMark stale = staleMark(result.publishTime(), now);
        String sourceType = isOfficial(domain) ? "OFFICIAL" : "UNVERIFIED";
        return new EventItem(
                clip(result.title(), 240),
                phase(eventDate, today),
                extractCity(text),
                null,
                eventDate,
                extractSaleStartTime(text),
                extractSaleStatus(text),
                sourceType,
                domain,
                result.url(),
                result.publishTime() == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(result.publishTime().atZone(ZONE)),
                stale.stale(),
                stale.reason(),
                clip(result.excerpt(), 300));
    }

    private Map<String, Object> buildResponse(String artist, String query, Instant now,
                                              LocalDate from, LocalDate to, List<EventItem> items,
                                              int rawCount, int filtered) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artist", artist);
        response.put("query", query);
        response.put("searchedAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(ZONE)));
        response.put("searchedRange", Map.of("from", from.toString(), "to", to.toString()));
        response.put("total", items.size());
        response.put("items", items);
        response.put("notes", notes(items, rawCount, filtered, from, to));
        return response;
    }

    private List<String> notes(List<EventItem> items, int rawCount, int filtered, LocalDate from, LocalDate to) {
        List<String> notes = new ArrayList<>();
        if (rawCount == 0) {
            notes.add("本次检索没有返回结果，可能是关键词未命中或检索服务未覆盖。");
            notes.add("这不代表该艺人没有演出安排；请如实说明未查到，不要编造场次、城市或时间。");
        } else if (items.isEmpty()) {
            notes.add("检索到 " + rawCount + " 条结果，但没有保留与艺人演出直接相关且处于查询区间内的结果。" +
                    (filtered > 0 ? "已过滤 " + filtered + " 条噪音或超出区间的结果。" : ""));
            notes.add("这不代表该艺人没有演出安排；请如实说明未查到，并建议关注艺人、主办方或票务平台官方渠道。");
        } else {
            long official = items.stream().filter(item -> "OFFICIAL".equals(item.sourceType())).count();
            long stale = items.stream().filter(EventItem::isStale).count();
            long unknownPublication = items.stream().filter(item -> item.staleReason() != null).count();
            notes.add("共检索 " + rawCount + " 条，保留 " + items.size() + " 条；其中官方票务平台来源 " + official + " 条。" +
                    (filtered > 0 ? "已过滤 " + filtered + " 条噪音或超出区间的结果。" : ""));
            if (stale > 0) {
                notes.add(stale + " 条信息超过 7 天，可能已变化，回答时必须提示用户重新核验。");
            }
            if (unknownPublication > 0) {
                notes.add(unknownPublication + " 条来源没有可解析的发布时间，无法判断其时效；不要表述为已核验最新信息。");
            }
            if (items.stream().noneMatch(item -> "UPCOMING".equals(item.relativePhase()))) {
                long past = items.stream().filter(item -> "PAST".equals(item.relativePhase())).count();
                if (past > 0) {
                    notes.add("结果中没有即将到来的场次，包含 " + past + " 条已结束场次；可以说明近期暂无新安排并列出最近已结束场次。");
                }
            }
            notes.addAll(conflictNotes(items));
        }
        notes.add("检索区间：" + from + " 至 " + to + "；如需更远期安排，可扩大检索跨度。");
        return notes;
    }

    private List<String> conflictNotes(List<EventItem> items) {
        List<String> conflicts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            EventItem left = items.get(i);
            String title = normalizedTitle(left.title());
            if (title.isBlank()) {
                continue;
            }
            for (int j = i + 1; j < items.size(); j++) {
                EventItem right = items.get(j);
                if (!title.equals(normalizedTitle(right.title())) || !same(left.city(), right.city())) {
                    continue;
                }
                boolean dateConflict = left.eventDate() != null && right.eventDate() != null
                        && !left.eventDate().equals(right.eventDate());
                boolean saleConflict = left.saleStartTime() != null && right.saleStartTime() != null
                        && !left.saleStartTime().equals(right.saleStartTime());
                if (dateConflict || saleConflict) {
                    conflicts.add("检测到同名活动的来源信息冲突：" + left.domain() + " 与 " + right.domain()
                            + " 的活动日期或开票时间不一致。请并列展示来源与说法，不要自行选一个下结论，也不要据此创建提醒。");
                }
            }
        }
        return conflicts.stream().distinct().toList();
    }

    private StaleMark staleMark(Instant publishedAt, Instant now) {
        if (publishedAt == null) {
            return new StaleMark(false, "来源未提供发布时间，无法判断时效");
        }
        long hours = Duration.between(publishedAt, now).toHours();
        if (hours > properties.getStaleHours()) {
            return new StaleMark(true, "该信息发布于 " + hours / 24 + " 天前，可能已变化");
        }
        return new StaleMark(false, null);
    }

    private boolean isOfficial(String domain) {
        if (domain.isBlank() || properties.getOfficialDomains() == null) {
            return false;
        }
        return properties.getOfficialDomains().stream()
                .filter(StrUtil::isNotBlank)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> domain.equals(value) || domain.endsWith("." + value));
    }

    private String domain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private LocalDate extractEventDate(String text) {
        Matcher matcher = EVENT_DATE.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            String prefix = text.substring(Math.max(0, start - 24), start);
            boolean saleContext = SALE_MARKERS.stream().anyMatch(prefix::contains);
            boolean eventContext = EVENT_DATE_MARKERS.stream().anyMatch(prefix::contains);
            if (saleContext && !eventContext) {
                continue;
            }
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
                return date;
            } catch (RuntimeException ignored) {
                // Ignore malformed dates and continue to the next candidate.
            }
        }
        return null;
    }

    private String extractSaleStartTime(String text) {
        int marker = Integer.MAX_VALUE;
        for (String saleMarker : SALE_MARKERS) {
            int index = text.indexOf(saleMarker);
            if (index >= 0) {
                marker = Math.min(marker, index + saleMarker.length());
            }
        }
        if (marker == Integer.MAX_VALUE) {
            return null;
        }
        String window = text.substring(marker, Math.min(text.length(), marker + 48));
        Matcher matcher = SALE_TIME.matcher(window);
        return matcher.find() ? matcher.group().trim() : null;
    }

    private String extractSaleStatus(String text) {
        if (containsAny(text, "售罄", "已抢完", "已售完")) return "SOLD_OUT";
        if (containsAny(text, "已结束", "已落幕")) return "CLOSED";
        if (containsAny(text, "待开票", "即将开票", "预约中", "预约抢票")) return "PENDING";
        if (containsAny(text, "售票中", "正在售票", "立即购买", "立即预订")) return "ON_SALE";
        return "UNKNOWN";
    }

    private String extractCity(String text) {
        return CITY_NAMES.stream().filter(text::contains).findFirst().orElse(null);
    }

    private String phase(LocalDate eventDate, LocalDate today) {
        if (eventDate == null) return "TBD";
        if (eventDate.isBefore(today)) return "PAST";
        if (eventDate.isAfter(today)) return "UPCOMING";
        return "ONGOING";
    }

    private int phaseRank(String phase) {
        return switch (phase) {
            case "UPCOMING" -> 0;
            case "ONGOING" -> 1;
            case "TBD" -> 2;
            default -> 3;
        };
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String normalizedTitle(String title) {
        return text(title).replaceAll("[\\s\\p{Punct}，。！？、：；（）【】《》“”‘’「」…·]", "");
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit) + "…";
    }

    private SearchWindow window(String freshness) {
        return switch (freshness) {
            case "week" -> new SearchWindow(7, 30);
            case "year" -> new SearchWindow(90, 365);
            default -> new SearchWindow(30, 90);
        };
    }

    private record SearchWindow(int pastDays, int futureDays) {
    }

    private record StaleMark(boolean stale, String reason) {
    }

    public record EventItem(String title, String relativePhase, String city, String venue,
                            LocalDate eventDate, String saleStartTime, String saleStatus,
                            String sourceType, String domain, String sourceUrl, String publishedAt,
                            boolean isStale, String staleReason, String excerpt) {
    }
}
