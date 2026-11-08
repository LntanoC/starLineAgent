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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchMcpExecutor.EventItem;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.EventPageExtractor.Draft;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class EventSearchService {
    private record Found(WebSearchClient.RawResult raw, String query) {}
    private record Batch(String provider, EventQueryPlanner.PlannedQuery query, List<Found> found, boolean pageLimit, String failure) {}
    private record Snapshot(String token, String key, long expires, List<Draft> items, Map<String, Object> coverage) {}
    private final List<WebSearchClient> providers;
    private final ArtistEventSearchProperties properties;
    private final OfficialSourceRegistryClient registry;
    private final EventQueryPlanner planner;
    private final CityDictionary cityDictionary;
    private final EventPageExtractor extractor;
    private final EventMerger merger;
    private final SourceResolver resolver;
    private final ProviderRunner runner;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48), task -> {
        Thread thread = new Thread(task, "event-discovery"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String, CompletableFuture<Snapshot>> flights = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ExecutorService redirectWorkers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "event-source-redirect"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    public EventSearchService(List<WebSearchClient> providers, ArtistEventSearchProperties properties, OfficialSourceRegistryClient registry,
                              EventQueryPlanner planner, EventPageExtractor extractor, EventMerger merger, SourceResolver resolver, ProviderRunner runner) {
        this.providers = providers; this.properties = properties; this.registry = registry; this.planner = planner;
        this.cityDictionary = new CityDictionary(properties);
        this.extractor = extractor; this.merger = merger; this.resolver = resolver; this.runner = runner;
    }
    public Map<String, Object> search(Map<String, Object> args) {
        Map<String, Object> normalized = new LinkedHashMap<>(args);
        if (!normalized.containsKey("window") && !normalized.containsKey("freshness")) normalized.put("window", properties.getDiscoveryWindow());
        return search(EventSearchQuery.from(normalized));
    }
    public Map<String, Object> search(EventSearchQuery query) {
        List<OfficialSource> sources;
        try { sources = registry.listEnabledSources(); }
        catch (RuntimeException error) {
            if (query.cursor() != null) throw new IllegalArgumentException("来源状态暂不可核验，请稍后重新查询");
            return unavailable(query, "SOURCE_REGISTRY_UNAVAILABLE");
        }
        String fingerprint = sources.stream().map(Object::toString).sorted().collect(java.util.stream.Collectors.joining(";"));
        String key = query.key() + "|" + fingerprint;
        long now = System.currentTimeMillis();
        snapshots.entrySet().removeIf(entry -> entry.getValue().expires < now);
        Snapshot snapshot;
        int offset = 0;
        if (query.cursor() != null) {
            try {
                String[] cursor = new String(Base64.getUrlDecoder().decode(query.cursor()), StandardCharsets.UTF_8).split(":");
                snapshot = snapshots.get(cursor[0]); offset = Integer.parseInt(cursor[1]);
                if (cursor.length != 2 || snapshot == null || !snapshot.key.equals(key) || offset < 1 || offset >= snapshot.items.size()) throw new IllegalArgumentException();
            } catch (RuntimeException error) { throw new IllegalArgumentException("分页游标已过期或来源/条件已变化，请重新查询"); }
        } else {
            snapshot = snapshots.values().stream().filter(s -> s.key.equals(key) && ((List<?>) s.coverage.get("failures")).isEmpty()).findFirst().orElse(null);
            if (snapshot == null) {
                CompletableFuture<Snapshot> fresh = new CompletableFuture<>();
                CompletableFuture<Snapshot> previous = flights.putIfAbsent(key, fresh);
                if (previous != null) snapshot = previous.join();
                else try {
                    snapshot = discover(query, sources, key);
                    if (snapshots.size() >= 128) snapshots.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().expires)).ifPresent(e -> snapshots.remove(e.getKey()));
                    // Failed searches are retriable immediately; cursor snapshots remain stable for five minutes.
                    if (((List<?>) snapshot.coverage.get("failures")).isEmpty()) snapshots.put(snapshot.token, snapshot);
                    else if (!snapshot.items.isEmpty()) snapshots.put(snapshot.token, snapshot);
                    fresh.complete(snapshot);
                } catch (RuntimeException error) { fresh.completeExceptionally(error); throw error; }
                finally { flights.remove(key, fresh); }
            }
        }
        return page(query, snapshot, offset);
    }
    /** The journey caller consumes typed candidates through the same policy boundary. */
    public List<EventItem> searchEvents(String artist, String city) {
        var result = search(new EventSearchQuery(artist, CityDictionary.normalize(city), "year", false, 50, null));
        var coverage = (Map<?, ?>) result.get("coverage");
        if ("UNKNOWN".equals(coverage.get("completeness")) || (((List<?>) result.get("items")).isEmpty() && !((List<?>) coverage.get("failures")).isEmpty()))
            throw new IllegalStateException("官方演出检索未完成");
        return mapper.convertValue(result.get("items"), new TypeReference<List<EventItem>>() {});
    }
    private Snapshot discover(EventSearchQuery query, List<OfficialSource> sources, String key) {
        long started = System.currentTimeMillis();
        // City queries also serve the existing four-second journey boundary; keep its route algorithms unchanged.
        long deadline = started + Math.max(500, Math.min(query.city() == null ? 8000 : 3000, properties.getDiscoveryTimeoutMillis()));
        var plans = new ArrayList<>(planner.plan(query, sources, properties.getMaxQueries()));
        List<String> failures = new ArrayList<>();
        var rawBudget = new java.util.concurrent.atomic.AtomicInteger(Math.max(1, Math.min(200, properties.getMaxRawResults())));
        boolean discovery = query.city() == null;
        long detailReserve = properties.isReadDetails() && discovery ? Math.min(Math.max(0, properties.getDetailBudgetMillis()), (deadline-started)/4) : 0;
        long searchDeadline = deadline-detailReserve;
        boolean adaptive = discovery && properties.getMaxQueries() > plans.size();
        long firstDeadline = adaptive ? started + (searchDeadline-started)*3/5 : searchDeadline;
        Phase first = runPhase(plans, sources, firstDeadline, rawBudget);
        List<Batch> batches = new ArrayList<>(first.batches());
        failures.addAll(first.failures());
        int submitted = first.submitted();
        if (adaptive && !sources.isEmpty() && !providers.isEmpty()) {
            Set<String> discovered = new LinkedHashSet<>();
            for (Batch batch : batches) for (Found page : batch.found()) {
                String title = Objects.toString(page.raw.title(), "");
                String text = title + " " + Objects.toString(page.raw.excerpt(), "");
                if (text.contains(query.artist())) cityDictionary.find(text).stream().filter(hit -> !hit.unknown())
                        .map(CityDictionary.Hit::city).forEach(discovered::add);
            }
            var followups = planner.followups(query, sources, List.copyOf(discovered), Math.min(12, properties.getMaxQueries())-plans.size());
            followups = followups.stream().filter(plan -> !plans.contains(plan)).toList();
            if (System.currentTimeMillis() < searchDeadline && !followups.isEmpty()) {
                plans.addAll(followups);
                Phase second = runPhase(followups, sources, searchDeadline, rawBudget);
                batches.addAll(second.batches()); failures.addAll(second.failures()); submitted += second.submitted();
            }
        }
        batches.stream().filter(batch -> batch.failure != null).forEach(batch -> failures.add(batch.provider + ":" + batch.failure));
        boolean limited = batches.stream().anyMatch(Batch::pageLimit);
        List<Found> found = batches.stream().flatMap(batch -> batch.found.stream()).toList();
        int rawLimit = Math.max(1, Math.min(200, properties.getMaxRawResults()));
        int fetched = found.size();
        if (found.size() > rawLimit) { found = found.subList(0, rawLimit); failures.add("QUERY_BUDGET_EXHAUSTED:raw limit"); }
        Map<String, Integer> filtered = new LinkedHashMap<>();
        Map<String, List<Found>> byUrl = new LinkedHashMap<>();
        for (var item : found) {
            try { byUrl.computeIfAbsent(SourceResolver.normalize(item.raw.url()).toString(), k -> new ArrayList<>()).add(item); }
            catch (RuntimeException invalid) { count(filtered, "INVALID_URL"); }
        }
        int afterSource = 0, afterRelevance = 0, afterDate = 0, draftsCount = 0, redirects = 0;
        Map<String, Integer> acceptedBySource = new HashMap<>(), rawBySource = new HashMap<>();
        List<Draft> drafts = new ArrayList<>();
        Map<String, List<Found>> resolvedPages = new LinkedHashMap<>();
        Map<String, OfficialSource> pageSources = new HashMap<>();
        for (var entry : byUrl.entrySet()) {
            boolean allowRedirect = redirects < 8 && System.currentTimeMillis() + 3000 < deadline;
            var resolution = resolveSource(entry.getKey(), sources, allowRedirect, deadline);
            if (resolution.source() == null) { count(filtered, resolution.reason(), entry.getValue().size()); if (allowRedirect) redirects++; continue; }
            var source = resolution.source();
            afterSource += entry.getValue().size();
            rawBySource.merge(source.domain(), entry.getValue().size(), Integer::sum);
            resolvedPages.computeIfAbsent(resolution.url(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            pageSources.put(resolution.url(), source);
        }
        DetailPages details = readDetails(query, resolvedPages, deadline, discovery && properties.isReadDetails());
        for (var entry : resolvedPages.entrySet()) {
            var source = pageSources.get(entry.getKey());
            // Choose the richest excerpt for duplicate URLs, while retaining every matching query.
            Found chosen = entry.getValue().stream().max(Comparator.comparingInt(f -> Objects.toString(f.raw.excerpt(), "").length())).orElseThrow();
            var raw = new WebSearchClient.RawResult(chosen.raw.title(), entry.getKey(), details.text().getOrDefault(entry.getKey(), chosen.raw.excerpt()), chosen.raw.publishTime(), chosen.raw.siteName());
            String text = Objects.toString(raw.title(), "") + " " + Objects.toString(raw.excerpt(), "");
            String slug;
            try { slug = URLDecoder.decode(raw.url(), StandardCharsets.UTF_8); } catch (RuntimeException ignored) { slug = ""; }
            if (!text.contains(query.artist()) && !slug.contains(query.artist())) { count(filtered, "NOT_ABOUT_ARTIST", entry.getValue().size()); continue; }
            if (List.of("演唱会", "巡演", "演出", "音乐会", "音乐节", "拼盘", "见面会", "开票", "门票", "场馆").stream().noneMatch(text::contains)
                    || ("GOV".equals(source.tier()) && List.of("许可", "批准", "批复", "准予").stream().noneMatch(text::contains))) {
                count(filtered, "NO_EVENT_MARKER", entry.getValue().size()); continue;
            }
            afterRelevance += entry.getValue().size();
            List<Draft> extracted;
            try { extracted = extractor.extract(raw, source, query, entry.getValue().stream().map(Found::query).distinct().toList()); }
            catch (RuntimeException error) { count(filtered, "PARSER_REJECTED"); continue; }
            draftsCount += extracted.size();
            int accepted = 0;
            for (var draft : extracted) {
                var item = draft.item();
                if (query.city() != null && !query.city().equals(item.city())) { count(filtered, "CITY_MISMATCH"); continue; }
                if (item.eventDate() != null && (item.eventDate().isBefore(query.fromDate()) || item.eventDate().isAfter(query.toDate()))) { count(filtered, "DATE_OUT_OF_WINDOW"); continue; }
                drafts.add(draft); accepted++;
            }
            if (accepted > 0) { afterDate += entry.getValue().size(); acceptedBySource.merge(source.domain(), accepted, Integer::sum); }
            count(filtered, "DUPLICATE_URL", entry.getValue().size() - 1);
        }
        List<Draft> merged = merger.diverseOrder(merger.merge(drafts));
        count(filtered, "DUPLICATE_EVENT", drafts.size() - merged.size());
        List<Map<String, Object>> sourceCoverage = new ArrayList<>();
        for (var source : sources) {
            boolean failed = batches.stream().anyMatch(b -> Objects.equals(b.query.site(), source.domain()) && b.failure != null);
            boolean queried = batches.stream().anyMatch(b -> Objects.equals(b.query.site(), source.domain()));
            sourceCoverage.add(Map.of("platform", source.platformName(), "domain", source.domain(), "status", failed ? "QUERY_FAILED" : rawBySource.containsKey(source.domain()) ? "OK" : queried ? "QUERY_COMPLETED_NO_MATCH" : "NOT_OBSERVED",
                    "raw", rawBySource.getOrDefault(source.domain(), 0), "accepted", acceptedBySource.getOrDefault(source.domain(), 0), "failure", failed ? "PROVIDER_QUERY_FAILED" : ""));
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("queries", submitted == 0 ? List.of() : plans.stream().map(EventQueryPlanner.PlannedQuery::query).toList());
        coverage.put("detailAttempts", details.attempts());
        coverage.put("detailRead", details.text().size());
        coverage.put("detailUnavailable", details.attempts()-details.text().size());
        coverage.put("rawResults", found.size()); coverage.put("fetchedResults", fetched); coverage.put("afterSource", afterSource);
        coverage.put("afterRelevance", afterRelevance); coverage.put("afterDate", afterDate); coverage.put("drafts", draftsCount);
        coverage.put("merged", merged.size()); coverage.put("filtered", filtered); coverage.put("sources", sourceCoverage);
        coverage.put("failures", failures); coverage.put("providerPageLimit", limited);
        coverage.put("filterUnits", Map.of("source/relevance/DUPLICATE_URL", "pages", "city/date/parser/DUPLICATE_EVENT", "drafts"));
        coverage.put("window", query.fromDate() + " 至 " + query.toDate());
        coverage.put("unknownDates", merged.stream().filter(Draft::dateUnknown).count());
        coverage.put("completeness", sources.isEmpty() || providers.isEmpty() ? "UNKNOWN"
                : limited || !failures.isEmpty() || filtered.containsKey("PARSER_REJECTED") || filtered.containsKey("REDIRECT_UNRESOLVED") || merged.stream().anyMatch(d -> d.dateUnknown() || d.cityUnknown()) ? "PARTIAL" : "BEST_EFFORT");
        log.info("event_search metrics queryHash={} queries={} raw={} filtered={} merged={} failures={} latencyMs={}",
                Integer.toHexString(query.key().hashCode()), submitted, found.size(), filtered, merged.size(), failures.size(), System.currentTimeMillis() - started);
        return new Snapshot(UUID.randomUUID().toString(), key, System.currentTimeMillis() + 300_000, merged, coverage);
    }
    private record Phase(List<Batch> batches, int submitted, List<String> failures) { }
    private Phase runPhase(List<EventQueryPlanner.PlannedQuery> plans, List<OfficialSource> sources,
                           long deadline, java.util.concurrent.atomic.AtomicInteger budget) {
        List<Future<Batch>> pending = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Queue<Batch> pages = new ConcurrentLinkedQueue<>();
        CompletionService<Batch> completion = new ExecutorCompletionService<>(workers);
        if (!sources.isEmpty() && System.currentTimeMillis() < deadline) for (var plan : plans) for (var provider : providers) {
            try { pending.add(completion.submit(() -> fetch(provider, plan, deadline, budget, pages))); }
            catch (RejectedExecutionException busy) { failures.add(provider.providerName()+":PROVIDER_RATE_LIMITED"); }
        }
        int finished=0;
        try {
            while (finished < pending.size()) {
                Future<Batch> done=completion.poll(Math.max(1, deadline-System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (done==null) break;
                batches.add(done.get()); finished++;
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); failures.add("SEARCH_INTERRUPTED"); }
        catch (ExecutionException error) { failures.add("PROVIDER_QUERY_FAILED"); }
        if (finished < pending.size()) failures.add("QUERY_BUDGET_EXHAUSTED:"+(pending.size()-finished)+" unfinished queries");
        pending.forEach(task -> { if (!task.isDone()) task.cancel(true); });
        batches.addAll(pages);
        return new Phase(batches,pending.size(),failures);
    }

    private record DetailPages(Map<String,String> text,int attempts) { }
    private DetailPages readDetails(EventSearchQuery query, Map<String,List<Found>> pages,long deadline,boolean enabled) {
        if (!enabled || System.currentTimeMillis() >= deadline) return new DetailPages(Map.of(),0);
        Map<String,Future<Optional<String>>> pending=new LinkedHashMap<>();
        EventPageReader reader=new EventPageReader(properties);
        long detailDeadline=Math.min(deadline,System.currentTimeMillis()+Math.max(1,properties.getDetailBudgetMillis()));
        for (var entry:pages.entrySet()) {
            if (pending.size()>=Math.max(0,Math.min(6,properties.getMaxDetailPages()))) break;
            Found chosen=entry.getValue().stream().max(Comparator.comparingInt(f -> Objects.toString(f.raw.excerpt(), "").length())).orElseThrow();
            String title=Objects.toString(chosen.raw.title(), "");
            if (!title.contains(query.artist())) continue;
            var cities=cityDictionary.find(title).stream().filter(hit -> !hit.unknown()).map(CityDictionary.Hit::city).distinct().toList();
            String city=cities.size()==1?cities.get(0):null;
            try { pending.put(entry.getKey(),redirectWorkers.submit(() -> reader.read(entry.getKey(),query.artist(),city,Math.max(1,detailDeadline-System.currentTimeMillis())))); }
            catch (RejectedExecutionException busy) { break; }
        }
        Map<String,String> text=new LinkedHashMap<>();
        for (var entry:pending.entrySet()) try {
            entry.getValue().get(Math.max(1,detailDeadline-System.currentTimeMillis()),TimeUnit.MILLISECONDS).ifPresent(value -> text.put(entry.getKey(),value));
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
        catch (ExecutionException|TimeoutException ignored) { /* Preserve the search excerpt. */ }
        finally { if (!entry.getValue().isDone()) entry.getValue().cancel(true); }
        pending.values().forEach(task -> { if (!task.isDone()) task.cancel(true); });
        return new DetailPages(text,pending.size());
    }

    private Batch fetch(WebSearchClient provider, EventQueryPlanner.PlannedQuery plan, long deadline, java.util.concurrent.atomic.AtomicInteger budget, Queue<Batch> completedPages) {
        List<Found> found = List.of(); boolean limited = false;
        try {
            for (int page = 0; page < Math.max(1, Math.min(2, properties.getMaxPagesPerQuery())); page++) {
                if (System.currentTimeMillis() >= deadline) return new Batch(provider.providerName(), plan, found, true, "QUERY_BUDGET_EXHAUSTED");
                int pageSize = Math.max(1, Math.min(20, properties.getMaxPageSize()));
                if (budget.getAndAdd(-pageSize) < pageSize) return new Batch(provider.providerName(), plan, found, true, "QUERY_BUDGET_EXHAUSTED");
                var result = runner.run(provider, new SearchProvider.SearchRequest(plan.query(), plan.site(), page, pageSize, null));
                // Publish each successful page before requesting the next one, so a later timeout cannot erase it.
                var pageResults = result.results().stream().map(raw -> new Found(raw, plan.query())).toList();
                completedPages.add(new Batch(provider.providerName(), plan, pageResults, result.providerPageLimit(), null));
                limited |= result.providerPageLimit();
                if (!result.hasMore()) return new Batch(provider.providerName(), plan, found, limited, null);
                if (page + 1 >= Math.max(1, Math.min(2, properties.getMaxPagesPerQuery()))) limited = true;
            }
            return new Batch(provider.providerName(), plan, found, limited, null);
        } catch (RuntimeException error) {
            String reason = Objects.toString(error.getMessage(), "");
            return new Batch(provider.providerName(), plan, found, limited, reason.contains("RATE_LIMIT") || reason.contains("429") ? "PROVIDER_RATE_LIMITED" : "PROVIDER_QUERY_FAILED");
        }
    }
    private Map<String, Object> page(EventSearchQuery query, Snapshot snapshot, int offset) {
        int size = Math.max(1, Math.min(query.pageSize(), Math.min(50, properties.getMaxItems())));
        int end = Math.min(snapshot.items.size(), offset + size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Draft draft : snapshot.items.subList(offset, end)) {
            Map<String, Object> item = mapper.convertValue(draft.item(), new TypeReference<LinkedHashMap<String, Object>>() {});
            item.put("dateUnknown", draft.dateUnknown()); item.put("cityUnknown", draft.cityUnknown()); item.put("noise", draft.noise());
            item.put("dateCandidates", draft.dateCandidates()); item.put("dateExpression", draft.dateExpression()); item.put("queries", draft.queries());
            items.add(item);
        }
        Map<String, Object> coverage = new LinkedHashMap<>(snapshot.coverage);
        int remaining = snapshot.items.size() - end;
        coverage.put("returned", items.size()); coverage.put("truncated", remaining); coverage.put("offset", offset);
        if (remaining > 0 && "BEST_EFFORT".equals(coverage.get("completeness"))) coverage.put("completeness", "PARTIAL");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artist", query.artist()); response.put("city", query.city()); response.put("window", query.window()); response.put("includeEnded", query.includeEnded());
        response.put("pageSize", query.pageSize()); response.put("total", items.size()); response.put("items", items); response.put("coverage", coverage);
        response.put("notes", CoverageReporter.notes(coverage, ((Number) coverage.get("unknownDates")).intValue(), (int) snapshot.items.stream().filter(d -> d.item().isStale()).count(),
                (int) snapshot.items.stream().filter(d -> d.item().conflict()).count(), remaining));
        response.put("nextCursor", remaining == 0 ? null : Base64.getUrlEncoder().withoutPadding().encodeToString((snapshot.token + ":" + end).getBytes(StandardCharsets.UTF_8)));
        return response;
    }
    private Map<String, Object> unavailable(EventSearchQuery query, String reason) {
        var snapshot = discover(query, List.of(), query.key());
        ((List<String>) snapshot.coverage.get("failures")).add(reason);
        return page(query, snapshot, 0);
    }
    private SourceResolver.Resolution resolveSource(String url, List<OfficialSource> sources, boolean allowNetwork, long deadline) {
        var direct = resolver.resolve(url, sources, false);
        if (direct.source() != null || !allowNetwork || "INVALID_URL".equals(direct.reason())) return direct;
        Future<SourceResolver.Resolution> task = null;
        try {
            // DNS cannot reliably be interrupted by the JDK; isolate it behind a bounded queue and caller deadline.
            task = redirectWorkers.submit(() -> resolver.resolve(url, sources, true));
            return task.get(Math.max(1, Math.min(1500, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            if (task != null) task.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new SourceResolver.Resolution(null, null, "REDIRECT_UNRESOLVED");
        }
    }
    private static void count(Map<String, Integer> counts, String reason) { count(counts, reason, 1); }
    private static void count(Map<String, Integer> counts, String reason, int count) { if (count > 0) counts.merge(reason, count, Integer::sum); }
    @PreDestroy public void close() { workers.shutdownNow(); redirectWorkers.shutdownNow(); }
}
