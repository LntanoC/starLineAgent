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

public final class TourStopParser {
    private TourStopParser() {}
    /** 只在 artist+city 槽位消除巡演“站”；普通路线 origin/destination 原样交给地点解析。 */
    public static String city(String value) {
        if (value == null || value.isBlank()) return null;
        String city = value.trim();
        if (city.matches(".*(?:从|到|出发|换乘|火车站|铁路站).*"))
            throw new IllegalArgumentException("city 请填演出城市；火车站请放在 origin 或 destination");
        return city.replaceFirst("(?:站|场|市)$", "");
    }
}
