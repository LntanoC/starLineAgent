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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class AmapTransitClient {
    private static final String SOURCE = "https://restapi.amap.com/v3/direction/transit/integrated";
    private final AmapHttpClient http;

    public List<RouteCandidate> routes(Place from, Place to, LocalDateTime at) {
        if (from.city() == null || to.city() == null) throw new JourneyProviderException("GEOCODE_BROAD", "公交查询缺少起终点城市");
        Map<String, String> parameters = new LinkedHashMap<>(Map.of("origin", AmapDrivingClient.coordinates(from),
                "destination", AmapDrivingClient.coordinates(to), "city", from.city(), "cityd", to.city(),
                "extensions", "all", "strategy", "0", "date", at.toLocalDate().toString(),
                "time", at.withMinute(at.getMinute() / 5 * 5).format(DateTimeFormatter.ofPattern("HH:mm"))));
        return parse(http.get("/direction/transit/integrated", parameters), from, to, at.toLocalDate());
    }

    static List<RouteCandidate> parse(JsonNode response, Place from, Place to, LocalDate date) {
        Map<String, RouteCandidate> unique = new LinkedHashMap<>();
        for (JsonNode transit : response.path("route").path("transits")) {
            int totalSeconds = transit.path("duration").asInt(-1);
            if (totalSeconds < 0) continue;
            List<Segment> segments = new ArrayList<>();
            List<Warning> warnings = new ArrayList<>();
            boolean incomplete = false, hasRail = false;
            Place cursor = from;
            for (JsonNode part : transit.path("segments")) {
                JsonNode walking = part.path("walking");
                if (walking.path("distance").asInt(0) > 0) {
                    int seconds = walking.path("duration").asInt(-1);
                    if (seconds < 0) { incomplete = true; break; }
                    Place end = pointPlace(walking.path("destination").asText(), "换乘步行终点", to);
                    segments.add(segment("walk", cursor, end, seconds, walking.path("distance").asInt(), null, null, null, false, null, null));
                    cursor = end;
                }
                JsonNode line = part.path("bus").path("buslines").path(0);
                if (line.isObject()) {
                    int seconds = line.path("duration").asInt(-1);
                    if (seconds < 0) { incomplete = true; break; }
                    String mode = line.path("type").asText().contains("地铁") || line.path("name").asText().contains("地铁") ? "metro" : "bus";
                    Place departure = stopPlace(line.path("departure_stop"), from);
                    Place arrival = stopPlace(line.path("arrival_stop"), to);
                    LocalDateTime firstService = serviceTime(date, AmapPlaceClient.value(line.path("station_start_time")));
                    LocalDateTime lastService = serviceTime(date, AmapPlaceClient.value(line.path("station_end_time")));
                    if (firstService != null && lastService != null && lastService.isBefore(firstService)) lastService = lastService.plusDays(1);
                    segments.add(segment(mode, departure, arrival, seconds, optionalInt(line.path("distance")),
                            AmapPlaceClient.value(line.path("name")), null, null, false,
                            firstService, lastService));
                    cursor = arrival;
                }
                JsonNode railway = part.path("railway");
                if (railway.isObject() && !railway.isEmpty()) {
                    hasRail = true;
                    LocalDateTime departure = serviceTime(date, AmapPlaceClient.value(railway.path("departure_stop").path("time")));
                    LocalDateTime arrival = serviceTime(date, AmapPlaceClient.value(railway.path("arrival_stop").path("time")));
                    if (arrival != null && departure != null && arrival.isBefore(departure)) arrival = arrival.plusDays(1);
                    boolean scheduled = departure != null && arrival != null;
                    int seconds = scheduled ? Math.toIntExact(Duration.between(departure, arrival).toSeconds())
                            : railway.path("duration").asInt(railway.path("time").asInt(-1));
                    if (seconds < 0) { incomplete = true; break; }
                    Place start = stopPlace(railway.path("departure_stop"), from);
                    Place end = stopPlace(railway.path("arrival_stop"), to);
                    List<Long> fares = new ArrayList<>();
                    for (JsonNode space : railway.path("spaces")) {
                        Long cost = CostCalculator.minor(AmapPlaceClient.value(space.path("cost")));
                        if (cost != null && cost > 0) fares.add(cost);
                    }
                    CostItem fare = fares.isEmpty() ? unknown("rail", start.name() + " → " + end.name(), CostScope.PER_PERSON, "铁路票价待核实，不按里程估价")
                            : new CostItem("rail", start.name() + " → " + end.name() + "（席别参考区间）",
                            Collections.min(fares), Collections.max(fares), "CNY", CostStatus.REFERENCE,
                            CostScope.PER_PERSON, SOURCE, "上游返回席别价格范围，未承诺余票或可售");
                    segments.add(new Segment("rail", start, end, departure, arrival, (seconds + 59) / 60,
                            optionalInt(railway.path("distance")), AmapPlaceClient.value(railway.path("trip")),
                            null, fare, SOURCE, scheduled, null, null));
                    cursor = end;
                }
            }
            List<CostItem> costs = new ArrayList<>();
            if (segments.isEmpty() || incomplete) {
                // 分段耗时缺失时只保留上游完整方案的合计，不把缺失分段填成零分钟。
                segments = new ArrayList<>(List.of(segment("transit", from, to, totalSeconds, null, null, null,
                        unknown(hasRail ? "rail" : "transit", "公共交通费用", CostScope.PER_PERSON, "分段信息不完整，票价待核实"), false, null, null)));
                warnings.add(new Warning("TRANSIT_SEGMENTS_INCOMPLETE", "高德分段耗时不完整，仅展示其路线合计，未验证班次"));
            } else {
                Segment last = segments.get(segments.size() - 1);
                if ("walk".equals(last.mode())) segments.set(segments.size() - 1,
                        new Segment(last.mode(), last.from(), to, null, null, last.durationMinutes(), last.distanceMeters(),
                                null, null, null, last.source(), false, null, null));
                int sum = segments.stream().mapToInt(Segment::durationMinutes).sum();
                if (segments.stream().noneMatch(Segment::scheduled) && (totalSeconds + 59) / 60 > sum)
                    segments.add(segment("transfer", to, to, (((totalSeconds + 59) / 60) - sum) * 60, null, null, null, null, false, null, null));
                if (hasRail) {
                    // 高德的汇总 cost 与铁路 spaces 覆盖口径不稳定，不混加，市内票价独立标未知。
                    if (segments.stream().anyMatch(s -> Set.of("bus", "metro").contains(s.mode())))
                        costs.add(unknown("local_transit", "市内接驳票价", CostScope.PER_PERSON, "铁路及公交汇总费用口径未能独立核验"));
                } else {
                    Long cost = CostCalculator.minor(AmapPlaceClient.value(transit.path("cost")));
                    costs.add(cost == null || cost == 0 ? unknown("transit", "市内公交/地铁", CostScope.PER_PERSON, "高德未返回可核实的公共交通费用")
                            : new CostItem("transit", "市内公交/地铁（去程）", cost, cost, "CNY", CostStatus.REFERENCE,
                            CostScope.PER_PERSON, SOURCE, "高德路线参考费用"));
                }
            }
            int transfers = Math.max(0, (int) segments.stream().filter(s -> Set.of("rail", "metro", "bus").contains(s.mode())).count() - 1);
            String key = segments.stream().map(s -> s.mode() + ":" + s.serviceNo() + ":" + s.from().name() + ":" + s.to().name()).collect(java.util.stream.Collectors.joining("|"));
            unique.putIfAbsent(key, new RouteCandidate("transit-" + unique.size(), hasRail ? "铁路与市内接驳" : "公交/地铁", List.copyOf(segments), List.copyOf(costs), List.copyOf(warnings), transfers));
        }
        return unique.values().stream().sorted(Comparator.comparingInt(c -> c.segments().stream().mapToInt(Segment::durationMinutes).sum())).limit(6).toList();
    }
    static LocalDateTime serviceTime(LocalDate date, String value) {
        if (value == null) return null;
        try {
            if (value.contains("T")) return LocalDateTime.parse(value);
            String compact = value.replace(":", "");
            if (!compact.matches("\\d{4}")) return null;
            int hours = Integer.parseInt(compact.substring(0, 2)), minutes = Integer.parseInt(compact.substring(2));
            if (hours > 71 || minutes > 59) return null;
            return date.atStartOfDay().plusHours(hours).plusMinutes(minutes);
        } catch (RuntimeException e) { return null; }
    }
    private static Segment segment(String mode, Place from, Place to, int seconds, Integer distance, String number,
                                   String operator, CostItem fare, boolean scheduled, LocalDateTime first, LocalDateTime last) {
        return new Segment(mode, from, to, null, null, (seconds + 59) / 60, distance, number, operator, fare, SOURCE, scheduled, first, last);
    }
    private static Integer optionalInt(JsonNode node) { int value = node.asInt(-1); return value < 0 ? null : value; }
    private static Place stopPlace(JsonNode node, Place fallback) {
        return pointPlace(node.path("location").asText(), node.path("name").asText("未标注站点"), fallback);
    }
    private static Place pointPlace(String location, String name, Place fallback) {
        GeoPoint point = null;
        try {
            String[] parts = location.split(",");
            double x = Double.parseDouble(parts[0]), y = Double.parseDouble(parts[1]);
            if (Double.isFinite(x) && Double.isFinite(y) && Math.abs(x) <= 180 && Math.abs(y) <= 90) point = new GeoPoint(x, y);
        } catch (RuntimeException ignored) { }
        return new Place(name, name, fallback.city(), fallback.adcode(), "站点", point, "amap", point == null ? 0.5 : 0.9);
    }
}
