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

import com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.model.GeoPoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** v3 的事实与计算契约；未知金额和时刻必须保留 null，不能补零。 */
public final class JourneyModels {
    private JourneyModels() {}
    public enum CostStatus { QUOTED, REFERENCE, ESTIMATED, UNKNOWN }
    public enum CostScope { PER_PERSON, PER_PARTY, PER_VEHICLE }
    public enum Feasibility { FEASIBLE, TIGHT, INFEASIBLE, UNKNOWN }
    public enum Confidence { HIGH, MEDIUM, LOW }
    public enum PlanStatus { OK, NEEDS_CLARIFICATION, PARTIAL, FAILED }
    public record Place(String name, String address, String city, String adcode, String level,
                        GeoPoint point, String source, double confidence) {}
    public record Evidence(String provider, String url, String fetchedAt, String note) {}
    public record Warning(String code, String message) {}
    public record Clarification(String field, String question, List<String> options, boolean required) {}
    public record PriceRange(Long minMinor, Long maxMinor, String currency, CostStatus status, String source) {}
    public record EventAnchor(String artist, String tour, String city, Place venue, LocalDateTime showStart,
                              LocalDateTime showEnd, PriceRange ticketPrice, List<Evidence> evidence) {}
    public record CostItem(String category, String label, Long minMinor, Long maxMinor, String currency,
                           CostStatus status, CostScope scope, String source, String note) {
        public CostItem {
            if (status == null || scope == null || currency == null || category == null || label == null)
                throw new IllegalArgumentException("费用缺少状态、范围或币种");
            if (status == CostStatus.UNKNOWN) {
                if (minMinor != null || maxMinor != null) throw new IllegalArgumentException("未知费用必须是 null");
            } else if (minMinor == null || maxMinor == null || minMinor < 0 || maxMinor < minMinor
                    || source == null || source.isBlank()) throw new IllegalArgumentException("费用缺少有效金额或来源");
        }
    }
    /** Total/Subtotal 均为同行合计，perPerson 为按同行人数分摊后的金额。 */
    public record CostSummary(List<CostItem> items, long knownSubtotalMinor, Long minTotalMinor,
                              Long maxTotalMinor, Long perPersonMinMinor, Long perPersonMaxMinor,
                              List<String> unknownCategories, String currency, int partySize) {}
    public record Segment(String mode, Place from, Place to, LocalDateTime departAt, LocalDateTime arriveAt,
                          int durationMinutes, Integer distanceMeters, String serviceNo, String operator,
                          CostItem fare, String source, boolean scheduled,
                          LocalDateTime firstServiceDeparture, LocalDateTime lastServiceDeparture) {}
    public record RouteCandidate(String id, String label, List<Segment> segments,
                                 List<CostItem> extraCosts, List<Warning> warnings, int transfers) {}
    public record ReturnJourney(String status, List<Segment> segments, LocalDateTime departAt,
                                LocalDateTime arriveAt, Feasibility feasibility, List<Warning> warnings) {}
    public record JourneyOption(String id, String label, List<String> modes, List<Segment> segments,
                                int travelMinutes, int totalMinutes, LocalDateTime departOriginAt,
                                LocalDateTime arriveVenueAt, LocalDateTime arriveVenueBy,
                                Feasibility feasibility, Integer slackMinutes, CostSummary cost,
                                Confidence confidence, int transfers, ReturnJourney returnJourney,
                                List<String> assumptions, List<Warning> warnings, List<Evidence> evidence) {}
    public record JourneyRequest(String artist, String city, String origin, String destination,
                                 LocalDate eventDate, String venue, LocalDateTime showStartTime,
                                 LocalDateTime showEndTime, LocalDateTime departAt, LocalDateTime arriveBy,
                                 LocalDateTime returnDepartAt, int partySize, Boolean roundTrip, List<String> modes,
                                 Long budgetMinor, Long ticketPaidMinor, int arrivalBufferMinutes,
                                 Long parkingMinor) {}
    public record JourneyPlan(String schemaVersion, PlanStatus status, JourneyRequest request,
                              EventAnchor event, List<JourneyOption> options, String recommendedOptionId,
                              String recommendReason, List<Clarification> questions,
                              List<Warning> warnings, Instant generatedAt) {}

    public static CostItem unknown(String category, String label, CostScope scope, String note) {
        return new CostItem(category, label, null, null, "CNY", CostStatus.UNKNOWN, scope, null, note);
    }
    public static Segment timed(Segment s, LocalDateTime depart, LocalDateTime arrive) {
        return new Segment(s.mode(), s.from(), s.to(), depart, arrive, s.durationMinutes(), s.distanceMeters(),
                s.serviceNo(), s.operator(), s.fare(), s.source(), s.scheduled(), s.firstServiceDeparture(), s.lastServiceDeparture());
    }
}
