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
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class AmapDrivingClient {
    private final AmapHttpClient http;
    private final TripPlanProperties properties;

    public List<RouteCandidate> routes(Place from, Place to, String mode) {
        String endpoint = "walk".equals(mode) ? "/direction/walking" : "/direction/driving";
        JsonNode response = http.get(endpoint, Map.of("origin", coordinates(from), "destination", coordinates(to), "extensions", "all"));
        return parse(response, from, to, mode, properties);
    }
    static List<RouteCandidate> parse(JsonNode response, Place from, Place to, String mode, TripPlanProperties properties) {
        JsonNode path = response.path("route").path("paths").path(0);
        int seconds = path.path("duration").asInt(-1), distance = path.path("distance").asInt(-1);
        if (seconds < 0 || distance < 0) throw new JourneyProviderException("AMAP_NO_ROUTE", "高德没有返回有效路线耗时和距离");
        if ("walk".equals(mode) && distance > 3000) return List.of();
        CostItem fare = null;
        if ("walk".equals(mode)) fare = new CostItem("walk", "步行交通费", 0L, 0L, "CNY", CostStatus.REFERENCE,
                CostScope.PER_PERSON, "rule:walking-no-transport-fare", "不含餐饮等个人消费");
        List<CostItem> extra = List.of();
        if ("taxi".equals(mode)) {
            Long cost = CostCalculator.minor(response.path("route").path("taxi_cost").asText(null));
            fare = cost == null || cost == 0 ? unknown("taxi", "打车费用", CostScope.PER_PARTY, "未获得高德打车参考费用")
                    : new CostItem("taxi", "打车参考费用", cost, cost, "CNY", CostStatus.REFERENCE, CostScope.PER_PARTY,
                    "https://restapi.amap.com/v3/direction/driving", "高德参考费用，不代表网约车报价");
        } else if ("driving".equals(mode)) {
            Long toll = CostCalculator.minor(path.path("tolls").asText(null));
            CostItem tollItem = toll == null ? unknown("toll", "过路费（去程）", CostScope.PER_VEHICLE, "高德未返回过路费")
                    : new CostItem("toll", "过路费（去程）", toll, toll, "CNY", CostStatus.REFERENCE,
                    CostScope.PER_VEHICLE, "https://restapi.amap.com/v3/direction/driving", "按一辆车计");
            extra = List.of(tollItem, CostCalculator.fuel(distance, properties.getLitresPer100km(), properties.getYuanPerLitre()));
        }
        Segment segment = new Segment(mode, from, to, null, null, (seconds + 59) / 60, distance,
                null, null, fare, "https://restapi.amap.com/v3/direction" + ("walk".equals(mode) ? "/walking" : "/driving"), false, null, null);
        return List.of(new RouteCandidate(mode, switch (mode) { case "walk" -> "步行"; case "taxi" -> "打车"; default -> "自驾"; },
                List.of(segment), extra, List.of(), 0));
    }
    static String coordinates(Place place) { return place.point().longitude() + "," + place.point().latitude(); }
}
