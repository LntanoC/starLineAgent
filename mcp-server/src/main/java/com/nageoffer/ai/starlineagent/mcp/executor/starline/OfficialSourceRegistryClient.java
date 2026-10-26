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

package com.nageoffer.ai.starlineagent.mcp.executor.starline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Reads the current allowlist from the API process; no local copy or restart is needed after admin edits. */
@Component
@RequiredArgsConstructor
public class OfficialSourceRegistryClient {
    private final ArtistEventSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public List<OfficialSource> listEnabledSources() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getSourceRegistryUrl()))
                    .timeout(Duration.ofMillis(properties.getSourceRegistryTimeoutMillis()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("来源白名单服务返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"0".equals(root.path("code").asText())) {
                throw new IllegalStateException("来源白名单服务返回失败状态");
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("来源白名单服务返回格式不正确");
            }
            List<OfficialSource> sources = new ArrayList<>();
            for (JsonNode item : data) {
                if (!item.isObject()) {
                    throw new IllegalStateException("来源白名单条目格式不正确");
                }
                String platformName = item.path("platformName").asText("").trim();
                String domain = item.path("domain").asText("").trim();
                String tier = item.path("tier").asText("").trim().toUpperCase(java.util.Locale.ROOT);
                if (platformName.isBlank() || domain.isBlank()
                        || !("GOV".equals(tier) || "TICKETING".equals(tier))) {
                    throw new IllegalStateException("来源白名单缺少平台名称、域名或有效层级");
                }
                sources.add(new OfficialSource(platformName, domain, tier));
            }
            return List.copyOf(sources);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取来源白名单时请求被中断", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("无法读取当前来源白名单", exception);
        }
    }

    public record OfficialSource(String platformName, String domain, String tier) {
    }
}
