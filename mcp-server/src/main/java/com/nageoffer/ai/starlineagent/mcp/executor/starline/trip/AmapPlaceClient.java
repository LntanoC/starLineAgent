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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class AmapPlaceClient {
    private final AmapHttpClient http;
    public Place resolve(String name, String city, boolean specific) {
        if (name == null || name.isBlank() || name.length() > 250)
            throw new JourneyProviderException("GEOCODE_MISSING", "请提供有效的地点名称或地址");
        Map<String, String> args = new java.util.LinkedHashMap<>(Map.of("address", name));
        if (city != null && !city.isBlank()) args.put("city", city);
        JsonNode geocodes = http.get("/geocode/geo", args).path("geocodes");
        if (geocodes.isArray() && geocodes.size() == 1) {
            Place place = parse(geocodes.get(0), name, false);
            if (place != null && Set.of("国家", "省").contains(place.level()))
                throw new JourneyProviderException("GEOCODE_BROAD", "地点仅定位到国家或省，请至少提供城市");
            boolean explicitArea = place != null && (name.equals(place.city()) || name.equals(place.city() == null ? "" : place.city().replaceFirst("市$", ""))
                    || place.address().endsWith(name));
            if (place != null && (!broad(place) || !specific && explicitArea)) return place;
        }
        Map<String, String> poiArgs = new java.util.LinkedHashMap<>(Map.of("keywords", name, "offset", "5", "extensions", "all"));
        if (city != null && !city.isBlank()) { poiArgs.put("city", city); poiArgs.put("citylimit", "true"); }
        JsonNode pois = http.get("/place/text", poiArgs).path("pois");
        List<Place> matches = new ArrayList<>();
        for (JsonNode poi : pois) { Place match = parse(poi, name, true); if (match != null) matches.add(match); }
        List<Place> exact = matches.stream().filter(p -> p.name().equals(name) || p.address().equals(name)).toList();
        if (exact.size() == 1) return exact.get(0);
        if (matches.size() == 1) return matches.get(0);
        if (!matches.isEmpty()) throw new JourneyProviderException("GEOCODE_AMBIGUOUS",
                "地点有多个候选，请补充地址：" + matches.stream().map(p -> p.name() + "（" + p.address() + "）").limit(3).toList());
        throw new JourneyProviderException("GEOCODE_BROAD", "未定位到所需地点，请补充具体名称或地址");
    }
    static Place parse(JsonNode item, String name, boolean poi) {
        try {
            String[] values = item.path("location").asText().split(",");
            double longitude = Double.parseDouble(values[0]), latitude = Double.parseDouble(values[1]);
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude) || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) return null;
            String level = poi ? "兴趣点" : item.path("level").asText();
            String city = value(item.path(poi ? "cityname" : "city"));
            if (city == null) city = value(item.path(poi ? "pname" : "province"));
            // 高德将上海等直辖市的城市定位点标成“省”；按其相同的省/市名称归一为业务市级，仍保留低精度提示。
            if (!poi && "省".equals(level) && city != null && city.endsWith("市") && city.equals(value(item.path("province")))) level = "市";
            return new Place(poi ? item.path("name").asText(name) : name,
                    item.path(poi ? "address" : "formatted_address").asText(name), city, value(item.path("adcode")), level,
                    new GeoPoint(longitude, latitude), "amap", Set.of("国家", "省", "市", "区县").contains(level) ? 0.4 : 0.9);
        } catch (RuntimeException e) { return null; }
    }
    static boolean broad(Place place) { return Set.of("国家", "省", "市", "区县", "").contains(place.level()); }
    static String value(JsonNode node) { return node.isTextual() && !node.asText().isBlank() ? node.asText() : null; }
}
