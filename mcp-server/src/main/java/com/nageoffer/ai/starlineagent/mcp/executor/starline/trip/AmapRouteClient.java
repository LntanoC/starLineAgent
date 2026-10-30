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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolException;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripLeg;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripRoutePlan;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Geocodes addresses and returns Amap event-trip and ordinary-route facts. */
@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
public class AmapRouteClient implements RouteProvider, TripRouteProvider {

    private static final String BASE_URL = "https://restapi.amap.com/v3";
    private static final Set<String> BROAD_LEVELS = Set.of("国家", "省", "市", "区县");
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment;

    public AmapRouteClient(Environment environment) {
        this.environment = environment;
    }

    @Override
    public TripRoutePlan plan(String origin, String destination, LocalDateTime departureAt, String mode) {
        if (StrUtil.isBlank(origin) || StrUtil.isBlank(destination) || StrUtil.isBlank(mode)
                || !Set.of("all", "transit", "driving").contains(mode)) {
            throw new McpToolException("请提供出发地、目的地和有效方式：all、transit 或 driving");
        }
        AddressMatch from = routeEndpoint(origin);
        AddressMatch to = routeEndpoint(destination);
        List<TripRoutePlan.Option> options = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        addEndpointAssumption(assumptions, "起点", from);
        addEndpointAssumption(assumptions, "终点", to);

        if ("all".equals(mode) || "transit".equals(mode)) {
            try {
                List<TripRoutePlan.Option> transit = transitOptions(from, to, departureAt);
                options.addAll(transit);
                if (transit.isEmpty()) {
                    warnings.add("高德没有返回公共交通路线");
                }
            } catch (McpToolException e) {
                if ("transit".equals(mode)) {
                    throw e;
                }
                warnings.add("高德公共交通查询暂不可用");
            }
        }
        if ("all".equals(mode) || "driving".equals(mode)) {
            try {
                options.add(drivingOption(from, to));
            } catch (McpToolException e) {
                if ("driving".equals(mode)) {
                    throw e;
                }
                warnings.add("高德自驾路线查询暂不可用");
            }
        }
        if (options.isEmpty()) {
            throw new McpToolException(warnings.isEmpty() ? "高德没有返回可用路线" : String.join("；", warnings));
        }
        if (("all".equals(mode) || "transit".equals(mode)) && departureAt == null) {
            assumptions.add("未提供具体出发日期和时间，公共交通方案未按班次时刻筛选；不要据此认定具体车次或发车时间");
        } else if ("all".equals(mode) || "transit".equals(mode)) {
            assumptions.add("公共交通按所提供的出发日期和时间查询；车票余量和最终票价仍需在官方渠道核实");
        }
        if ("all".equals(mode) || "driving".equals(mode)) {
            assumptions.add("自驾耗时来自高德路径规划，未来实际路况可能变化");
        }
        assumptions.addAll(warnings);
        if (("all".equals(mode) || "transit".equals(mode)) && options.stream().anyMatch(option -> "transit".equals(option.mode()))) {
            assumptions.add("公共交通方案和参考费用来自高德路径规划，不代表余票或可售状态");
        }
        return new TripRoutePlan(origin, destination, departureAt, options, assumptions);
    }

    public GeoPoint geocode(String address) {
        return geocodeResult(address).point();
    }

    @Override
    public TripLeg route(String origin, String destination, String mode, LocalDateTime departAt) {
        // Transit duration is the value used to calculate departAt, so query the time-agnostic route to avoid guessing a timetable.
        AddressMatch from = geocodeResult(origin, false);
        AddressMatch to = geocodeResult(destination, true);
        String endpoint;
        List<String> parameters = new ArrayList<>(List.of(
                "origin=" + coordinates(from.point()), "destination=" + coordinates(to.point())));
        switch (mode) {
            case "driving" -> {
                endpoint = "/direction/driving";
                parameters.add("extensions=base");
                parameters.add("strategy=10");
            }
            case "walking" -> endpoint = "/direction/walking";
            case "transit" -> {
                String fromCity = StrUtil.blankToDefault(from.citycode(), from.city());
                String toCity = StrUtil.blankToDefault(to.citycode(), to.city());
                if (StrUtil.isBlank(fromCity) || !fromCity.equals(toCity)) {
                    throw new McpToolException("公共交通路线仅支持同城查询；城际交通时刻请自行确认");
                }
                endpoint = "/direction/transit/integrated";
                parameters.add("city=" + encode(fromCity));
            }
            default -> throw new McpToolException("mode 参数不合法，可选值：driving、transit、walking");
        }
        JsonNode response = get(endpoint, parameters);
        JsonNode route = response.path("route");
        JsonNode path = "transit".equals(mode) ? route.path("transits").path(0) : route.path("paths").path(0);
        long durationSeconds = path.path("duration").asLong(-1);
        long distanceMeters = "transit".equals(mode) ? transitDistance(path) : path.path("distance").asLong(-1);
        if (path.isMissingNode() || durationSeconds < 0 || distanceMeters < 0) {
            throw new McpToolException("高德未返回有效路线耗时和距离，未生成估算结果");
        }
        return new TripLeg(origin, destination, mode, (int) Math.ceil(durationSeconds / 60.0),
                Math.toIntExact(distanceMeters), "amap");
    }

    private AddressMatch geocodeResult(String address) {
        return geocodeResult(address, false);
    }

    private AddressMatch geocodeResult(String address, boolean requireSpecificLocation) {
        if (StrUtil.isBlank(address)) {
            throw new McpToolException("地址不能为空");
        }
        JsonNode response = get("/geocode/geo", List.of("address=" + encode(address)));
        JsonNode item = response.path("geocodes").path(0);
        String[] coordinates = item.path("location").asText("").split(",", -1);
        try {
            if (coordinates.length != 2) {
                throw new NumberFormatException();
            }
            GeoPoint point = new GeoPoint(Double.parseDouble(coordinates[0]), Double.parseDouble(coordinates[1]));
            String level = item.path("level").asText("");
            if (requireSpecificLocation && BROAD_LEVELS.contains(level)) {
                throw new McpToolException("高德只匹配到较大区域，请补充具体场馆地址");
            }
            return new AddressMatch(point, item.path("city").asText(""), item.path("citycode").asText(""), level);
        } catch (NumberFormatException e) {
            throw new McpToolException("高德未能将地址解析到具体坐标，请补充场馆或出发地地址");
        }
    }

    private AddressMatch routeEndpoint(String address) {
        AddressMatch match = geocodeResult(address, false);
        if (Set.of("国家", "省").contains(match.level())) {
            throw new McpToolException("请至少提供城市级出发地和目的地");
        }
        return match;
    }

    private void addEndpointAssumption(List<String> assumptions, String label, AddressMatch match) {
        if ("市".equals(match.level())) {
            assumptions.add(label + "只提供城市名，按高德城市定位点规划；结果不是门到门路线，提供具体地址可提高准确性");
        } else if ("区县".equals(match.level())) {
            assumptions.add(label + "只精确到区县，按高德行政区定位点规划；结果不是门到门路线，提供具体地址可提高准确性");
        }
    }

    private List<TripRoutePlan.Option> transitOptions(AddressMatch from, AddressMatch to,
            LocalDateTime departureAt) {
        if (StrUtil.isBlank(from.citycode()) || StrUtil.isBlank(to.citycode())) {
            throw new McpToolException("高德未能识别起点或终点城市，请补充城市或详细地址");
        }
        List<String> parameters = new ArrayList<>(List.of(
                "origin=" + coordinates(from.point()),
                "destination=" + coordinates(to.point()),
                "city=" + encode(from.citycode()),
                "extensions=all",
                "strategy=0"));
        if (!from.citycode().equals(to.citycode())) {
            parameters.add("cityd=" + encode(to.citycode()));
        }
        if (departureAt != null) {
            parameters.add("date=" + departureAt.format(DateTimeFormatter.ofPattern("yyyy-M-d")));
            parameters.add("time=" + departureAt.format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        JsonNode transits = get("/direction/transit/integrated", parameters).path("route").path("transits");
        if (!transits.isArray()) {
            return List.of();
        }
        List<TripRoutePlan.Option> options = new ArrayList<>();
        for (JsonNode transit : transits) {
            long durationSeconds = transit.path("duration").asLong(-1);
            if (durationSeconds < 0) {
                continue;
            }
            options.add(parseTransitOption(transit, departureAt != null));
        }
        return options.stream().sorted(Comparator.comparingLong(TripRoutePlan.Option::durationMinutes))
                .limit(3).toList();
    }

    private TripRoutePlan.Option drivingOption(AddressMatch from, AddressMatch to) {
        JsonNode response = get("/direction/driving", List.of(
                "origin=" + coordinates(from.point()),
                "destination=" + coordinates(to.point()),
                "extensions=all",
                "strategy=10"));
        JsonNode path = response.path("route").path("paths").path(0);
        long durationSeconds = path.path("duration").asLong(-1);
        long distanceMeters = path.path("distance").asLong(-1);
        if (path.isMissingNode() || durationSeconds < 0 || distanceMeters < 0) {
            throw new McpToolException("高德未返回有效自驾路线耗时和距离");
        }
        List<String> steps = new ArrayList<>();
        for (JsonNode step : path.path("steps")) {
            String instruction = StrUtil.trimToNull(step.path("instruction").asText(""));
            if (instruction != null && steps.size() < 8) {
                steps.add(instruction);
            }
        }
        return new TripRoutePlan.Option("driving", minutes(durationSeconds), distanceMeters,
                null, "amap", steps);
    }

    static TripRoutePlan.Option parseTransitOption(JsonNode transit, boolean includeSchedule) {
        long durationSeconds = transit.path("duration").asLong(-1);
        if (durationSeconds < 0) {
            throw new McpToolException("高德未返回有效公共交通耗时");
        }
        List<String> steps = new ArrayList<>();
        for (JsonNode segment : transit.path("segments")) {
            JsonNode walking = segment.path("walking");
            long walkingDistance = walking.path("distance").asLong(0);
            if (walkingDistance > 0 && steps.size() < 8) {
                steps.add("步行约 " + walkingDistance + " 米");
            }
            for (JsonNode busLine : segment.path("bus").path("buslines")) {
                if (steps.size() >= 8) {
                    break;
                }
                String name = StrUtil.blankToDefault(busLine.path("name").asText(""), "公交/地铁");
                String from = busLine.path("departure_stop").path("name").asText("");
                String to = busLine.path("arrival_stop").path("name").asText("");
                steps.add("乘坐 " + name + (from.isBlank() && to.isBlank() ? "" : "（" + from + " → " + to + "）"));
            }
            JsonNode railway = segment.path("railway");
            if (!railway.isMissingNode() && !railway.isNull() && steps.size() < 8) {
                String train = includeSchedule ? railway.path("trip").asText("") : "";
                String from = railway.path("departure_stop").path("name").asText("");
                String to = railway.path("arrival_stop").path("name").asText("");
                String fromTime = includeSchedule ? railway.path("departure_stop").path("time").asText("") : "";
                String toTime = includeSchedule ? railway.path("arrival_stop").path("time").asText("") : "";
                String label = railTypeName(railway.path("type").asText(""));
                StringBuilder step = new StringBuilder("乘坐").append(label);
                if (!train.isBlank()) {
                    step.append(' ').append(train);
                }
                if (!from.isBlank() || !to.isBlank()) {
                    step.append("（").append(from);
                    if (!fromTime.isBlank()) {
                        step.append(' ').append(fromTime).append("发车");
                    }
                    step.append(" → ").append(to);
                    if (!toTime.isBlank()) {
                        step.append(' ').append(toTime).append("到站");
                    }
                    step.append('）');
                }
                steps.add(step.toString());
            }
        }
        if (steps.isEmpty()) {
            steps.add("高德公共交通换乘方案");
        }
        long distance = transitDistance(transit);
        String cost = StrUtil.trimToNull(transit.path("cost").asText(""));
        return new TripRoutePlan.Option("transit", minutes(durationSeconds),
                distance < 0 ? null : distance, cost, "amap", steps);
    }

    private static String railTypeName(String type) {
        return switch (type) {
            case "2011" -> "高铁";
            case "2012" -> "动车";
            case "2013" -> "城际列车";
            case "2014" -> "直达特快列车";
            case "2015" -> "特快列车";
            case "2016" -> "快速列车";
            case "2017" -> "临时列车";
            case "2018" -> "市郊列车";
            default -> "火车";
        };
    }

    private static long minutes(long durationSeconds) {
        return (durationSeconds + 59) / 60;
    }

    private JsonNode get(String path, List<String> parameters) {
        String key = environment.getProperty("AMAP_API_KEY");
        if (StrUtil.isBlank(key)) {
            throw new McpToolException("出行规划未配置：请设置环境变量 AMAP_API_KEY");
        }
        List<String> query = new ArrayList<>(parameters);
        query.add("output=JSON");
        query.add("key=" + encode(key));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path + "?" + String.join("&", query)))
                .timeout(Duration.ofSeconds(2)).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new McpToolException("高德路径服务返回异常状态码: " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (!"1".equals(body.path("status").asText()) || !"10000".equals(body.path("infocode").asText())) {
                throw new McpToolException("高德路径查询失败: " + body.path("info").asText("未知错误"));
            }
            return body;
        } catch (McpToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("高德路径查询被中断，请稍后重试");
        } catch (Exception e) {
            throw new McpToolException("高德路径服务暂时不可用，请稍后重试");
        }
    }

    private static long transitDistance(JsonNode transit) {
        long distance = 0;
        boolean found = false;
        for (JsonNode segment : transit.path("segments")) {
            JsonNode walking = segment.path("walking");
            if (walking.hasNonNull("distance")) {
                distance += walking.path("distance").asLong();
                found = true;
            }
            for (JsonNode busLine : segment.path("bus").path("buslines")) {
                if (busLine.hasNonNull("distance")) {
                    distance += busLine.path("distance").asLong();
                    found = true;
                }
            }
            JsonNode railway = segment.path("railway");
            if (railway.hasNonNull("distance")) {
                distance += railway.path("distance").asLong();
                found = true;
            }
        }
        return found ? distance : -1;
    }

    private String coordinates(GeoPoint point) {
        return point.longitude() + "," + point.latitude();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AddressMatch(GeoPoint point, String city, String citycode, String level) {
    }
}
