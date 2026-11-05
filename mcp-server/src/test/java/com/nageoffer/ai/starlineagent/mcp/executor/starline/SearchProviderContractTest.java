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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.SearchProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SearchProviderContractTest {
    @Test void yuanbaoSendsSupportedCountSiteAndExposesSinglePageLimit() throws Exception {
        var request = new AtomicReference<String>();
        var body = new AtomicReference<>("{\"Pages\":[{\"url\":\"https://example.com/event\",\"title\":\"tour\",\"passage\":\"details\"}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/SearchPro", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var client = new YuanbaoWebSearchClient(new MockEnvironment().withProperty("YUANBAO_SEARCH_API_KEY", "fixture"), "http://127.0.0.1:" + server.getAddress().getPort() + "/SearchPro");
            var page = client.searchPage(new SearchProvider.SearchRequest("artist tour", "example.com", 0, 20, null));
            var json = new ObjectMapper().readTree(request.get());
            assertEquals(20, json.path("Cnt").asInt()); assertEquals("example.com", json.path("Site").asText());
            assertFalse(json.has("Page")); assertFalse(json.has("Freshness")); assertTrue(page.providerPageLimit());
            assertEquals(1, page.results().size());
            body.set("{\"Response\":{\"RequestId\":\"fixture\",\"Query\":\"q\",\"Version\":\"standard\"}}");
            assertTrue(client.search("q", null, 20).isEmpty());
            body.set("{}");
            assertThrows(RuntimeException.class, () -> client.search("q", null, 20));
            body.set("{\"Response\":{\"RequestId\":\"fixture\",\"Query\":\"q\",\"Version\":\"standard\",\"Msg\":\"hit black query\"}}");
            assertThrows(RuntimeException.class, () -> client.search("q", null, 20));
            assertThrows(IllegalArgumentException.class, () -> client.searchPage(new SearchProvider.SearchRequest("q", null, 1, 20, null)));
            body.set("{\"Response\":{\"Error\":{\"Code\":\"UnauthorizedOperation\"}}}");
            assertThrows(RuntimeException.class, () -> client.search("q", null, 20));
        } finally { server.stop(0); }
    }
}
