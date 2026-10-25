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
import com.nageoffer.ai.starlineagent.mcp.config.YuanbaoApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Tencent Yuanbao Search adapter; the MCP tool depends only on the shared provider boundary. */
@Slf4j
@Component("yuanbaoWebSearchClient")
@Conditional(YuanbaoApiKeyConfiguredCondition.class)
public class YuanbaoWebSearchClient implements WebSearchClient {

    private static final String API_KEY = "YUANBAO_SEARCH_API_KEY";
    private static final String API_URL = "https://api.wsa.cloud.tencent.com/SearchPro";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment;

    public YuanbaoWebSearchClient(Environment environment) {
        this.environment = environment;
    }

    @Override
    public List<RawResult> search(String query, String publicationFreshness, int count) {
        String apiKey = environment.getProperty(API_KEY);
        if (StrUtil.isBlank(apiKey)) {
            throw new McpToolException("演出查询未配置：请设置环境变量 YUANBAO_SEARCH_API_KEY");
        }
        try {
            // Tencent Freshness filters page publication dates. Starline applies a separate
            // past/future event-date window after receiving search results, so omit Freshness.
            String requestBody = objectMapper.createObjectNode()
                    .put("Query", query)
                    .toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new McpToolException("腾讯元宝搜索返回异常状态码: " + response.statusCode());
            }
            return parse(response.body(), count);
        } catch (McpToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("腾讯元宝搜索请求被中断，请稍后重试");
        } catch (Exception e) {
            log.error("腾讯元宝搜索请求失败", e);
            throw new McpToolException("腾讯元宝搜索暂时不可用，请稍后重试");
        }
    }

    private List<RawResult> parse(String body, int count) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode pages = root.path("Response").path("Pages");
        if (!pages.isArray()) {
            pages = root.path("Pages");
        }
        List<RawResult> results = new ArrayList<>();
        for (JsonNode page : pages) {
            JsonNode item = page.isTextual() ? objectMapper.readTree(page.asText()) : page;
            if (item == null) {
                continue;
            }
            String url = text(item, "url");
            if (StrUtil.isBlank(url)) {
                continue;
            }
            results.add(new RawResult(
                    text(item, "title"),
                    url,
                    firstText(item, "passage", "content"),
                    publishTime(item),
                    text(item, "site")));
            if (results.size() >= Math.max(1, count)) {
                break;
            }
        }
        return results;
    }

    private Instant publishTime(JsonNode item) {
        String value = firstText(item, "date", "publish_time", "published_time");
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATS) {
                    try {
                        return LocalDateTime.parse(value, formatter).atZone(DEFAULT_ZONE).toInstant();
                    } catch (DateTimeParseException ignoredDateTime) {
                        // Try the next documented date form.
                    }
                }
                for (DateTimeFormatter formatter : LOCAL_DATE_FORMATS) {
                    try {
                        return LocalDate.parse(value, formatter).atStartOfDay(DEFAULT_ZONE).toInstant();
                    } catch (DateTimeParseException ignoredDate) {
                        // A non-standard date stays unknown; do not guess.
                    }
                }
            }
        }
        return null;
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = text(item, field);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode item, String field) {
        return item.path(field).asText("");
    }
}