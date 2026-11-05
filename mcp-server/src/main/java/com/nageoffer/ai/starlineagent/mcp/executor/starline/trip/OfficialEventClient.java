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

import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.EventSearchService;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.EventItem;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OfficialEventClient implements OfficialEventGateway {
    private final ObjectProvider<EventSearchService> provider;
    @Override public List<EventItem> search(String artist, String city) {
        // The shared cache revalidates the database allowlist, including immediately disabled sources.
        EventSearchService client = provider.getIfAvailable();
        if (client == null) throw new JourneyProviderException("EVENT_SERVICE_UNAVAILABLE", "官方演出查询未配置，无法核实场次");
        return client.searchEvents(artist, city);
    }
}
