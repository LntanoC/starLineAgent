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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.EventPageExtractor.Draft;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-writer durable index. Reads never wait for public sites; source policy is checked on every read and refresh. */
@Component
public class EventCrawlIndex implements AutoCloseable {
    public record State(String sourceId, String status, List<CrawlRecord> records, List<String> warnings, long checkedAt, long lastSuccessAt) {}
    public record Stored(int version, Map<String,List<State>> artists) {}
    public record View(List<Draft> drafts, List<Map<String,Object>> sources, Set<String> handledUrls) {}
    private static final Map<String,String> DOMAINS = Map.of("beijing","banshi.whlyj.beijing.gov.cn","guangzhou","wglj.gz.gov.cn","damai","search.damai.cn");
    private final EventCrawlProperties props;
    private final OfficialSourceRegistryClient registry;
    private final EventSourceCrawler crawler;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final LinkedHashMap<String,List<State>> artists=new LinkedHashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"event-crawl"); t.setDaemon(true); return t; });
    private long revision;
    private String storageStatus="OK";
    private boolean started;
    @Autowired public EventCrawlIndex(EventCrawlProperties props,OfficialSourceRegistryClient registry) { this(props,registry,new CrawlHttp()::get); }
    public EventCrawlIndex(EventCrawlProperties props,OfficialSourceRegistryClient registry,CrawlHttp.Fetch fetch) {
        this.props=props; this.registry=registry; this.crawler=new EventSourceCrawler(fetch,props);
        if(props.isEnabled()) {
            load();
            for(String artist:props.getArtists()) watch(artist);
        }
    }
    @EventListener(ApplicationReadyEvent.class) public synchronized void start() {
        if(props.isEnabled() && !started) { started=true; worker.scheduleWithFixedDelay(this::tick,5,60,TimeUnit.SECONDS); }
    }
    private static OfficialSource source(String id,List<OfficialSource> sources) {
        String host=DOMAINS.get(id);
        var policy=new SourceResolver().resolve("https://"+host+"/",sources,false).source();
        return policy!=null && (id.equals("damai")?"TICKETING":"GOV").equals(policy.tier())?policy:null;
    }
    public synchronized long revision() { return revision; }
    private synchronized void watch(String artist) {
        if(artist==null || artist.isBlank() || artist.length()>80 || artists.containsKey(artist) || artists.size()>=Math.max(1,Math.min(128,props.getMaxArtists()))) return;
        artists.put(artist,List.of()); revision++; save();
    }
    /** One artist per tick bounds scheduler work; each source has an independent six-hour retry cooldown. */
    public void tick() {
        if(!props.isEnabled()) return;
        try {
            List<OfficialSource> sources=registry.listEnabledSources();
            String target=null;
            synchronized(this) {
                long now=System.currentTimeMillis();
                for(var entry:artists.entrySet()) if(List.of("beijing","guangzhou","damai").stream().anyMatch(id -> enabled(id) && source(id,sources)!=null
                        && entry.getValue().stream().noneMatch(s -> s.sourceId().equals(id) && now-s.checkedAt()<Math.max(60_000,props.getRefreshMillis())))) { target=entry.getKey(); break; }
            }
            if(target==null) return;
            for(String id:List.of("beijing","guangzhou","damai")) {
                if(!enabled(id) || source(id,sources)==null) continue;
                synchronized(this) {
                    long now=System.currentTimeMillis();
                    if(artists.get(target).stream().anyMatch(s -> s.sourceId().equals(id) && now-s.checkedAt()<Math.max(60_000,props.getRefreshMillis()))) continue;
                }
                // Re-read policy immediately before a source's network work.
                if(source(id,registry.listEnabledSources())==null) continue;
                update(target,crawler.crawl(id,target));
            }
        } catch(Exception ignored) { /* Preserve prior data; querying still rechecks live source policy. */ }
    }
    private boolean enabled(String id) { return !id.equals("damai") || props.isDamaiEnabled(); }
    public synchronized void update(String artist,EventSourceCrawler.Batch batch) {
        if(!artists.containsKey(artist)) watch(artist);
        if(!artists.containsKey(artist)) return;
        var states=new ArrayList<>(artists.get(artist));
        var prior=states.stream().filter(s -> s.sourceId().equals(batch.sourceId())).findFirst().orElse(null);
        Map<String,CrawlRecord> records=new LinkedHashMap<>();
        // Scans are bounded: absence on a scanned page never proves cancellation or deletion.
        if(prior!=null) prior.records().forEach(r -> records.put(r.key(),r));
        batch.records().stream().filter(r -> r.artist().equals(artist)).forEach(r -> records.put(r.key(),r));
        var retained=records.values().stream().filter(r -> r.approvedTo()==null || !r.approvedTo().isBefore(EventSearchQuery.today().minusDays(60)))
                .sorted(Comparator.comparingLong(CrawlRecord::fetchedAt).reversed()).limit(500).toList();
        long success=!batch.records().isEmpty() || "OK".equals(batch.status()) ? batch.checkedAt() : prior==null?0:prior.lastSuccessAt();
        states.removeIf(s -> s.sourceId().equals(batch.sourceId()));
        states.add(new State(batch.sourceId(),batch.status(),retained,batch.warnings(),batch.checkedAt(),success));
        artists.put(artist,List.copyOf(states)); revision++; save();
    }
    public synchronized View read(EventSearchQuery query,List<OfficialSource> sources) {
        if(!props.isEnabled()) return new View(List.of(),List.of(),Set.of());
        watch(query.artist());
        List<Draft> drafts=new ArrayList<>(); List<Map<String,Object>> coverage=new ArrayList<>(); Set<String> handled=new HashSet<>();
        var states=artists.getOrDefault(query.artist(),List.of());
        for(String id:List.of("beijing","guangzhou","damai")) {
            OfficialSource policy=source(id,sources);
            State state=states.stream().filter(s -> s.sourceId().equals(id)).findFirst().orElse(null);
            boolean active=enabled(id) && policy!=null;
            String status=!enabled(id)?"NOT_ENABLED":policy==null?"NOT_WHITELISTED":state==null?(artists.containsKey(query.artist())?"INITIALIZING":"WATCH_LIMIT"):state.status();
            Map<String,Object> info=new LinkedHashMap<>();
            info.put("source",id); info.put("domain",DOMAINS.get(id)); info.put("status",status);
            info.put("checkedAt",state==null?0:state.checkedAt()); info.put("lastSuccessAt",state==null?0:state.lastSuccessAt());
            info.put("records",active && state!=null?state.records().size():0); info.put("warnings",state==null?List.of():state.warnings());
            info.put("storageStatus",storageStatus); coverage.add(info);
            if(!active || state==null || !"GOV".equals(policy.tier())) continue;
            for(CrawlRecord r:state.records()) {
                if(!trusted(r,sources)) continue;
                handled.add(SourceResolver.normalize(r.sourceUrl()).toString());
                if(!r.artist().equals(query.artist()) || (query.city()!=null && !query.city().equals(r.city()))) continue;
                if(r.approvedTo()!=null && r.approvedTo().isBefore(query.fromDate()) || r.approvedFrom()!=null && r.approvedFrom().isAfter(query.toDate())) continue;
                LocalDate date=Objects.equals(r.approvedFrom(),r.approvedTo())?r.approvedFrom():null;
                boolean stale=System.currentTimeMillis()-r.fetchedAt()>Math.max(60_000,props.getStaleMillis());
                boolean changed=r.changeType()!=null && (r.changeType().contains("取消") || r.changeType().contains("撤销"));
                String range=r.approvedFrom()==null?"许可日期待核实":r.approvedFrom()+" 至 "+r.approvedTo();
                String note="许可编号："+r.approvalNo()+"；许可演出范围："+range+"；"+r.changeType()+(date==null?"；具体场次日期待核实":"")+"；审批不代表正在售票";
                var item=new ArtistEventSearchMcpExecutor.EventItem(r.artist(),r.title(),changed?"CANCELLED":"APPROVED","GOV",r.city(),date,null,null,false,
                        policy.platformName(),r.sourceUrl(),note,r.attachmentUrl()==null?r.sourceUrl():r.attachmentUrl(),stale,changed,
                        List.of(new ArtistEventSearchMcpExecutor.SourceEvidence(policy.platformName(),"GOV",r.sourceUrl(),changed?"CANCELLED":"APPROVED")),
                        r.venue(),null,null,null,null,r.sourceUrl(),null);
                drafts.add(new Draft(item,date==null,r.city()==null,false,List.of(),"许可范围："+range,List.of("direct-crawl:"+id)));
            }
        }
        return new View(List.copyOf(drafts),List.copyOf(coverage),Set.copyOf(handled));
    }
    private static boolean trusted(CrawlRecord r,List<OfficialSource> sources) {
        try {
            CrawlHttp.validate(r.sourceUrl());
            if(!Objects.equals(DOMAINS.get(r.sourceId()),java.net.URI.create(r.sourceUrl()).getHost())) return false;
            if(r.attachmentUrl()!=null && !Objects.equals(java.net.URI.create(r.sourceUrl()).getHost(),CrawlHttp.validate(r.attachmentUrl()).getHost())) return false;
            var resolved=new SourceResolver().resolve(r.sourceUrl(),sources,false);
            return resolved.source()!=null && "GOV".equals(resolved.source().tier());
        } catch(Exception e) { return false; }
    }
    public synchronized Map<String,Object> status() {
        return Map.of("enabled",props.isEnabled(),"storageStatus",storageStatus,"revision",revision,"watchCount",artists.size(),
                "sources",artists.values().stream().flatMap(Collection::stream).map(s -> Map.of("source",s.sourceId(),"status",s.status(),
                        "records",s.records().size(),"checkedAt",s.checkedAt(),"warnings",s.warnings())).toList());
    }
    private void load() {
        Path file=Path.of(props.getDirectory(),"index.json");
        if(!Files.exists(file)) return;
        try {
            if(Files.size(file)>8_000_000) throw new IllegalStateException();
            Stored stored=mapper.readValue(file.toFile(),Stored.class);
            if(stored.version()!=1 || stored.artists()==null || stored.artists().size()>128) throw new IllegalStateException();
            for(var e:stored.artists().entrySet()) {
                if(e.getKey()==null || e.getKey().isBlank() || e.getKey().length()>80 || e.getValue()==null || e.getValue().size()>3) throw new IllegalStateException();
                for(State s:e.getValue()) if(s==null || !DOMAINS.containsKey(s.sourceId()) || s.records()==null || s.records().size()>500 || s.warnings()==null) throw new IllegalStateException();
            }
            stored.artists().entrySet().stream().limit(Math.max(1,Math.min(128,props.getMaxArtists()))).forEach(e -> artists.put(e.getKey(),List.copyOf(e.getValue())));
        } catch(Exception e) { storageStatus="STORE_READ_FAILED"; }
    }
    private void save() {
        if(!props.isEnabled() || "STORE_READ_FAILED".equals(storageStatus)) return;
        Path directory=Path.of(props.getDirectory()); Path temp=null;
        try {
            Files.createDirectories(directory); temp=Files.createTempFile(directory,"index-",".tmp");
            byte[] bytes=mapper.writeValueAsBytes(new Stored(1,artists));
            if(bytes.length>8_000_000) throw new IllegalStateException("INDEX_SIZE_LIMIT");
            Files.write(temp,bytes);
            try { Files.move(temp,directory.resolve("index.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,directory.resolve("index.json"),StandardCopyOption.REPLACE_EXISTING); }
            storageStatus="OK";
        } catch(Exception e) { storageStatus="STORE_WRITE_FAILED"; }
        finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(Exception ignored) {} }
    }
    @Override @PreDestroy public void close() { worker.shutdownNow(); }
}
