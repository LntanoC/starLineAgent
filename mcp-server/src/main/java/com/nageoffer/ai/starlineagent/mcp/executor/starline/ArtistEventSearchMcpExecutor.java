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
import java.net.IDN;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.string;

/** Searches allowlisted official sources, classifies their tier and merges event evidence. */
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
    private static final List<String> APPROVAL_MARKERS = List.of(
            "营业性演出准予许可", "演出准予许可", "演出许可", "行政许可决定", "许可决定", "批准", "批复", "准予");
    private static final List<String> EVENT_DATE_MARKERS = List.of("演出时间", "演出日期", "举办", "开唱", "演出将", "活动时间");
    private static final List<String> CITY_NAMES = List.of(
            "乌鲁木齐", "呼和浩特", "哈尔滨", "石家庄", "海口", "三亚", "北京", "上海", "广州", "深圳",
            "杭州", "成都", "武汉", "南京", "西安", "重庆", "长沙", "天津", "苏州", "郑州", "青岛",
            "大连", "厦门", "昆明", "合肥", "福州", "济南", "沈阳", "长春", "太原", "南昌", "贵阳", "南宁", "兰州");
    private static final Pattern EVENT_DATE = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private static final Pattern SALE_DATETIME = Pattern.compile(
            "(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*([01]?\\d|2[0-3]):([0-5]\\d)");

    @Qualifier("yuanbaoWebSearchClient")
    private final WebSearchClient searchClient;
    private final ArtistEventSearchProperties properties;
    private final OfficialSourceRegistryClient sourceRegistryClient;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static final String DESCRIPTION = "查询中国大陆艺人近期的演出安排，数据只来自管理员启用的白名单：文旅公示（GOV，仅证明已获批复）和票务平台（TICKETING，提供上架、开票、售罄状态）。适用于近期演出、巡演、场次、城市和开票查询；艺人生日、作品、八卦等问题不要调用。stage=APPROVED表示已获批，不等于确定举办或可购票；ON_SHELF=已上架待开票，ON_SALE=已开票，SOLD_OUT=已售罄，ENDED=已结束。daysToSale为正表示距离开票的天数，0表示今天，负数表示已过开票日，null表示没有准确开票时间；只有canBuyTicket=true才能说现在可以买。total=0只表示本次白名单搜索未查到，不代表没有演出；不得编造场次、日期或时间。涉及最近、今年、下个月等相对时间先调用current_date。演出信息由前端渲染为卡片，只给一句总述，不重复列出卡片字段，也不要在文字中创建或声称已创建提醒。";

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
            List<OfficialSourceRegistryClient.OfficialSource> sources = sourceRegistryClient.listEnabledSources();
            if (sources.isEmpty()) {
                Map<String, Object> emptyResult = buildResponse(artist, List.of(), 0, 0);
                emptyResult.put("notes", List.of(
                        "管理员当前没有启用任何官方域名白名单，本次未执行演出搜索。",
                        "这不代表该艺人没有演出安排；请稍后再查或直接查看文旅网公示与票务平台。"));
                return McpToolResults.success(objectMapper.writeValueAsString(emptyResult));
            }
            int fetchCount = Math.max(1, Math.min(MAX_FETCH_COUNT, properties.getMaxItems() * 3));
            // This window filters event dates below. Tencent Freshness filters page publish dates and cannot include future dates.
            List<WebSearchClient.RawResult> raw = searchClient.search(query, null, fetchCount);

            Instant now = Instant.now();
            List<Candidate> candidates = new ArrayList<>();
            int filtered = 0;
            for (WebSearchClient.RawResult result : raw) {
                OfficialSourceRegistryClient.OfficialSource source = matchedSource(result.url(), sources);
                if (source == null) {
                    filtered++;
                    continue;
                }
                if (!isAboutArtist(result, artist)) {
                    filtered++;
                    continue;
                }
                if ("GOV".equals(source.tier()) && !isPermitNotice(result)) {
                    filtered++;
                    continue;
                }
                EventItem item = toEventItem(result, source, artist, today, now);
                if (item.eventDate() != null && (item.eventDate().isBefore(from) || item.eventDate().isAfter(to))) {
                    filtered++;
                    continue;
                }
                candidates.add(new Candidate(item, source));
            }
            List<EventItem> items = mergeEvents(candidates, today);
            int maxItems = Math.max(1, properties.getMaxItems());
            items.sort(Comparator.comparing(EventItem::eventDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(EventItem::city, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            if (items.size() > maxItems) {
                items = new ArrayList<>(items.subList(0, maxItems));
            }

            Map<String, Object> result = buildResponse(artist, items, raw.size(), filtered);
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

    private boolean isPermitNotice(WebSearchClient.RawResult result) {
        String text = text(result.title()) + " " + text(result.excerpt());
        return APPROVAL_MARKERS.stream().anyMatch(text::contains);
    }

    private EventItem toEventItem(WebSearchClient.RawResult result,
                                  OfficialSourceRegistryClient.OfficialSource source,
                                  String artist, LocalDate today, Instant now) {
        String text = text(result.title()) + " " + text(result.excerpt());
        LocalDate eventDate = extractEventDate(text);
        StaleMark stale = staleMark(result.publishTime(), now);
        String stage = judgeStage(text, source.tier(), eventDate, today);
        LocalDateTime saleStartTime = "TICKETING".equals(source.tier()) ? extractSaleStartTime(text) : null;
        Long daysToSale = saleStartTime == null ? null
                : ChronoUnit.DAYS.between(today, saleStartTime.toLocalDate());
        String title = StrUtil.blankToDefault(clip(result.title(), 240), clip(result.excerpt(), 240));
        String approvedNote = "GOV".equals(source.tier())
                ? approvalNote(source.platformName(), result.publishTime()) : null;
        return new EventItem(
                artist,
                title,
                stage,
                source.tier(),
                extractCity(text),
                eventDate,
                saleStartTime,
                daysToSale,
                "ON_SALE".equals(stage) && !stale.stale(),
                source.platformName(),
                result.url(),
                approvedNote,
                "GOV".equals(source.tier()) ? result.url() : null,
                stale.stale(),
                false,
                List.of(new SourceEvidence(source.platformName(), source.tier(), result.url(), stage)));
    }

    private Map<String, Object> buildResponse(String artist, List<EventItem> items,
                                              int rawCount, int filtered) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artist", artist);
        response.put("total", items.size());
        response.put("items", items);
        response.put("notes", notes(items, rawCount, filtered));
        return response;
    }

    private List<String> notes(List<EventItem> items, int rawCount, int filtered) {
        List<String> notes = new ArrayList<>();
        if (items.isEmpty()) {
            notes.add("本次共检索 " + rawCount + " 条，未展示未通过官方域名白名单、艺人相关性或活动日期筛选的结果。");
            notes.add("这不代表该艺人没有演出安排；只表示本次检索未能从当前白名单渠道筛选出符合条件的信息。");
            notes.add("建议稍后再查，或直接在文旅网和票务平台官方渠道核实。");
        } else {
            long stale = items.stream().filter(EventItem::isStale).count();
            notes.add("本次共检索 " + rawCount + " 条，展示 " + items.size()
                    + " 条白名单渠道结果，" + filtered + " 条未通过白名单、相关性或日期筛选。");
            if (stale > 0) {
                notes.add(stale + " 条信息无法确认在时效范围内；回答时必须提醒用户重新核验，不能据此创建提醒。");
            }
            long conflicts = items.stream().filter(EventItem::conflict).count();
            if (conflicts > 0) {
                notes.add(conflicts + " 条演出存在票务来源状态或开票时间冲突，卡片已保留来源，不能据此创建提醒。");
            }
        }
        return notes;
    }

    private List<EventItem> mergeEvents(List<Candidate> candidates, LocalDate today) {
        Map<EventKey, List<Candidate>> groups = new LinkedHashMap<>();
        int unique = 0;
        for (Candidate candidate : candidates) {
            EventItem item = candidate.item();
            EventKey key = item.eventDate() != null && StrUtil.isNotBlank(item.city())
                    ? new EventKey(item.eventDate(), normalizeCity(item.city()), null)
                    : new EventKey(item.eventDate(), normalizeCity(item.city()), "unmerged-" + unique++);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }

        List<EventItem> result = new ArrayList<>();
        for (List<Candidate> group : groups.values()) {
            List<Candidate> ticketing = group.stream()
                    .filter(candidate -> "TICKETING".equals(candidate.source().tier()))
                    .sorted(Comparator.comparing((Candidate candidate) -> candidate.item().isStale())
                            .thenComparing(candidate -> candidate.item().saleStartTime() == null))
                    .toList();
            List<Candidate> government = group.stream()
                    .filter(candidate -> "GOV".equals(candidate.source().tier()))
                    .sorted(Comparator.comparing(candidate -> candidate.item().isStale()))
                    .toList();
            Candidate primary = ticketing.isEmpty()
                    ? government.get(0) : ticketing.get(0);
            EventItem selected = primary.item();
            Set<String> saleTimes = ticketing.stream().map(candidate -> candidate.item().saleStartTime())
                    .filter(Objects::nonNull).map(LocalDateTime::toString).collect(Collectors.toSet());
            Set<String> saleStages = ticketing.stream().map(candidate -> candidate.item().stage())
                    .collect(Collectors.toSet());
            boolean conflict = saleTimes.size() > 1 || saleStages.size() > 1;
            Candidate approval = government.isEmpty() ? null : government.get(0);
            String approvedNote = approval == null ? null : approval.item().approvedNote();
            String approvedUrl = approval == null ? null : approval.item().sourceUrl();
            List<SourceEvidence> evidence = group.stream().map(Candidate::item)
                    .flatMap(item -> item.sourceEvidence().stream()).distinct().toList();
            LocalDateTime saleStartTime = conflict ? null : selected.saleStartTime();
            Long daysToSale = saleStartTime == null ? null
                    : ChronoUnit.DAYS.between(today, saleStartTime.toLocalDate());
            result.add(new EventItem(selected.artistName(), selected.title(), selected.stage(), selected.tier(),
                    selected.city(), selected.eventDate(), saleStartTime, daysToSale,
                    selected.canBuyTicket() && !conflict, selected.sourceName(), selected.sourceUrl(),
                    approvedNote, approvedUrl, selected.isStale(), conflict, evidence));
        }
        return result;
    }

    private StaleMark staleMark(Instant publishedAt, Instant now) {
        if (publishedAt == null) {
            return new StaleMark(false);
        }
        long hours = Duration.between(publishedAt, now).toHours();
        if (hours > properties.getStaleHours()) {
            return new StaleMark(true);
        }
        return new StaleMark(false);
    }

    private OfficialSourceRegistryClient.OfficialSource matchedSource(
            String url, List<OfficialSourceRegistryClient.OfficialSource> sources) {
        String host = domain(url);
        if (host.isBlank()) {
            return null;
        }
        return sources.stream()
                .filter(source -> {
                    String allowed = normalizeDomain(source.domain());
                    return !allowed.isBlank() && (host.equals(allowed) || host.endsWith("." + allowed));
                })
                .max(Comparator.comparingInt(source -> normalizeDomain(source.domain()).length()))
                .orElse(null);
    }

    private String domain(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            int port = uri.getPort();
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || (port != -1 && port != 80 && port != 443)) {
                return "";
            }
            return normalizeDomain(uri.getHost());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String normalizeDomain(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        try {
            String host = value.trim();
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException ignored) {
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

    private LocalDateTime extractSaleStartTime(String text) {
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
        String window = text.substring(Math.max(0, marker - 32), Math.min(text.length(), marker + 96));
        Matcher matcher = SALE_DATETIME.matcher(window);
        while (matcher.find()) {
            try {
                return LocalDateTime.of(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)));
            } catch (RuntimeException ignored) {
                // Ignore malformed times and continue to another candidate.
            }
        }
        return null;
    }

    private String judgeStage(String text, String tier, LocalDate eventDate, LocalDate today) {
        if (eventDate != null && eventDate.isBefore(today)) {
            return "ENDED";
        }
        if ("TICKETING".equals(tier)) {
            if (containsAny(text, "售罄", "已抢完", "已售完", "缺货登记")) return "SOLD_OUT";
            if (containsAny(text, "售票中", "正在售票", "立即购买", "立即预订", "选座购买")) return "ON_SALE";
            return "ON_SHELF";
        }
        return "APPROVED";
    }

    private String extractCity(String text) {
        return CITY_NAMES.stream().filter(text::contains).findFirst().orElse(null);
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private String normalizeCity(String city) {
        return city == null ? null : city.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String approvalNote(String platformName, Instant publishedAt) {
        if (publishedAt == null) {
            return platformName + " · 文旅许可公示";
        }
        String publishedDate = DateTimeFormatter.ISO_LOCAL_DATE.format(publishedAt.atZone(ZONE));
        return platformName + " · 公示于 " + publishedDate;
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

    private record StaleMark(boolean stale) {
    }

    private record Candidate(EventItem item, OfficialSourceRegistryClient.OfficialSource source) {
    }

    private record EventKey(LocalDate eventDate, String city, String unique) {
    }

    public record SourceEvidence(String sourceName, String tier, String sourceUrl, String stage) {
    }

    public record EventItem(String artistName, String title, String stage, String tier, String city,
                            LocalDate eventDate, LocalDateTime saleStartTime, Long daysToSale,
                            boolean canBuyTicket, String sourceName, String sourceUrl,
                            String approvedNote, String approvedUrl, boolean isStale, boolean conflict,
                            List<SourceEvidence> sourceEvidence) {
    }
}
