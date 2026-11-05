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

import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class AmapRouteGateway implements RouteGateway {
    private final AmapPlaceClient places;
    private final AmapTransitClient transit;
    private final AmapDrivingClient driving;
    @Override public Place resolve(String name, String city, boolean specific) { return places.resolve(name, city, specific); }
    @Override public List<RouteCandidate> routes(Place from, Place to, LocalDateTime at, String mode) {
        return "transit".equals(mode) ? transit.routes(from, to, at) : driving.routes(from, to, mode);
    }
}
