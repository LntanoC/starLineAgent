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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event;

import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.EventPageExtractor.Draft;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class EventMerger {
    public List<Draft> merge(List<Draft> drafts) {
        Map<List<Object>, List<Draft>> groups = new LinkedHashMap<>();
        for (var draft : drafts) {
            var item = draft.item();
            // Unknown venues cannot prove that two venues are the same; retain their separate evidence.
            var key = Arrays.<Object>asList(item.artistName(), item.eventDate(), item.city(), item.venueName(),
                    item.eventDate() == null ? item.sourceUrl() + "|" + draft.dateExpression() : item.venueName() == null ? item.sourceUrl() : "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(draft);
        }
        List<Draft> results = new ArrayList<>();
        for (var group : groups.values()) {
            group.sort(Comparator.comparing((Draft d) -> !"TICKETING".equals(d.item().tier())).thenComparing(d -> d.item().isStale()));
            Draft selected = group.get(0);
            EventItem item = selected.item();
            var ticketing = group.stream().map(Draft::item).filter(i -> "TICKETING".equals(i.tier())).toList();
            boolean timeConflict = group.stream().map(d -> d.item().showStartTime()).filter(Objects::nonNull).distinct().count() > 1
                    || group.stream().map(d -> d.item().showEndTime()).filter(Objects::nonNull).distinct().count() > 1;
            boolean saleConflict = ticketing.stream().map(EventItem::saleStartTime).filter(Objects::nonNull).distinct().count() > 1;
            boolean conflict = timeConflict || saleConflict || ticketing.stream().map(EventItem::stage).distinct().count() > 1;
            var govt = group.stream().map(Draft::item).filter(i -> "GOV".equals(i.tier())).findFirst().orElse(null);
            EventItem merged = new EventItem(item.artistName(), item.title(), item.stage(), item.tier(), item.city(), item.eventDate(),
                    conflict ? null : item.saleStartTime(), conflict ? null : item.daysToSale(), item.canBuyTicket() && !conflict,
                    item.sourceName(), item.sourceUrl(), govt == null ? null : govt.approvedNote(), govt == null ? null : govt.sourceUrl(),
                    item.isStale(), conflict, group.stream().flatMap(d -> d.item().sourceEvidence().stream()).distinct().toList(),
                    item.venueName(), item.venueAddress(), timeConflict ? null : item.showStartTime(), timeConflict ? null : item.showEndTime(),
                    item.priceRange(), item.venueSource(), timeConflict ? null : item.timeSource());
            results.add(new Draft(merged, selected.dateUnknown(), selected.cityUnknown(), selected.noise(), selected.dateCandidates(), selected.dateExpression(),
                    group.stream().flatMap(d -> d.queries().stream()).distinct().toList()));
        }
        return results;
    }
    public List<Draft> diverseOrder(List<Draft> input) {
        List<Draft> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing((Draft d) -> d.item().eventDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Draft::noise).thenComparing(d -> Objects.toString(d.item().city(), "")));
        Map<String, Deque<Draft>> groups = new LinkedHashMap<>();
        sorted.forEach(d -> groups.computeIfAbsent(Objects.toString(d.item().city(), "待核实"), k -> new ArrayDeque<>()).add(d));
        List<Draft> output = new ArrayList<>();
        while (output.size() < sorted.size()) for (var queue : groups.values()) if (!queue.isEmpty()) output.add(queue.removeFirst());
        return output;
    }
}
