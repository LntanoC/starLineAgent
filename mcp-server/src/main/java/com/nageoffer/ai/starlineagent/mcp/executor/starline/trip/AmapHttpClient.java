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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Conditional(AmapApiKeyConfiguredCondition.class)
public class AmapHttpClient {
    record Response(int status, String body) {}
    @FunctionalInterface interface Sender { Response send(HttpRequest request) throws Exception; }
    private record Cached(JsonNode value, long expires) {}
    private final Environment environment;
    private final Sender sender;
    private final Semaphore permits;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean halfOpen = new AtomicBoolean();
    private volatile long openUntil;

    @Autowired
    public AmapHttpClient(Environment environment, TripPlanProperties properties) {
        this(environment, properties, request -> {
            HttpResponse<String> response = SharedClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        });
    }
    private static final class SharedClient {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }
    AmapHttpClient(Environment environment, TripPlanProperties properties, Sender sender) {
        this.environment = environment;
        this.sender = sender;
        this.permits = new Semaphore(Math.max(1, Math.min(16, properties.getMaxConcurrentRequests())));
    }

    public JsonNode get(String path, Map<String, String> parameters) {
        String cacheKey = path + new TreeMap<>(parameters);
        long now = System.currentTimeMillis();
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expires() > now) {
            log.info("journey provider=amap endpoint={} status=OK cacheHit=true", path);
            return cached.value();
        }
        if (openUntil > now) throw new JourneyProviderException("AMAP_CIRCUIT_OPEN", "高德暂时不可用，稍后重试");
        boolean probe = openUntil != 0;
        if (probe && !halfOpen.compareAndSet(false, true))
            throw new JourneyProviderException("AMAP_CIRCUIT_OPEN", "高德服务正在恢复探测");
        if (!permits.tryAcquire()) {
            if (probe) halfOpen.set(false);
            throw new JourneyProviderException("AMAP_QUOTA", "路线查询并发繁忙，请稍后重试");
        }
        long started = System.nanoTime();
        String outcome = "FAILED";
        try {
            String key = environment.getProperty("AMAP_API_KEY");
            if (key == null || key.isBlank()) throw new JourneyProviderException("AMAP_INVALID_KEY", "高德服务未配置");
            TreeMap<String, String> query = new TreeMap<>(parameters);
            query.put("key", key);
            query.put("output", "JSON");
            String args = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://restapi.amap.com/v3" + path + "?" + args))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Response response = sender.send(request);
                    if ((response.status() == 429 || response.status() >= 500) && attempt == 0) { pause(); continue; }
                    if (response.status() != 200) throw new JourneyProviderException("AMAP_HTTP_ERROR", "高德返回异常状态");
                    JsonNode body = mapper.readTree(response.body());
                    String code = body.path("infocode").asText();
                    if (!"1".equals(body.path("status").asText()) || !"10000".equals(code)) {
                        if (Set.of("10003", "10004", "10014", "10019", "10020", "10021").contains(code)) {
                            if (attempt == 0) { pause(); continue; }
                            throw new JourneyProviderException("AMAP_QUOTA", "高德配额或频率受限");
                        }
                        throw new JourneyProviderException(Set.of("10001", "10005", "10009", "10012", "10013").contains(code)
                                ? "AMAP_INVALID_KEY" : "AMAP_REQUEST_REJECTED", "高德未能接受本次查询（" + code.replaceAll("[^0-9]", "") + "）");
                    }
                    if (cache.size() >= 1024) cache.entrySet().removeIf(e -> e.getValue().expires() <= now);
                    if (cache.size() >= 1024) cache.clear();
                    long ttl = path.startsWith("/geocode") || path.startsWith("/place") ? 86400000L : 600000L;
                    cache.put(cacheKey, new Cached(body, System.currentTimeMillis() + ttl));
                    failures.set(0); openUntil = 0; outcome = "OK";
                    return body;
                } catch (java.io.IOException e) {
                    if (attempt == 0) { pause(); continue; }
                    throw new JourneyProviderException("AMAP_TIMEOUT", "高德请求超时或网络暂时不可用");
                }
            }
            throw new JourneyProviderException("AMAP_TIMEOUT", "高德请求未完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JourneyProviderException("AMAP_TIMEOUT", "路线查询已取消或超时");
        } catch (JourneyProviderException e) {
            outcome = e.code();
            if (!Set.of("AMAP_INVALID_KEY", "AMAP_REQUEST_REJECTED").contains(e.code()) && failures.incrementAndGet() >= 5)
                openUntil = System.currentTimeMillis() + 30000;
            throw e;
        } catch (Exception e) {
            outcome = "AMAP_INVALID_RESPONSE";
            if (failures.incrementAndGet() >= 5) openUntil = System.currentTimeMillis() + 30000;
            throw new JourneyProviderException(outcome, "高德返回的数据无法解析");
        } finally {
            permits.release();
            if (probe) halfOpen.set(false);
            log.info("journey provider=amap endpoint={} latencyMs={} status={} cacheHit=false", path,
                    (System.nanoTime() - started) / 1000000, outcome);
        }
    }
    private static void pause() throws InterruptedException { Thread.sleep(200); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
