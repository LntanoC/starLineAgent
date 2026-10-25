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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.config.YdcApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** You.com adapter shared by the generic search tool and the Starline event search. */
@Slf4j
@Component
@Conditional(YdcApiKeyConfiguredCondition.class)
public class YouComWebSearchClient implements WebSearchClient {

    private static final String API_KEY = "YDC_API_KEY";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment;

    private final String apiUrl;

    @Autowired
    public YouComWebSearchClient(Environment environment) {
        this(environment, "https://ydc-index.io/v1/search");
    }

    public YouComWebSearchClient(Environment environment, String apiUrl) {
        this.environment = environment;
        this.apiUrl = apiUrl;
    }

    @Override
    public List<RawResult> search(String query, String freshness, int count) {
        String apiKey = environment.getProperty(API_KEY);
        if (StrUtil.isBlank(apiKey)) {
            throw new McpToolException("联网检索未配置：请设置环境变量 YDC_API_KEY");
        }
        try {
            StringBuilder url = new StringBuilder(apiUrl)
                    .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&count=").append(count);
            if (StrUtil.isNotBlank(freshness)) {
                url.append("&freshness=").append(URLEncoder.encode(freshness, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-API-Key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new McpToolException("You.com 检索服务返回异常状态码: " + response.statusCode());
            }
            return parse(response.body(), count);
        } catch (McpToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("联网检索被中断，请稍后重试");
        } catch (Exception e) {
            log.error("You.com 联网检索失败", e);
            throw new McpToolException("联网检索暂时不可用，请稍后重试");
        }
    }

    private List<RawResult> parse(String body, int count) throws Exception {
        JsonNode results = objectMapper.readTree(body).path("results");
        List<RawResult> items = new ArrayList<>();
        collect(items, results.path("web"));
        collect(items, results.path("news"));
        return items.stream().limit(Math.max(1, count)).toList();
    }

    private void collect(List<RawResult> target, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            target.add(new RawResult(
                    item.path("title").asText(""),
                    item.path("url").asText(""),
                    excerpt(item),
                    publishTime(item),
                    firstText(item, "site_name", "siteName", "source")));
        }
    }

    private String excerpt(JsonNode item) {
        String description = item.path("description").asText("");
        if (!description.isBlank()) {
            return description;
        }
        JsonNode snippets = item.path("snippets");
        return snippets.isArray() && !snippets.isEmpty() ? snippets.get(0).asText("") : "";
    }

    private Instant publishTime(JsonNode item) {
        for (String field : List.of("page_age", "published_time", "publish_time", "publishedAt", "date")) {
            JsonNode value = item.path(field);
            if (value.isNumber()) {
                long epoch = value.asLong();
                return Instant.ofEpochMilli(epoch > 10_000_000_000L ? epoch : epoch * 1000);
            }
            String raw = value.asText("");
            if (raw.isBlank()) {
                continue;
            }
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(raw).toInstant();
                } catch (DateTimeParseException ignoredOffset) {
                    try {
                        return LocalDateTime.parse(raw).atZone(DEFAULT_ZONE).toInstant();
                    } catch (DateTimeParseException ignoredDateTime) {
                        try {
                            return LocalDate.parse(raw).atStartOfDay(DEFAULT_ZONE).toInstant();
                        } catch (DateTimeParseException ignoredDate) {
                            // Relative strings such as "3 days ago" stay unknown; we do not guess.
                        }
                    }
                }
            }
        }
        return null;
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
