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

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Cache and circuit state are per configured provider/key; failed pages never enter the cache. */
@Component
public class ProviderRunner {
    private record Cached(long until, CompletableFuture<SearchProvider.SearchPage> page) {}
    private static class State { final Semaphore permits = new Semaphore(4); final AtomicInteger failures = new AtomicInteger(); volatile long openUntil; }
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();
    public SearchProvider.SearchPage run(SearchProvider provider, SearchProvider.SearchRequest request) {
        String key = provider.providerName() + "|" + request;
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().until < now);
        Cached existing = cache.get(key);
        if (existing != null) return existing.page.join();
        if (cache.size() > 512) cache.clear();
        Cached fresh = new Cached(now + 300_000, new CompletableFuture<>());
        existing = cache.putIfAbsent(key, fresh);
        if (existing != null) return existing.page.join();
        State state = states.computeIfAbsent(provider.providerName(), k -> new State());
        boolean acquired = false;
        try {
            if (state.openUntil > now) throw new IllegalStateException("PROVIDER_CIRCUIT_OPEN");
            acquired = state.permits.tryAcquire();
            if (!acquired) throw new IllegalStateException("PROVIDER_RATE_LIMITED");
            SearchProvider.SearchPage result = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try { result = provider.searchPage(request); break; }
                catch (RuntimeException error) {
                    String message = Objects.toString(error.getMessage(), "");
                    boolean retryable = message.matches("(?s).*(?:5\\d\\d|429|网络|暂时不可用).*");
                    if (attempt == 1 || !retryable || Thread.currentThread().isInterrupted()) throw error;
                }
            }
            state.failures.set(0);
            fresh.page.complete(Objects.requireNonNull(result));
            return result;
        } catch (RuntimeException error) {
            if (state.failures.incrementAndGet() >= 5) state.openUntil = System.currentTimeMillis() + 30_000;
            fresh.page.completeExceptionally(error);
            cache.remove(key, fresh);
            throw error;
        } finally { if (acquired) state.permits.release(); }
    }
}
