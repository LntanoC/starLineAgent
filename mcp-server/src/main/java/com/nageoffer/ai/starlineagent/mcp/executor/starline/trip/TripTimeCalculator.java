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
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolException;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripLeg;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripPlan;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.TripPlanInput;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure time arithmetic around route durations supplied by RouteProvider. */
public final class TripTimeCalculator {

    private static final Set<String> MODES = Set.of("driving", "transit", "walking");

    public TripPlan calculate(TripPlanInput input, RouteProvider routeProvider, int defaultBufferMinutes) {
        if (input == null || StrUtil.isBlank(input.venue()) || StrUtil.isBlank(input.origin())
                || input.eventStartTime() == null || StrUtil.isBlank(input.mode()) || !MODES.contains(input.mode())) {
            throw new McpToolException("请提供有效的场馆、出发地、演出开始时间和通行方式");
        }
        if (defaultBufferMinutes < 0 || (input.arrivalBufferMinutes() != null && input.arrivalBufferMinutes() < 0)) {
            throw new McpToolException("arrivalBufferMinutes 不能小于 0");
        }
        if ((input.intercityArrivalTime() == null) != StrUtil.isBlank(input.intercityArrivalPlace())) {
            throw new McpToolException("intercityArrivalTime 与 intercityArrivalPlace 必须同时提供");
        }

        int bufferMinutes = input.arrivalBufferMinutes() == null
                ? defaultBufferMinutes : input.arrivalBufferMinutes();
        LocalDateTime arriveVenueBy = input.eventStartTime().minusMinutes(bufferMinutes);
        String localOrigin = StrUtil.isBlank(input.intercityArrivalPlace())
                ? input.origin() : input.intercityArrivalPlace();
        LocalDateTime routeQueryTime = input.intercityArrivalTime() == null
                ? arriveVenueBy : input.intercityArrivalTime();
        TripLeg localLeg = routeProvider.route(localOrigin, input.venue(), input.mode(), routeQueryTime);
        if (localLeg == null || localLeg.durationMinutes() < 0 || localLeg.distanceMeters() < 0) {
            throw new McpToolException("高德未返回有效路线耗时和距离，未生成估算结果");
        }
        LocalDateTime latestDeparture = arriveVenueBy.minusMinutes(localLeg.durationMinutes());

        List<String> assumptions = new ArrayList<>(List.of(
                "耗时来自高德路径规划，未含实时路况拥堵，建议额外预留 15 分钟"));
        if ("driving".equals(input.mode()) || input.intercityArrivalPlace() != null) {
            assumptions.add("未考虑安检、取票、找车位时间");
        }
        if (input.intercityArrivalTime() == null) {
            assumptions.add("未包含城际段，请自行确认到达时间；latestDeparture 仅指从所填出发地出发的时间");
        } else {
            assumptions.add("城际到达时间由用户提供；本结果只计算到达地点至场馆的路线，不包含火车或航班行程");
            if (input.intercityArrivalTime().isAfter(latestDeparture)) {
                assumptions.add("用户提供的城际到达时间晚于市内最晚出发时间，按当前路线耗时无法保证准时到场");
            }
        }
        if (input.intercityArrivalTime() == null && "driving".equals(input.mode())) {
            assumptions.set(assumptions.size() - 1,
                    "驾车路线从所填出发地直达场馆；如还需火车或航班，请自行确认其到达时间");
        }

        String confidence = input.intercityArrivalTime() == null && "driving".equals(input.mode())
                ? "MEDIUM" : "LOW";
        return new TripPlan(input.venue(), input.eventStartTime(), arriveVenueBy, localLeg, null,
                latestDeparture, List.copyOf(assumptions), confidence);
    }
}
