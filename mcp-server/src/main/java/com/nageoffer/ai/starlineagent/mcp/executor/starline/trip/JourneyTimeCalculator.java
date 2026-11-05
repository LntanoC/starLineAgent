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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

public final class JourneyTimeCalculator {
    public record Timeline(List<Segment> segments, LocalDateTime departure, LocalDateTime arrival,
                           int travelMinutes, Feasibility feasibility, Integer slackMinutes, List<Warning> warnings) {}

    public Timeline calculate(List<Segment> facts, LocalDateTime departAt, LocalDateTime arriveBy,
                              int stationBuffer, LocalDateTime now) {
        if (facts.isEmpty() || (departAt == null) == (arriveBy == null) || stationBuffer < 0)
            throw new IllegalArgumentException("请提供路线以及唯一的出发或到达时刻");
        List<Segment> prepared = new ArrayList<>();
        for (Segment fact : facts) {
            if (fact.durationMinutes() < 0 || (fact.scheduled() && (fact.departAt() == null || fact.arriveAt() == null || fact.arriveAt().isBefore(fact.departAt()))))
                throw new IllegalArgumentException("路线缺少有效耗时或班次时刻");
            if ("rail".equals(fact.mode()) && stationBuffer > 0)
                prepared.add(new Segment("station_buffer", fact.from(), fact.from(), null, null, stationBuffer,
                        null, null, null, null, "configured:railStationBufferMinutes", false, null, null));
            prepared.add(fact);
        }
        LocalDateTime start = departAt;
        if (start == null) {
            LocalDateTime cursor = arriveBy;
            for (int i = prepared.size() - 1; i >= 0; i--) {
                Segment segment = prepared.get(i);
                cursor = segment.scheduled() ? segment.departAt() : cursor.minusMinutes(segment.durationMinutes());
            }
            start = cursor;
        }
        LocalDateTime cursor = start;
        List<Segment> timeline = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        boolean missed = false, unknownSchedule = false;
        for (Segment segment : prepared) {
            LocalDateTime departure = cursor;
            LocalDateTime arrival;
            if (segment.scheduled()) {
                if (cursor.isAfter(segment.departAt())) {
                    missed = true;
                    warnings.add(new Warning("MISSED_CONNECTION", "无法在 " + segment.departAt() + " 前赶到 " + segment.from().name()));
                } else if (cursor.isBefore(segment.departAt())) {
                    int wait = Math.toIntExact(Duration.between(cursor, segment.departAt()).toMinutes());
                    if (wait > 0) timeline.add(new Segment("wait", segment.from(), segment.from(), cursor,
                            segment.departAt(), wait, null, null, null, null, "amap:schedule", false, null, null));
                }
                departure = segment.departAt();
                arrival = segment.arriveAt();
            } else {
                arrival = cursor.plusMinutes(segment.durationMinutes());
                if (List.of("rail", "metro", "bus", "transit", "coach", "flight").contains(segment.mode())) unknownSchedule = true;
            }
            if (segment.lastServiceDeparture() != null && departure.isAfter(segment.lastServiceDeparture())) {
                missed = true;
                warnings.add(new Warning("LAST_SERVICE_MISSED", "赶不上 " + segment.from().name() + " 的已知末班服务"));
            }
            if (segment.firstServiceDeparture() != null && departure.isBefore(segment.firstServiceDeparture())) {
                unknownSchedule = true;
                warnings.add(new Warning("BEFORE_FIRST_SERVICE", "计划出发早于已知首班，需重新确认可乘班次"));
            }
            timeline.add(timed(segment, departure, arrival));
            if (arrival.isAfter(cursor)) cursor = arrival;
        }
        if (unknownSchedule) warnings.add(new Warning("RAIL_SCHEDULE_UNKNOWN", "部分公共交通未按真实班次筛选，额外候车时间未计入"));
        Integer slack = arriveBy == null ? null : Math.toIntExact(Math.floorDiv(Duration.between(cursor, arriveBy).getSeconds(), 60));
        Feasibility feasibility = evaluate(slack, unknownSchedule, missed || (now != null && start.isBefore(now)));
        if (now != null && start.isBefore(now)) warnings.add(new Warning("DEPARTURE_PASSED", "计划出发时间已过，当前不能按此方案出发"));
        int duration = Math.max(timeline.stream().mapToInt(Segment::durationMinutes).sum(), Math.toIntExact(Duration.between(start, cursor).toMinutes()));
        // 已错过班次时不存在可实现的到达时刻，不能把停留在起点的时间展示成成功到达。
        return new Timeline(List.copyOf(timeline), start, missed ? null : cursor, duration, feasibility, slack, List.copyOf(warnings));
    }

    public static Feasibility evaluate(Integer slack, boolean unknown, boolean missed) {
        if (missed || (slack != null && slack < 0)) return Feasibility.INFEASIBLE;
        if (unknown) return Feasibility.UNKNOWN;
        if (slack == null || slack >= 15) return Feasibility.FEASIBLE;
        return Feasibility.TIGHT;
    }
}
