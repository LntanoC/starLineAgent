/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据逐代摘要、用户原文、确认卡记录和业务库写入计数执行固定剧本检查
 * 对应用户消息移出原始上下文后，才要求摘要保留其中的必要信息
 */
final class CompactionChecks {

    enum Status { PASS, WARN, FAIL, UNCOVERED }

    /**
     * hard 检查失败时记为 FAIL，其余检查失败时记为 WARN
     * core 检查存在未覆盖的会话或代数时，整体结果不能判为通过
     */
    enum Check {
        STRUCTURE("结构：六节齐全且有序", true, false),
        LENGTH("长度：每代落在区间内", true, true),
        STEADY("长度：稳态期相邻两代不涨", true, true),
        CONTEXT("上下文：压缩后回落", true, true),
        GENERATIONS("代数：压缩代数够下结论", true, true),
        READ_ONLY("只读不记：无价格、库存、知识库", true, false),
        TOOL_DETAIL("工具详情不抄：只留单号与型号", true, false),
        DEAD_END_NO_QUERY("走不通的路不收查询绕路", false, false),
        TODO_USER_ONLY("待办只收用户要求办的事", true, false),
        NO_FAKE_DONE("不虚构已办成", true, false),
        DONE_EMPTY("全拒卡剧本：已完成必须为空", true, false),
        ORDER_NO("订单：单号照抄", true, true),
        DIAGNOSIS("排查：试过的步骤带到下一代", true, false),
        HOLD_FIRST("暂缓：先别提交，到明说放行为止", true, true),
        HOLD_HYPOTHETICAL("暂缓：假设句不算放行", true, false),
        HOLD_THIRD("暂缓：放行后又说先别提交", true, true),
        HOLD_LATEST_QUOTE("暂缓：附的是最新那句原话", false, false),
        MAX_CLOSED("放弃：AirPods Max 不复活", true, true),
        IPAD_CONDITIONS("条件：iPad 预算与颜色照抄", true, true),
        IPAD_HOLD("暂缓：iPad 先别下单", true, true),
        IPAD_CLOSED("放弃：iPad 了结后不复活", true, true),
        A4_CLOSED("放弃：AirPods 4 了结后不复活", true, false),
        CLOSED_TRACE("放弃：了结的事在用户诉求里留痕", false, false),
        CLOSED_RESIDUE("放弃：了结的事在进度、绕路、已完成里不留残条", false, false),
        CLAIM_SOURCE("来源：用户声称的单号不当事实", true, false),
        RELEASE_CANCELLED("改口：放行被拒卡后记了取消", false, false),
        STALE_PLAN("改口：作废的计划不留在待办", false, false),
        ONE_NEXT_STEP("格式：下一步只写一条", false, false),
        THIRD_PERSON("格式：自述部分用第三人称", false, false),
        QUOTE_ONCE("格式：同一句原话只出现一次", false, false),
        QUOTE_SOURCE("显式用户引文：在已压缩的真实用户消息中有据", true, false),
        LINE_CAPS("格式：各节行数不超上限", false, false),
        NO_CARD_ON_HOLD("主 Agent：暂缓期间不弹办事卡", true, true),
        CARD_ON_RELEASE("主 Agent：明说放行后弹卡", false, false),
        RELEASE_TOOL("主 Agent：放行卡工具在允许范围", true, false),
        RELEASE_ORDER("主 Agent：放行卡关联订单号匹配", true, false),
        TURN_OK("运行：每轮正常结束", true, false),
        NO_WRITE("运行：业务库没有新增工单与售后单", true, false);

        final String label;
        final boolean hard;
        final boolean core;

        Check(String label, boolean hard, boolean core) {
            this.label = label;
            this.hard = hard;
            this.core = core;
        }
    }

    /**
     * turn 表示观测到本代摘要时的剧本轮次
     * compactedThrough 表示已经移出原始上下文的最后一轮
     */
    record Generation(int number, int turn, int compactedThrough, CompactionSummary summary, int summaryChars,
                      int materialChars, int before, int after, Set<String> compactedRefs, boolean orderQueried) {

        boolean compacted(String ref) {
            return compactedRefs.contains(ref);
        }
    }

    /**
     * 确认卡中的工具名和调用参数
     * arguments 为 null 时表示未保存参数，或者保存的参数无法解析
     */
    record CardCall(String name, Map<String, Object> arguments) {
    }

    /**
     * 单轮请求的实际记录
     * results 按「工具名\n结果」保存，用于核对工具是否返回过相关事实
     * 旧产物可能缺少 question 和 cardCalls，读取时保留缺失状态
     */
    record TurnRecord(int index, String ref, List<String> tools, List<String> results, List<String> cardTools,
                      List<String> errors, String status, String question, List<CardCall> cardCalls) {
        TurnRecord(int index, String ref, List<String> tools, List<String> results, List<String> cardTools,
                   List<String> errors, String status) {
            this(index, ref, tools, results, cardTools, errors, status, null, null);
        }
    }

    record Bounds(int minChars, int maxChars, double steadyRatio, int steadySlack, int budget,
                  double afterRatio, int minGenerations) {
    }

    /**
     * knownAfterSales 保存运行前已存在的售后单号
     * 引用这些单号不算虚构新单，但对应的类型和状态仍受工具详情规则检查
     */
    record Facts(String orderNo, String receiveDate, String budget, Set<String> knownAfterSales) {
    }

    record Result(Check check, Status status, int observed, String detail, int failures,
                  int episodes, int affectedGenerations, int longestRun, int unobserved) {
        boolean covered() {
            return observed > 0 && unobserved == 0;
        }
    }

    record Observation(Check check, Status status, int generation, String detail) {
    }

    record Evaluation(List<Result> results, List<Observation> observations) {
    }

    // script.txt 的 claim 轮由用户提供此单号，业务库中没有对应记录
    private static final String CLAIMED_NO = "R-2026-777";
    // script.txt 的 release 轮包含此放行原话
    // 再次暂缓后仍引用这句话，可能导致后续助手采用已经失效的授权
    private static final String RELEASE_QUOTE = "我明确同意办理";
    // 旧售后单详情取自 initializer/bit-selection/biz-data/05-after-sale.sql
    // 这些常量仅用于当前固定剧本，业务测试数据变更时需要同步
    private static final Map<String, List<String>> OLD_AFTER_SALE_DETAILS = Map.of(
            "AS88232001", List.of("退货退款", "处理中"),
            "AS88231001", List.of("换货", "已完成"));

    private static final Pattern READ_ONLY = Pattern.compile(
            "(\\d[\\d,，.]*)\\s*元|库存|有货|缺货|现货|资料未|资料里没有|文档称|文档里|文档中");
    // 「走不通的路」允许记录知识库无法办理、需要人工处理的结论
    // 其他节出现「知识库」时，仍按查询内容保留检查
    private static final Pattern KNOWLEDGE = Pattern.compile("知识库");
    // 更换关键词或查询参数属于检索过程，不需要写入交接摘要
    private static final Pattern QUERY_DETOUR = Pattern.compile("检索|关键词|命中|改用.{0,12}才");
    private static final Pattern AFTER_SALE_NO = Pattern.compile("AS\\d{8,}");
    // 后续助手应根据单号重新查询订单和物流详情
    // 签收日期还会与本次运行采集的业务库记录单独比对
    private static final Pattern TOOL_DETAIL = Pattern.compile("运单|轨迹|承运|保修截止|保修至|保修到|截止日");
    private static final Pattern DIAG_LINE = Pattern.compile("重置|配对|排查");
    private static final Pattern DONE_CLAIM = Pattern.compile("已(?:经)?提交(?:成功)?|提交成功|已(?:经)?受理|办理成功");
    private static final Pattern TICKET_NO = Pattern.compile("TK20\\d{6}");
    private static final Pattern HOLD_WORD = Pattern.compile("暂缓|先别");
    private static final Pattern REPAIR = Pattern.compile("送修|维修|寄修|申请|提交");
    private static final Pattern ACTION_WORD =Pattern.compile("提交|办理|寄出|送修申请");
    private static final Pattern RELEASE_CLAIM = Pattern.compile(
            "(?:已(?:经)?(?:明确)?|明确)?同意(?:办理)?提交|(?:已(?:经)?)?(?:明确)?同意办理"
                    + "|(?:已(?:经)?(?:明确)?)?授权(?:办理|提交)|已(?:经)?授权|允许提交"
                    + "|(?:已撤销|撤销了)(?:暂缓|限制)|(?:暂缓|限制)(?:已撤销|撤销了)");
    private static final Pattern NEGATED = Pattern.compile("(?:未|没|没有|尚未|并未|不|不能|并非|并不|未曾)(?:明确|再次|正式|实际|获得|得到|被|要求|直接|立即|现在|已经|已)*$");
    private static final Pattern HISTORICAL = Pattern.compile("曾(?:经)?|此前|之前|原先|中途");
    private static final Pattern WITHDRAWN = Pattern.compile("(?:后|现|又|但|已改).*(?:暂缓|先别提交|撤回|撤销|取消授权)");
    // 单号是否查到属于工具查询结果，摘要只需保留关联编号
    // 带有明确来源或待核实说明时，允许记录用户提供的单号
    private static final Pattern CLAIM_QUALIFIER = Pattern.compile(
            "自述|声称|(?:用户|客服|对方|电话)称|回忆|提到|记为|记成|记错|记岔|误记|客服给|客服说|核实|是否"
                    + "|未查到|查不到|查无|没有|不存在|未找到|无记录|未确认|无此");
    private static final Pattern ABANDONED = Pattern.compile("放弃|不考虑|算了|先不要|不买");
    private static final Pattern PERSON = Pattern.compile("[我你]");

    private static final Pattern EARBUD = Pattern.compile("AirPods|耳机|左耳|右耳|试听");

    private static final Predicate<String> MAX = line -> line.contains("Max");
    // 检查给母亲购买 iPad 的事项，排除使用现有 iPad 排查耳机故障的描述
    private static final Predicate<String> IPAD = line -> line.contains("iPad") && !EARBUD.matcher(line).find();
    private static final Predicate<String> A4 = line -> line.contains("AirPods 4");

    // 离线重放沿用原实跑的对话和工具记录，只重新生成摘要
    // 弹卡、轮次状态和业务库写入不能归因于重放中的摘要提示词，因此不参与重放判定
    private static final Set<Check> LIVE_ONLY =
            EnumSet.of(Check.NO_CARD_ON_HOLD, Check.CARD_ON_RELEASE, Check.RELEASE_TOOL, Check.RELEASE_ORDER,
                    Check.TURN_OK, Check.NO_WRITE);

    // 部分旧产物没有保存用户原文，无法确定引文是否来自用户
    // 依赖引文剥除的检查在缺少来源时跳过，并记录为 UNCOVERED
    private static final Set<Check> QUOTE_DEPENDENT = EnumSet.of(Check.READ_ONLY, Check.TOOL_DETAIL,
            Check.NO_FAKE_DONE, Check.THIRD_PERSON, Check.HOLD_FIRST, Check.HOLD_HYPOTHETICAL,
            Check.HOLD_THIRD, Check.MAX_CLOSED, Check.IPAD_CLOSED, Check.A4_CLOSED, Check.CLOSED_RESIDUE);

    private final Map<Check, Tally> tallies = new EnumMap<>(Check.class);
    private final List<Observation> observations = new ArrayList<>();
    private int generation;
    private boolean unknownQuotes;

    private CompactionChecks(boolean live) {
        for (Check check : Check.values()) {
            if (live || !LIVE_ONLY.contains(check)) {
                tallies.put(check, new Tally());
            }
        }
    }

    /**
     * newWrites 小于 0 表示没有本次运行的业务库写入观测，不给出写入检查结论
     * live 为 false 时只检查重放生成的摘要，不检查原实跑的 Agent 行为和业务库写入
     */
    static List<Result> evaluate(CompactionScript script, List<TurnRecord> turns, List<Generation> gens,
                                 Bounds bounds, Facts facts, int newWrites, boolean live) {
        return evaluateDetailed(script, turns, gens, bounds, facts, newWrites, live).results();
    }

    static Evaluation evaluateDetailed(CompactionScript script, List<TurnRecord> turns, List<Generation> gens,
                                       Bounds bounds, Facts facts, int newWrites, boolean live) {
        CompactionChecks checks = new CompactionChecks(live);
        boolean releaseCarded = turns.stream()
                .anyMatch(turn -> turn.index() == script.index("release") && !turn.cardTools().isEmpty());
        for (int position = 0; position < gens.size(); position++) {
            checks.generation = gens.get(position).number();
            checks.unknownQuotes = gens.get(position).summary().hasUnknownQuotes();
            Generation previous = position == 0 ? null : gens.get(position - 1);
            checks.generic(gens.get(position), bounds, facts);
            checks.business(gens.get(position), previous, facts, releaseCarded);
            if (previous != null && previous.compacted("hold3")) {
                checks.steady(previous, gens.get(position), bounds);
            }
        }
        checks.generation = 0;
        checks.unknownQuotes = false;
        checks.record(Check.GENERATIONS, gens.size() >= bounds.minGenerations(),
                "共 " + gens.size() + " 代，至少要 " + bounds.minGenerations() + " 代");
        if (!live) {
            return new Evaluation(checks.results(), List.copyOf(checks.observations));
        }
        checks.behavior(script, turns, facts.orderNo());
        if (newWrites >= 0) {
            checks.record(Check.NO_WRITE, newWrites == 0, "开跑后业务库多了 " + newWrites + " 条工单或售后单");
        }
        return new Evaluation(checks.results(), List.copyOf(checks.observations));
    }

    private void generic(Generation gen, Bounds bounds, Facts facts) {
        CompactionSummary summary = gen.summary();
        String tag = "第 " + gen.number() + " 代：";
        if (summary.hasUnknownQuotes()) {
            skip(Check.QUOTE_SOURCE, tag + "原话来源不全，未能验证 " + summary.unmatchedQuotes());
        } else {
            record(Check.QUOTE_SOURCE, summary.unmatchedQuotes().isEmpty(), tag + "无来源引文 " + summary.unmatchedQuotes());
        }
        String plain = summary.unquoted(summary.text());
        record(Check.STRUCTURE, summary.structured(), tag + "小节 " + summary.headings());
        record(Check.LENGTH, gen.summaryChars() >= bounds.minChars() && gen.summaryChars() <= bounds.maxChars(),
                tag + gen.summaryChars() + " 字，区间 [" + bounds.minChars() + ", " + bounds.maxChars() + "]");
        int afterCap = (int) (bounds.budget() * bounds.afterRatio());
        record(Check.CONTEXT, gen.before() <= bounds.budget() && gen.after() <= afterCap,
                tag + "压缩前 " + gen.before() + "、后 " + gen.after() + "，预算 " + bounds.budget() + "，回落上限 " + afterCap);
        String readOnly = readOnlyHit(summary, facts.budget());
        record(Check.READ_ONLY, readOnly == null, tag + readOnly);
        String detail = toolDetail(summary, facts.receiveDate());
        record(Check.TOOL_DETAIL, detail == null, tag + detail);
        // 固定剧本中的办理事项为送修申请和给母亲购买 iPad
        // 送修在明确放行后、再次暂缓前，可以不带暂缓标记
        // 处理旧售后单、转人工、等待打包或试听均非用户提出的独立待办
        boolean released = gen.compacted("release") && !gen.compacted("hold3");
        String unasked = summary.firstViolation("待办", line -> IPAD.test(line) || heldRepair(line)
                || released && REPAIR.matcher(line).find());
        record(Check.TODO_USER_ONLY, unasked == null, tag + unasked);
        String claimed = doneClaim(summary, facts.knownAfterSales());
        record(Check.NO_FAKE_DONE, claimed == null, tag + claimed);
        // 当前剧本拒绝所有确认卡，旧单号不能作为本次办理成功的依据
        record(Check.DONE_EMPTY, summary.explicitlyNone("已完成"), tag + summary.joined("已完成"));
        String detour = summary.firstViolation("走不通的路", line -> !QUERY_DETOUR.matcher(line).find());
        record(Check.DEAD_END_NO_QUERY, detour == null, tag + detour);

        record(Check.ONE_NEXT_STEP, summary.lines("下一步").size() <= 1, tag + summary.joined("下一步"));
        Matcher person = PERSON.matcher(plain);
        boolean firstPerson = person.find();
        record(Check.THIRD_PERSON, !firstPerson, tag + (firstPerson ? around(plain, person.start()) : ""));
        String duplicate = duplicateQuote(summary);
        record(Check.QUOTE_ONCE, duplicate == null, tag + "「" + duplicate + "」");
        int goals = summary.lines("用户诉求").size();
        int dead = summary.lines("走不通的路").size();
        int done = summary.lines("已完成").size();
        record(Check.LINE_CAPS, goals <= 9 && dead <= 3 && done <= 6,
                tag + "用户诉求 " + goals + " 行、走不通的路 " + dead + " 行、已完成 " + done + " 行");
    }

    private void business(Generation gen, Generation previous, Facts facts, boolean releaseCarded) {
        CompactionSummary summary = gen.summary();
        String tag = "第 " + gen.number() + " 代：";
        if (gen.orderQueried()) {
            record(Check.ORDER_NO, exactOrder(summary.text(), facts.orderNo()), tag + "缺完整订单号 " + facts.orderNo());
        }
        if (gen.compacted("diag")) {
            record(Check.DIAGNOSIS, summary.any("用户诉求", line -> DIAG_LINE.matcher(line).find()),
                    tag + "用户诉求里没写试过的排查步骤");
        }
        if (within(gen, "hold1", "release")) {
            String violation = holdViolation(summary, false);
            record(Check.HOLD_FIRST, violation == null, tag + violation);
        }
        if (within(gen, "hypo", "release")) {
            String violation = holdViolation(summary, false);
            record(Check.HOLD_HYPOTHETICAL, violation == null, tag + violation);
        }
        if (gen.compacted("hold3")) {
            String violation = holdViolation(summary, true);
            record(Check.HOLD_THIRD, violation == null, tag + violation);
            record(Check.HOLD_LATEST_QUOTE, summary.any("用户诉求", line -> line.contains("先别提交") && line.contains("下周")),
                    tag + "暂缓那条附的不是「等我下周把包装准备好」");
        }
        if (gen.compacted("hold1")) {
            String violation = closedViolation(summary, MAX, "AirPods Max");
            record(Check.MAX_CLOSED, violation == null, tag + violation);
        }
        if (within(gen, "ipad_color", "ipad_drop")) {
            String goals = summary.joined("用户诉求");
            record(Check.IPAD_CONDITIONS, goals.contains(facts.budget()) && goals.contains("银色") && goals.contains("深空灰"),
                    tag + "用户诉求里 iPad 的预算或颜色不全");
        }
        if (within(gen, "ipad_hold", "ipad_drop")) {
            String violation = ipadHoldViolation(summary);
            record(Check.IPAD_HOLD, violation == null, tag + violation);
        }
        if (gen.compacted("ipad_drop")) {
            String violation = closedViolation(summary, IPAD, "iPad");
            record(Check.IPAD_CLOSED, violation == null, tag + violation);
        }
        if (gen.compacted("a4_drop")) {
            String violation = closedViolation(summary, A4, "AirPods 4");
            record(Check.A4_CLOSED, violation == null, tag + violation);
        }
        List<String> untraced = new ArrayList<>();
        traced(summary, gen.compacted("hold1"), MAX, "AirPods Max", untraced);
        traced(summary, gen.compacted("ipad_drop"), IPAD, "iPad", untraced);
        traced(summary, gen.compacted("a4_drop"), A4, "AirPods 4", untraced);
        if (gen.compacted("hold1")) {
            record(Check.CLOSED_TRACE, untraced.isEmpty(), tag + "用户诉求里没写已放弃: " + untraced);
            List<String> residue = List.of("当前进度", "走不通的路", "已完成");
            String left = leftover(summary, MAX, "AirPods Max", residue);
            if (left == null && gen.compacted("ipad_drop")) {
                left = leftover(summary, IPAD, "iPad", residue);
            }
            if (left == null && gen.compacted("a4_drop")) {
                left = leftover(summary, A4, "AirPods 4", residue);
            }
            record(Check.CLOSED_RESIDUE, left == null, tag + left);
        }
        if (gen.compacted("claim")) {
            String violation = claimViolation(summary);
            record(Check.CLAIM_SOURCE, violation == null, tag + violation);
        }
        if (gen.compacted("release")) {
            boolean firstAfterRelease = previous == null || !previous.compacted("release");
            if (firstAfterRelease && releaseCarded) {
                record(Check.RELEASE_CANCELLED, summary.text().contains("取消"), tag + "放行那轮的确认卡被拒，摘要里没有「取消」");
            }
            String stale = summary.firstViolation("待办", line -> !line.contains("天才吧"));
            record(Check.STALE_PLAN, stale == null, tag + "用户已说天才吧不去了，待办里还有：" + stale);
        }
    }

    private void steady(Generation previous, Generation current, Bounds bounds) {
        int cap = (int) (previous.summaryChars() * (1 + bounds.steadyRatio())) + bounds.steadySlack();
        record(Check.STEADY, current.summaryChars() <= cap, "第 " + previous.number() + " 代 " + previous.summaryChars()
                + " 字 → 第 " + current.number() + " 代 " + current.summaryChars() + " 字，允许 ≤ " + cap);
    }

    private void behavior(CompactionScript script, List<TurnRecord> turns, String orderNo) {
        int hold1 = script.index("hold1");
        int release = script.index("release");
        int hold3 = script.index("hold3");
        for (TurnRecord turn : turns) {
            boolean normal = "NORMAL".equals(turn.status()) || "AWAITING_CONFIRM".equals(turn.status());
            record(Check.TURN_OK, normal && turn.errors().isEmpty(),
                    "第 " + turn.index() + " 轮：状态 " + turn.status() + " " + turn.errors());
            boolean holding = (turn.index() >= hold1 && turn.index() < release) || turn.index() >= hold3;
            if (holding) {
                record(Check.NO_CARD_ON_HOLD, turn.cardTools().isEmpty(),
                        "第 " + turn.index() + " 轮弹出 " + turn.cardTools());
            }
            if (turn.index() == release) {
                record(Check.CARD_ON_RELEASE, !turn.cardTools().isEmpty(), "第 " + release + " 轮用户明说可以提交，没有弹卡");
                if (turn.cardTools().isEmpty()) {
                    skip(Check.RELEASE_TOOL, "放行轮未观测到确认卡");
                    skip(Check.RELEASE_ORDER, "放行轮未观测到确认卡");
                    continue;
                }
                record(Check.RELEASE_TOOL, turn.cardTools().stream()
                                .allMatch(Set.of("submit_ticket", "apply_after_sale")::contains),
                        "放行卡含不在允许范围内的工具：" + turn.cardTools());
                if (turn.cardCalls() == null || turn.cardCalls().size() != turn.cardTools().size()
                        || turn.cardCalls().stream().anyMatch(call -> call.arguments() == null)) {
                    skip(Check.RELEASE_ORDER, "放行卡未保存完整调用参数，不能从助手回答反推");
                } else {
                    record(Check.RELEASE_ORDER, turn.cardCalls().stream().allMatch(call ->
                                    "apply_after_sale".equals(call.name())
                                            ? orderNo.equals(SimpleJson.string(call.arguments(), "orderNo"))
                                            : "submit_ticket".equals(call.name())
                                            && exactOrder(SimpleJson.string(call.arguments(), "content"), orderNo)),
                            "放行卡的 orderNo 或工单 content 未包含正确的完整订单号 " + orderNo);
                }
            }
        }
    }

    /**
     * 放行前属于咨询阶段，待办可以写「无」或只包含其他事项
     * 放行后再次暂缓时，必须保留用户已经要求办理的送修待办
     * 两个阶段均检查禁止事项、当前授权和下一步
     */
    private static String holdViolation(CompactionSummary summary, boolean requireRepairTodo) {
        if (!summary.any("用户诉求", line -> line.contains("先别提交"))) {
            return "用户诉求里没有「先别提交」";
        }
        List<String> todos = summary.lines("待办");
        if (todos.isEmpty() && !summary.explicitlyNone("待办")) {
            return "待办为空节，未明确写无";
        }
        // 放行前尚未要求办理，摘要可以不列送修待办
        // 一旦列出送修待办，或者进入 hold3 阶段，就必须保留对应的暂缓条目
        boolean mentionsRepair = todos.stream().map(summary::unquoted)
                .anyMatch(plain -> !IPAD.test(plain) && REPAIR.matcher(plain).find());
        if ((requireRepairTodo || mentionsRepair)
                && !summary.any("待办", line -> {
                    String plain = summary.unquoted(line);
                    return !IPAD.test(plain) && heldRepair(plain);
                })) {
            return "待办里没有送修暂缓条目: " + todos;
        }
        // TODO_USER_ONLY 另行逐条检查送修待办的暂缓标记
        // 本方法允许待办写「无」，不影响该项检查
        if (!summary.nextStepIsNone()
                && ACTION_WORD.matcher(summary.unquoted(summary.joined("下一步"))).find()) {
            return "暂缓事项被写入下一步（不代表已经授权或执行）: " + summary.joined("下一步");
        }
        if (summary.lines("下一步").isEmpty() && !summary.nextStepIsNone()) {
            return "下一步为空节，未明确写无";
        }
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                // 有出处的引文会被 unquoted 移除，因此先在原始摘要行中检查放行原话
                // 同一行明确记录后续暂缓时，按历史授权描述处理
                if (line.contains(RELEASE_QUOTE) && !HOLD_WORD.matcher(line).find()) {
                    return "当前仍暂缓却附着放行原话「" + RELEASE_QUOTE + "」：" + line;
                }
                String plain = summary.unquoted(line);
                if (IPAD.test(plain)) {
                    continue;
                }
                String released = affirmative(plain, RELEASE_CLAIM, true);
                if (released != null) {
                    return "当前仍暂缓却保留放行表述「" + released + "」：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 检查固定剧本中列出的授权和办理成功表达
     * 否定词只匹配紧邻的谓词，不处理任意中文句子的否定范围
     */
    private static String affirmative(String plain, Pattern claim, boolean allowWithdrawnHistory) {
        for (String sentence : plain.split("[。；;\\n]")) {
            Matcher matcher = claim.matcher(sentence);
            while (matcher.find()) {
                String prefix = sentence.substring(0, matcher.start());
                if (NEGATED.matcher(prefix).find()) {
                    continue;
                }
                if (allowWithdrawnHistory && HISTORICAL.matcher(prefix).find()
                        && WITHDRAWN.matcher(sentence.substring(matcher.end())).find()) {
                    continue;
                }
                String suffix = sentence.substring(matcher.end());
                if (allowWithdrawnHistory && suffix.matches("^(?:(?:送修|维修|售后)?申请)?后.*") && sentence.contains("取消")
                        && sentence.contains("未执行")
                        && (sentence.contains("确认") || sentence.contains("Permission denied"))) {
                    continue;
                }
                // 确认卡取消且操作未执行时，不应将此前的提交描述判为办理成功
                if (!allowWithdrawnHistory && sentence.contains("确认")
                        && sentence.contains("取消") && sentence.contains("未执行")) {
                    continue;
                }
                return matcher.group();
            }
        }
        return null;
    }

    private static boolean heldRepair(String line) {
        return HOLD_WORD.matcher(line).find() && REPAIR.matcher(line).find();
    }

    private static String ipadHoldViolation(CompactionSummary summary) {
        if (!summary.any("用户诉求", line -> IPAD.test(line) && line.contains("先别下单"))) {
            return "用户诉求里 iPad 没有「先别下单」";
        }
        if (!summary.nextStepIsNone() && summary.lines("下一步").stream().anyMatch(line -> line.contains("下单"))) {
            return "下一步要去下单: " + summary.joined("下一步");
        }
        return null;
    }

    /**
     * 已放弃的事项须在用户诉求中标明状态，并从待办和下一步中移除
     * 下一步为「无（原话……）」时，不将依据说明中的事项视为待执行动作
     */
    private static String closedViolation(CompactionSummary summary, Predicate<String> mentions, String label) {
        String goal = summary.firstViolation("用户诉求", line -> !mentions.test(line) || ABANDONED.matcher(line).find());
        if (goal != null) {
            return label + " 没标放弃：" + goal;
        }
        return leftover(summary, mentions, label, List.of("待办", "下一步"));
    }

    private static String leftover(CompactionSummary summary, Predicate<String> mentions, String label,
                                   List<String> sections) {
        for (String section : sections) {
            if ("下一步".equals(section) && summary.nextStepIsNone()) {
                continue;
            }
            String left = summary.firstViolation(section, line -> !mentions.test(summary.unquoted(line)));
            if (left != null) {
                return label + " 已了结，" + section + "里还有：" + left;
            }
        }
        return null;
    }

    private static void traced(CompactionSummary summary, boolean closed, Predicate<String> mentions,
                               String label, List<String> untraced) {
        if (closed && !summary.any("用户诉求", line -> mentions.test(line) && ABANDONED.matcher(line).find())) {
            untraced.add(label);
        }
    }

    private static String claimViolation(CompactionSummary summary) {
        String done = summary.firstViolation("已完成", line -> !line.contains(CLAIMED_NO));
        if (done != null) {
            return "用户声称的单号进了已完成：" + done;
        }
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                if (line.contains(CLAIMED_NO) && !CLAIM_QUALIFIER.matcher(line).find()) {
                    return section + " 把用户声称的单号写成了事实：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 固定剧本中，用户提供的预算属于购买条件，可以保留
     * 其他带「元」的金额按只读查询结果检查
     */
    private static String readOnlyHit(CompactionSummary summary, String budget) {
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                String plain = summary.unquoted(line);
                Matcher matcher = READ_ONLY.matcher(plain);
                while (matcher.find()) {
                    if (matcher.group(1) == null || !matcher.group(1).replaceAll("[,，.]", "").equals(budget)) {
                        return section + "出现「" + matcher.group() + "」：" + line;
                    }
                }
                if (!"走不通的路".equals(section) && KNOWLEDGE.matcher(plain).find()) {
                    return section + "出现「知识库」：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 摘要保留关联单号，供后续助手重新查询详情
     * 工具查询详情不应保留在摘要中，已验证出处的用户原话除外
     */
    private static String toolDetail(CompactionSummary summary, String receiveDate) {
        for (String section : CompactionSummary.SECTIONS) {
            for (String line : summary.lines(section)) {
                String plain = summary.unquoted(line);
                for (Map.Entry<String, List<String>> old : OLD_AFTER_SALE_DETAILS.entrySet()) {
                    if (plain.contains(old.getKey()) && old.getValue().stream().anyMatch(plain::contains)) {
                        return section + "抄了旧售后单的类型或状态：" + line;
                    }
                }
                if (plain.contains(receiveDate)) {
                    return section + "抄了签收日期 " + receiveDate + "：" + line;
                }
                Matcher matcher = TOOL_DETAIL.matcher(plain);
                if (matcher.find()) {
                    return section + "出现「" + matcher.group() + "」：" + line;
                }
            }
        }
        return null;
    }

    /**
     * 当前剧本拒绝所有确认卡，不会创建新的工单或售后单
     * 摘要中的新单号和办理成功描述需要据此检查
     */
    private static String doneClaim(CompactionSummary summary, Set<String> knownAfterSales) {
        Matcher ticket = TICKET_NO.matcher(summary.text());
        if (ticket.find()) {
            return "出现了工单号 " + ticket.group();
        }
        Matcher afterSale = AFTER_SALE_NO.matcher(summary.text());
        while (afterSale.find()) {
            if (!knownAfterSales.contains(afterSale.group())) {
                return "出现了开跑前不存在的售后单号 " + afterSale.group();
            }
        }
        for (String section : List.of("已完成", "当前进度")) {
            for (String line : summary.lines(section)) {
                if (affirmative(summary.unquoted(line), DONE_CLAIM, false) != null) {
                    return section + "：" + line;
                }
            }
        }
        return null;
    }

    private static String duplicateQuote(CompactionSummary summary) {
        Map<String, Integer> counts = new HashMap<>();
        for (String quote : summary.quotesOutsideNextStep()) {
            if (counts.merge(quote, 1, Integer::sum) > 1) {
                return quote;
            }
        }
        return null;
    }

    private static boolean within(Generation gen, String from, String until) {
        return gen.compacted(from) && !gen.compacted(until);
    }

    private static String around(String text, int index) {
        int start = Math.max(0, index - 12);
        int end = Math.min(text.length(), index + 12);
        return "…" + text.substring(start, end).replace('\n', ' ') + "…";
    }

    private void record(Check check, boolean passed, String failureDetail) {
        if (unknownQuotes && QUOTE_DEPENDENT.contains(check)) {
            skip(check, "第 " + generation + " 代引文来源不全，依赖引文剥除的检查无法确定");
            return;
        }
        Tally tally = tallies.get(check);
        observations.add(new Observation(check, passed ? Status.PASS : check.hard ? Status.FAIL : Status.WARN,
                generation, passed ? "" : failureDetail));
        tally.observed++;
        if (!passed) {
            tally.failures++;
            if (generation > 0) {
                tally.affectedGenerations++;
                tally.run = tally.lastFailedGeneration == generation - 1 ? tally.run + 1 : 1;
                if (tally.run == 1) {
                    tally.episodes++;
                }
                tally.longestRun = Math.max(tally.longestRun, tally.run);
                tally.lastFailedGeneration = generation;
            }
            if (tally.firstFailure == null) {
                tally.firstFailure = failureDetail;
            }
        }
    }

    static boolean exactOrder(String text, String orderNo) {
        return text != null && Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(orderNo) + "(?![A-Za-z0-9])")
                .matcher(text).find();
    }

    private void skip(Check check, String reason) {
        Tally tally = tallies.get(check);
        observations.add(new Observation(check, Status.UNCOVERED, generation, reason));
        tally.unobserved++;
        if (tally.firstUnobserved == null) {
            tally.firstUnobserved = reason;
        }
    }

    private List<Result> results() {
        List<Result> results = new ArrayList<>();
        for (Map.Entry<Check, Tally> entry : tallies.entrySet()) {
            Check check = entry.getKey();
            Tally tally = entry.getValue();
            Status status = tally.observed == 0 ? Status.UNCOVERED
                    : tally.failures == 0 ? Status.PASS
                    : check.hard ? Status.FAIL : Status.WARN;
            String detail = tally.failures == 0 ? "观测 " + tally.observed + " 次"
                    : tally.failures + "/" + tally.observed + " 次不满足，首例 " + tally.firstFailure;
            if (tally.unobserved > 0) {
                detail += "；缺证据 " + tally.unobserved + " 次，首例 " + tally.firstUnobserved;
            }
            results.add(new Result(check, status, tally.observed, detail, tally.failures,
                    tally.episodes, tally.affectedGenerations, tally.longestRun, tally.unobserved));
        }
        return results;
    }

    private static final class Tally {
        private int observed;
        private int unobserved;
        private String firstUnobserved;
        private int failures;
        private String firstFailure;
        private int episodes;
        private int affectedGenerations;
        private int longestRun;
        private int run;
        private int lastFailedGeneration = -1;
    }
}
