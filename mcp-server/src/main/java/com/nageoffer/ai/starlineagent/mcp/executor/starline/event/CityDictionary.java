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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchProperties;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class CityDictionary {
    public record Hit(String city, int start, int end, boolean unknown) {}
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Pattern names;
    public CityDictionary(ArtistEventSearchProperties properties) {
        try (var input = getClass().getResourceAsStream("/event-search/cities.json")) {
            var cities = new ObjectMapper().readTree(Objects.requireNonNull(input)).path("cities");
            cities.fieldNames().forEachRemaining(name -> { aliases.put(name, normalize(name)); aliases.put(normalize(name), normalize(name)); });
        } catch (Exception e) { throw new IllegalStateException("城市词典无法加载", e); }
        properties.getCityAliases().forEach((alias, city) -> aliases.put(alias, normalize(city)));
        names = Pattern.compile(aliases.keySet().stream().filter(s -> s.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
    }
    public List<Hit> find(String text) {
        List<Hit> hits = new ArrayList<>();
        var matcher = names.matcher(text);
        while (matcher.find()) hits.add(new Hit(aliases.get(matcher.group()), matcher.start(), matcher.end(), false));
        var station = Pattern.compile("(?:^|[，,；;\\s])([\\p{IsHan}]{2,6})(?:站|奥体中心)").matcher(text);
        while (station.find()) {
            int start = station.start(1), end = station.end(1);
            if (hits.stream().noneMatch(hit -> hit.start() < end && hit.end() > start))
                hits.add(new Hit(station.group(1), start, end, true));
        }
        hits.sort(Comparator.comparingInt(Hit::start));
        return hits;
    }
    public static String normalize(String city) {
        return city == null ? null : city.trim().replaceAll("\\s+", "").replaceFirst("(?:市|特别行政区)$", "");
    }
}
