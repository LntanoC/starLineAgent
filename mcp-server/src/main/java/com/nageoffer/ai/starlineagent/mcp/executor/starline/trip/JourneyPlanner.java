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

import com.nageoffer.ai.starlineagent.mcp.config.AmapApiKeyConfiguredCondition;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

@Slf4j
@Service
@Conditional(AmapApiKeyConfiguredCondition.class)
public class JourneyPlanner {
    private final RouteGateway gateway;
    private final TripPlanProperties properties;
    private final Clock clock;
    private final JourneyTimeCalculator time = new JourneyTimeCalculator();
    private final ExecutorService workers = new ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            runnable -> { Thread thread = new Thread(runnable, "journey-provider"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    @Autowired public JourneyPlanner(RouteGateway gateway, TripPlanProperties properties) {
        this(gateway, properties, Clock.system(ZoneId.of("Asia/Shanghai")));
    }
    JourneyPlanner(RouteGateway gateway, TripPlanProperties properties, Clock clock) {
        this.gateway = gateway; this.properties = properties; this.clock = clock;
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
    private record Bundle(String mode, List<RouteCandidate> outbound, List<RouteCandidate> returns, List<Warning> warnings) {}

    public JourneyPlan plan(JourneyRequest request, EventAnchor event) {
        long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(8);
        List<Warning> warnings = new ArrayList<>();
        if (request.origin() == null) return clarify(request, event, "origin", "你从哪里出发？", List.of("例如：南京市鼓楼区某地址", "例如：上海虹桥站"));
        if (request.destination() == null && event == null) return clarify(request, null, "destination", "要去哪里？", List.of("城市名", "具体场馆或地址"));
        LocalDateTime arriveBy = event == null ? request.arriveBy() : event.showStart().minusMinutes(request.arrivalBufferMinutes());
        LocalDateTime departAt = event == null ? request.departAt() : null;
        if (arriveBy == null && departAt == null) return clarify(request, event, "departAt", "何时出发，或最晚何时到达？", List.of("例如：2026-11-21T09:00:00 出发", "例如：2026-11-21T18:30:00 前到达"));
        if (arriveBy != null && departAt != null) throw new IllegalArgumentException("arriveBy 与 departAt 不能同时提供");
        String destination = event == null ? request.destination() : event.venue().address();
        Place from, to;
        String originName = request.origin();
        Future<Place> fromTask = null, toTask = null;
        try {
            fromTask = workers.submit(() -> gateway.resolve(originName, null, false));
            toTask = workers.submit(() -> event != null && event.venue().point() != null ? event.venue()
                    : gateway.resolve(destination, event == null ? null : event.city(), event != null));
            from = fromTask.get(remaining(deadline), TimeUnit.NANOSECONDS);
            to = toTask.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            if (fromTask != null) fromTask.cancel(true);
            if (toTask != null) toTask.cancel(true);
            Throwable cause = root(failure);
            if (cause instanceof JourneyProviderException provider && provider.code().startsWith("GEOCODE"))
                return clarify(request, event, "place", provider.getMessage(), List.of("补充含城市的具体地址"));
            return result(PlanStatus.FAILED, request, event, List.of(), null, null, List.of(), List.of(warning(cause)));
        }
        boolean crossCity = !sameCity(from, to);
        boolean roundTrip = request.roundTrip() == null ? event != null && crossCity : request.roundTrip();
        request = resolvedRequest(request, destination, roundTrip);
        final JourneyRequest resolved = request;
        LocalDateTime returnAt = request.returnDepartAt() != null ? request.returnDepartAt()
                : event != null && event.showEnd() != null ? event.showEnd().plusMinutes(properties.getCrowdBufferMinutes()) : null;
        if (returnAt != null && event != null && returnAt.isBefore(event.showStart()))
            throw new IllegalArgumentException("返程时间不能早于开场时间");
        Set<String> modes = new LinkedHashSet<>();
        if (request.modes() == null || request.modes().isEmpty()) modes.addAll(crossCity ? List.of("transit", "driving") : List.of("transit", "taxi", "driving", "walk"));
        else for (String mode : request.modes()) {
            if (Set.of("flight", "coach").contains(mode)) warnings.add(new Warning("MODE_UNAVAILABLE", mode + " 时刻与价格尚未接入，费用待核实"));
            else modes.add("rail".equals(mode) ? "transit" : mode);
        }
        LocalDateTime queryAt = departAt == null ? arriveBy : departAt;
        CompletionService<Bundle> completion = new ExecutorCompletionService<>(workers);
        List<Future<Bundle>> pending = new ArrayList<>();
        for (String mode : modes) {
            try { pending.add(completion.submit(() -> fetch(from, to, queryAt, mode, arriveBy, roundTrip, returnAt))); }
            catch (RejectedExecutionException e) { warnings.add(new Warning("AMAP_QUOTA", "查询任务繁忙，请稍后重试")); }
        }
        List<JourneyOption> options = new ArrayList<>();
        try {
            for (int i = 0; i < pending.size(); i++) {
                Future<Bundle> finished = completion.poll(remaining(deadline), TimeUnit.NANOSECONDS);
                if (finished == null) { warnings.add(new Warning("AMAP_TIMEOUT", "部分交通方式未在查询时限内返回")); break; }
                Bundle bundle = finished.get();
                warnings.addAll(bundle.warnings());
                for (RouteCandidate candidate : bundle.outbound()) {
                    if (resolved.modes() != null && resolved.modes().equals(List.of("rail")) && candidate.segments().stream().noneMatch(s -> "rail".equals(s.mode()))) continue;
                    options.add(option(candidate, bundle.returns(), resolved, event, from, to, departAt, arriveBy, roundTrip, returnAt));
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); warnings.add(new Warning("AMAP_TIMEOUT", "规划已取消")); }
        catch (Exception e) { warnings.add(warning(root(e))); }
        finally { pending.forEach(f -> { if (!f.isDone()) f.cancel(true); }); }
        int fastest = options.stream().filter(JourneyPlanner::usable).mapToInt(JourneyOption::travelMinutes).min().orElse(Integer.MAX_VALUE - 10);
        Long budget = request.budgetMinor();
        options.sort(Comparator.comparingInt((JourneyOption o) -> o.feasibility() == Feasibility.INFEASIBLE ? 3 : usable(o) ? 0 : 2)
                .thenComparingInt(o -> budget != null && withinBudget(o, budget) ? 0 : 1)
                .thenComparingInt(o -> o.travelMinutes() <= fastest + 10 ? 0 : 1)
                .thenComparingInt(o -> o.travelMinutes() <= fastest + 10 ? o.transfers() : o.travelMinutes())
                .thenComparingInt(o -> o.cost().unknownCategories().size()).thenComparingInt(JourneyOption::travelMinutes));
        if (options.size() > 3) options = new ArrayList<>(options.subList(0, 3));
        JourneyOption recommended = options.stream().filter(JourneyPlanner::usable).findFirst().orElse(null);
        boolean partial = !warnings.isEmpty() || options.stream().anyMatch(o -> !o.warnings().isEmpty() || !o.cost().unknownCategories().isEmpty() || o.feasibility() == Feasibility.UNKNOWN);
        JourneyPlan plan = result(options.isEmpty() ? PlanStatus.FAILED : partial ? PlanStatus.PARTIAL : PlanStatus.OK,
                request, event, List.copyOf(options), recommended == null ? null : recommended.id(),
                recommended == null ? null : budget != null && withinBudget(recommended, budget) ? "WITHIN_BUDGET"
                        : recommended.travelMinutes() > fastest ? "FEWEST_TRANSFERS" : "FASTEST", List.of(), warnings);
        log.info("journey plan status={} options={} unknownItems={} latencyMs={}", plan.status(), options.size(),
                options.stream().mapToInt(o -> o.cost().unknownCategories().size()).sum(), (System.nanoTime() - started) / 1000000);
        return plan;
    }

    private Bundle fetch(Place from, Place to, LocalDateTime at, String mode, LocalDateTime arriveBy, boolean roundTrip, LocalDateTime returnAt) {
        List<Warning> warnings = new ArrayList<>();
        List<RouteCandidate> outbound;
        try {
            outbound = gateway.routes(from, to, at, mode);
            if (arriveBy != null && "transit".equals(mode) && !outbound.isEmpty()) {
                int duration = outbound.stream().mapToInt(c -> c.segments().stream().mapToInt(Segment::durationMinutes).sum()).max().orElse(0);
                long rails = outbound.stream().mapToLong(c -> c.segments().stream().filter(s -> "rail".equals(s.mode())).count()).max().orElse(0);
                // 先用路线耗时定位查询窗口，再查真实班次；倒推值不能直接冒充发车时刻。
                try { outbound = gateway.routes(from, to, arriveBy.minusMinutes(duration + rails * properties.getRailStationBufferMinutes()), mode); }
                catch (RuntimeException e) { warnings.add(warning(e)); }
            }
            if (outbound.isEmpty()) warnings.add(new Warning("AMAP_NO_ROUTE", mode + " 未返回可用路线"));
        } catch (RuntimeException e) { return new Bundle(mode, List.of(), List.of(), List.of(warning(e))); }
        List<RouteCandidate> returns = List.of();
        if (roundTrip && returnAt != null) {
            try { returns = gateway.routes(to, from, returnAt, mode); }
            catch (RuntimeException e) { warnings.add(new Warning("RETURN_ROUTE_UNAVAILABLE", "返程查询未完成：" + warning(e).message())); }
        }
        return new Bundle(mode, outbound, returns, warnings);
    }

    private JourneyOption option(RouteCandidate candidate, List<RouteCandidate> returns, JourneyRequest request,
                                  EventAnchor event, Place from, Place to, LocalDateTime departAt, LocalDateTime arriveBy,
                                  boolean roundTrip, LocalDateTime returnAt) {
        var timeline = time.calculate(candidate.segments(), departAt, arriveBy, properties.getRailStationBufferMinutes(), LocalDateTime.now(clock));
        List<CostItem> costs = new ArrayList<>(candidate.extraCosts());
        candidate.segments().stream().map(Segment::fare).filter(Objects::nonNull).forEach(costs::add);
        List<Warning> warnings = new ArrayList<>(candidate.warnings());
        warnings.addAll(timeline.warnings());
        List<String> assumptions = new ArrayList<>(List.of("按 " + request.partySize() + " 人计；金额为人民币，交通费用来自路径服务参考值或明确标注的公式"));
        boolean capacityUnknown = candidate.segments().stream().anyMatch(s -> "driving".equals(s.mode()) && request.partySize() > 5
                || "taxi".equals(s.mode()) && request.partySize() > 4);
        if (capacityUnknown) {
            warnings.add(new Warning("VEHICLE_CAPACITY_UNKNOWN", "同行人数超出本次普通单车共乘假设，车型和车辆数量须确认"));
            costs.add(unknown("additional_vehicles", "车辆数量与多车费用", CostScope.PER_PARTY, "未确认车型与车辆数量，不能保证一车足够"));
        }
        if (AmapPlaceClient.broad(from) || AmapPlaceClient.broad(to)) {
            assumptions.add("按城市/区县定位点规划，不是门到门路线；请提供具体地址提高准确性");
            warnings.add(new Warning("GEOCODE_BROAD", "地点仅精确到城市或区县"));
        }
        if (request.roundTrip() != null) assumptions.add(roundTrip ? "本方案包含返程；返程费用独立查询" : "本方案只计算去程，未计算返程");
        if (candidate.segments().stream().anyMatch(s -> "driving".equals(s.mode()))) {
            assumptions.add("自驾按同行共乘一辆车计，载客容量和未来路况需另行确认");
            costs.add(request.parkingMinor() == null ? unknown("parking", "停车费", CostScope.PER_VEHICLE, "停车价格未核实")
                    : new CostItem("parking", "停车费", request.parkingMinor(), request.parkingMinor(), "CNY", CostStatus.QUOTED, CostScope.PER_VEHICLE, "user", "用户提供"));
        }
        if (event != null) {
            PriceRange price = event.ticketPrice();
            costs.add(request.ticketPaidMinor() != null ? new CostItem("ticket", "门票实付", request.ticketPaidMinor(), request.ticketPaidMinor(), "CNY", CostStatus.QUOTED, CostScope.PER_PERSON, "user", "用户提供的人均实付")
                    : price == null || price.status() == CostStatus.UNKNOWN ? unknown("ticket", "演出门票", CostScope.PER_PERSON, "门票价格待核实")
                    : new CostItem("ticket", "演出票档区间", price.minMinor(), price.maxMinor(), price.currency(), price.status(), CostScope.PER_PERSON, price.source(), "参考票档，不代表实付或可售"));
        }
        ReturnJourney returnJourney;
        if (!roundTrip) {
            returnJourney = new ReturnJourney("NOT_REQUESTED", List.of(), null, null, Feasibility.UNKNOWN, List.of(new Warning("RETURN_NOT_CALCULATED", "未计算返程")));
        } else if (returnAt == null || returns.isEmpty()) {
            Warning warning = new Warning(returnAt == null ? "EVENT_END_UNKNOWN" : "RETURN_ROUTE_UNAVAILABLE", returnAt == null ? "散场/返程出发时间未知，返程与末班车尚未核实" : "未获取可用返程路线，末班车尚未核实");
            warnings.add(warning);
            costs.add(unknown("return_transport", "返程交通", CostScope.PER_PARTY, warning.message()));
            returnJourney = new ReturnJourney("UNKNOWN", List.of(), returnAt, null, Feasibility.UNKNOWN, List.of(warning));
        } else {
            List<JourneyTimeCalculator.Timeline> timelines = returns.stream().map(r -> time.calculate(r.segments(), returnAt, null, properties.getRailStationBufferMinutes(), LocalDateTime.now(clock))).toList();
            int best = 0;
            for (int i = 1; i < timelines.size(); i++) {
                if (timelines.get(best).feasibility() == Feasibility.INFEASIBLE && timelines.get(i).feasibility() != Feasibility.INFEASIBLE) best = i;
            }
            var back = timelines.get(best);
            RouteCandidate returnRoute = returns.get(best);
            List<CostItem> backCosts = new ArrayList<>(returnRoute.extraCosts());
            returnRoute.segments().stream().map(Segment::fare).filter(Objects::nonNull).forEach(backCosts::add);
            for (CostItem cost : backCosts) costs.add(new CostItem(cost.category(), "返程：" + cost.label().replace("（去程）", ""), cost.minMinor(), cost.maxMinor(), cost.currency(), cost.status(), cost.scope(), cost.source(), cost.note()));
            List<Warning> backWarnings = new ArrayList<>(returnRoute.warnings());
            backWarnings.addAll(back.warnings());
            if (back.feasibility() == Feasibility.UNKNOWN) backWarnings.add(new Warning("LAST_SERVICE_UNKNOWN", "返程末班车未完全核实，不保证当天可返回"));
            if (back.feasibility() == Feasibility.INFEASIBLE) backWarnings.add(new Warning("RETURN_INFEASIBLE", "赶不上返程服务；可另查打车、次日返程或住宿，住宿费用未计入"));
            warnings.addAll(backWarnings);
            returnJourney = new ReturnJourney("PLANNED", back.segments(), back.departure(), back.arrival(), back.feasibility(), backWarnings);
        }
        CostSummary summary = CostCalculator.summarize(costs, request.partySize());
        if (!summary.unknownCategories().isEmpty()) warnings.add(new Warning("PRICE_UNKNOWN", "费用只包含已知部分，另有 " + summary.unknownCategories().size() + " 类费用待核实"));
        if (request.budgetMinor() != null && summary.knownSubtotalMinor() > request.budgetMinor()) warnings.add(new Warning("OVER_BUDGET", "仅已知费用已超出同行合计预算"));
        Confidence confidence = capacityUnknown || timeline.feasibility() == Feasibility.UNKNOWN || from.confidence() < 0.8 || to.confidence() < 0.8 ? Confidence.LOW
                : warnings.isEmpty() && costs.stream().allMatch(c -> c.status() == CostStatus.QUOTED) ? Confidence.HIGH : Confidence.MEDIUM;
        int total = event == null ? timeline.travelMinutes() : Math.toIntExact(Duration.between(timeline.departure(), event.showStart()).toMinutes());
        return new JourneyOption(candidate.id(), candidate.label(), candidate.segments().stream().map(Segment::mode).distinct().toList(),
                timeline.segments(), timeline.travelMinutes(), total, timeline.departure(), timeline.arrival(), arriveBy,
                capacityUnknown && timeline.feasibility() != Feasibility.INFEASIBLE ? Feasibility.UNKNOWN : timeline.feasibility(), timeline.slackMinutes(), summary, confidence, candidate.transfers(), returnJourney,
                List.copyOf(assumptions), List.copyOf(warnings), event == null ? List.of() : event.evidence());
    }
    private static boolean usable(JourneyOption option) {
        return (option.feasibility() == Feasibility.FEASIBLE || option.feasibility() == Feasibility.TIGHT)
                && ("NOT_REQUESTED".equals(option.returnJourney().status()) || option.returnJourney().feasibility() == Feasibility.FEASIBLE || option.returnJourney().feasibility() == Feasibility.TIGHT);
    }
    private static boolean withinBudget(JourneyOption option, long budget) {
        return option.cost().unknownCategories().isEmpty() && option.cost().maxTotalMinor() != null && option.cost().maxTotalMinor() <= budget;
    }
    static boolean sameCity(Place a, Place b) {
        if (a.adcode() != null && b.adcode() != null && a.adcode().length() >= 2 && b.adcode().length() >= 2
                && Set.of("11", "12", "31", "50").contains(a.adcode().substring(0, 2)))
            return a.adcode().substring(0, 2).equals(b.adcode().substring(0, 2));
        if (a.adcode() != null && b.adcode() != null && a.adcode().length() >= 4 && b.adcode().length() >= 4)
            return a.adcode().substring(0, 4).equals(b.adcode().substring(0, 4));
        return a.city() != null && b.city() != null && a.city().replaceFirst("市$", "").equals(b.city().replaceFirst("市$", ""));
    }
    static JourneyRequest resolvedRequest(JourneyRequest r, String destination, boolean roundTrip) {
        return new JourneyRequest(r.artist(), r.city(), r.origin(), destination, r.eventDate(), r.venue(), r.showStartTime(), r.showEndTime(), r.departAt(), r.arriveBy(), r.returnDepartAt(), r.partySize(), roundTrip, r.modes(), r.budgetMinor(), r.ticketPaidMinor(), r.arrivalBufferMinutes(), r.parkingMinor());
    }
    static JourneyPlan clarify(JourneyRequest request, EventAnchor event, String field, String question, List<String> choices) {
        return result(PlanStatus.NEEDS_CLARIFICATION, request, event, List.of(), null, null, List.of(new Clarification(field, question, choices, true)), List.of());
    }
    static JourneyPlan result(PlanStatus status, JourneyRequest request, EventAnchor event, List<JourneyOption> options, String recommended, String reason, List<Clarification> questions, List<Warning> warnings) {
        return new JourneyPlan("trip-plan.v3", status, request, event, options, recommended, reason, questions, List.copyOf(warnings), Instant.now());
    }
    static Warning warning(Throwable error) {
        return error instanceof JourneyProviderException p ? new Warning(p.code(), p.getMessage()) : new Warning("AMAP_TIMEOUT", "部分路线查询暂时不可用");
    }
    private static long remaining(long deadline) { return Math.max(1, deadline - System.nanoTime()); }
    private static Throwable root(Throwable error) { return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error; }
}
