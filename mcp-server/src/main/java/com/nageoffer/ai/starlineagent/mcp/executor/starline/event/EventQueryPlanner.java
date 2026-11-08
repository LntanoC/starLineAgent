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

import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class EventQueryPlanner {
    public record PlannedQuery(String query, String site) {}
    public List<PlannedQuery> plan(EventSearchQuery query, List<OfficialSource> sources, int limit) {
        List<PlannedQuery> plans = new ArrayList<>();
        // City planning must get its target even when the discovery query budget is small.
        if (query.city() != null) plans.add(new PlannedQuery(query.artist() + " " + query.city() + " 演唱会", null));
        plans.add(new PlannedQuery(query.artist() + " 演唱会 巡演", null));
        plans.add(new PlannedQuery(query.artist() + " 音乐节 拼盘 加场 " + EventSearchQuery.today().getYear(), null));
        sources.stream().sorted(Comparator.comparing(s -> !"TICKETING".equals(s.tier())))
                .forEach(source -> plans.add(new PlannedQuery("site:" + source.domain() + " " + query.artist() + " 演出", source.domain())));
        plans.add(new PlannedQuery(query.artist() + " 开票 官方", null));
        return plans.stream().distinct().limit(Math.max(1, Math.min(6, limit))).toList();
    }
    public List<PlannedQuery> followups(EventSearchQuery query, List<OfficialSource> sources,
                                        List<String> cities, int remaining) {
        List<PlannedQuery> result = new ArrayList<>();
        var ranked = sources.stream().sorted(Comparator.comparing(source -> !"TICKETING".equals(source.tier()))).toList();
        // First reach every discovered city, then spend remaining queries on additional official domains.
        if (!ranked.isEmpty()) for (String city : cities)
            result.add(new PlannedQuery("site:" + ranked.get(0).domain() + " " + query.artist() + " " + city + " 演出日期 加场", ranked.get(0).domain()));
        for (var source : ranked) for (String city : cities)
            result.add(new PlannedQuery("site:" + source.domain() + " " + query.artist() + " " + city + " 演出", source.domain()));
        return result.stream().distinct().limit(Math.max(0, remaining)).toList();
    }
}
