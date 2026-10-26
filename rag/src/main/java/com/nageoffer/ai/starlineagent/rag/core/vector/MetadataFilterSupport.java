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

package com.nageoffer.ai.starlineagent.rag.core.vector;

import com.google.gson.Gson;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared validation and safe literal rendering for portable scalar metadata equality filters. */
final class MetadataFilterSupport {

    private static final Gson GSON = new Gson();

    private MetadataFilterSupport() {
    }

    static Map<String, Object> normalize(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("metadata filter key must not be blank");
            }
            if (value instanceof String || value instanceof Boolean) {
                normalized.put(key, value);
                return;
            }
            if (value instanceof Number number) {
                try {
                    normalized.put(key, new BigDecimal(number.toString()));
                    return;
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("metadata filter number must be finite: " + key, ex);
                }
            }
            throw new IllegalArgumentException(
                    "metadata filter only supports string, number, or boolean values: " + key);
        });
        return Collections.unmodifiableMap(normalized);
    }

    static String toJson(Map<String, Object> filters) {
        return GSON.toJson(normalize(filters));
    }

    static String toMilvusExpression(Map<String, Object> filters) {
        return normalize(filters).entrySet().stream()
                .map(entry -> "metadata[" + GSON.toJson(entry.getKey()) + "] == " + GSON.toJson(entry.getValue()))
                .collect(Collectors.joining(" and "));
    }
}
