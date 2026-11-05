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
        plans.add(new PlannedQuery(query.artist() + " 巡演 城市 站", null));
        sources.stream().sorted(Comparator.comparing(s -> !"TICKETING".equals(s.tier())))
                .forEach(source -> plans.add(new PlannedQuery("site:" + source.domain() + " " + query.artist() + " 演出", source.domain())));
        plans.add(new PlannedQuery(query.artist() + " 开票 官方", null));
        return plans.stream().distinct().limit(Math.max(1, Math.min(6, limit))).toList();
    }
}
