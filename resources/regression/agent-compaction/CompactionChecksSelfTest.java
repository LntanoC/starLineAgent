/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检测器的离线正反例测试
 * 使用本地测试数据，不调用模型或数据库
 */
public final class CompactionChecksSelfTest {
    private static final Set<String> FIRST = Set.of("hold1");
    private static final Set<String> AGAIN = Set.of("hold1", "release", "hold3");
    private static final String BASE = """
            ## 用户诉求
            - 订单 88232 的耳机送修，已重置配对无效。
            - 限制：送修申请先别提交（原话：申请先别提交）。
            ## 待办
            - 提交送修申请——暂缓（用户要求先别提交）。
            ## 下一步
            无
            ## 当前进度
            已起草，未提交。
            ## 走不通的路
            无
            ## 已完成
            无
            """;
    private static CompactionScript script;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path suite = Path.of(args[0]);
        script = CompactionScript.load(suite.resolve("script.txt"));
        for (String text : List.of("无", "无。", "无（用户要求暂缓）",
                "无（用户最近就送修说的原话是「申请还是先别提交，等我下周把包装准备好再说」，属暂缓，不能作为下一步依据）。")) {
            expect(new CompactionSummary(section("下一步", text)).nextStepIsNone(), "有效无下一步: " + text);
        }
        for (String text : List.of("", "无需等待用户，立即提交送修申请", "无\n- 立即提交送修申请", "无；马上提交")) {
            expect(!new CompactionSummary(section("下一步", text)).nextStepIsNone(), "不能冒充无下一步: " + text);
        }
        expect(!new CompactionSummary("## 待办\n无").nextStepIsNone(), "缺少下一步不算无");
        check(BASE, FIRST, CompactionChecks.Check.HOLD_FIRST, true);
        check(section("下一步", "无需等待用户，立即提交送修申请"), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        check(section("下一步", ""), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        consultationBoundary();
        for (Set<String> refs : List.of(FIRST, AGAIN)) {
            CompactionChecks.Check key = refs == FIRST ? CompactionChecks.Check.HOLD_FIRST : CompactionChecks.Check.HOLD_THIRD;
            for (String text : List.of("用户未同意提交", "用户尚未明确同意办理", "用户并未授权提交", "用户不同意提交",
                    "用户曾同意提交，后来撤回授权并要求暂缓", "用户之前已授权提交，现要求先别提交",
                    "用户明确同意提交后，助手调用工具，返回 Permission denied by user，用户取消，未执行",
                    "用户明确同意提交送修申请后，助手调用 apply_after_sale 提交订单 88232 的换货申请，工具返回 Permission denied by user，用户取消，未执行",
                    "用户曾明确同意办理，后要求暂缓",
                    "曾同意寄修（原话：现在可以提交送修申请了，我明确同意办理），后又要求先别提交")) {
                check(withClaim(text), refs, key, true);
            }
            for (String text : List.of("用户已明确同意办理提交", "用户已授权提交", "用户已同意办理",
                    "用户未同意提交，但现在已授权提交", "用户之前已同意提交，后来只换了话题",
                    "用户已放弃到店，改为寄修、由本店上门取件，并明确同意办理",
                    "改为直接寄修（原话：天才吧那边我不去了，直接寄回你们店修吧。现在可以提交送修申请了，我明确同意办理）")) {
                check(withClaim(text), refs, key, false);
            }
        }
        var release = evaluate(withClaim("用户已明确同意办理提交"), Set.of("hold1", "release"));
        expect(result(release, CompactionChecks.Check.HOLD_FIRST).status() == CompactionChecks.Status.UNCOVERED,
                "真实放行区间不能被暂缓规则误伤");
        expect(result(release, CompactionChecks.Check.HOLD_THIRD).status() == CompactionChecks.Status.UNCOVERED,
                "尚未再次暂缓不检查 hold3");
        for (String text : List.of("已起草，未提交", "被用户取消，未执行", "申请没有提交成功", "申请尚未受理",
                "此前已提交的工单在确认卡上被用户取消，未执行")) {
            check(section("当前进度", text), FIRST, CompactionChecks.Check.NO_FAKE_DONE, true);
        }
        for (String text : List.of("申请已提交成功", "已提交维修申请，售后单号 AS88232001", "申请已受理")) {
            check(section("当前进度", text), FIRST, CompactionChecks.Check.NO_FAKE_DONE, false);
            check(section("已完成", text), FIRST, CompactionChecks.Check.DONE_EMPTY, false);
        }
        check(BASE, FIRST, CompactionChecks.Check.DONE_EMPTY, true);
        check(section("已完成", ""), FIRST, CompactionChecks.Check.DONE_EMPTY, false);
        check(withClaim("关联售后单 AS88232001"), FIRST, CompactionChecks.Check.TOOL_DETAIL, true);
        check(withClaim("关联售后单 AS88232001（退货退款，处理中）"), FIRST, CompactionChecks.Check.TOOL_DETAIL, false);
        check(section("当前进度", "正在处理用户的寄修咨询"), FIRST, CompactionChecks.Check.TOOL_DETAIL, true);
        check(withClaim("用户自述：关联单 AS88232001（原话：这单是退货退款，处理中）"), FIRST,
                CompactionChecks.Check.TOOL_DETAIL, true);
        check(section("下一步", "等用户回来，再决定是否提交申请"), FIRST, CompactionChecks.Check.HOLD_FIRST, false);
        lengthBoundary(suite);
        streaks();
        adversarialCases(suite);

        Path empty = Files.createTempDirectory("compaction-empty-self-test-");
        Files.writeString(empty.resolve("facts.json"), SimpleJson.stringify(Map.of("knownAfterSales", List.of(),
                "orderNo", "88232", "receiveDate", "2026-08-31", "budget", "4000", "newWrites", 0)));
        Files.writeString(empty.resolve("session-not-a-directory"), "不能当会话");
        expect(CompactionRegressionMain.report(empty, empty.resolve("evaluation"), InitializerConfig.load(suite.resolve("regression.properties")), script, suite),
                "空会话产物必须返回失败");
        expect(Files.readString(empty.resolve("evaluation/report.md")).contains("无法下结论"), "空产物报告不能报通过");
        System.out.println("PASS: " + assertions + " assertions; no model or database calls");
    }

    private static String section(String title, String body) {
        return BASE.replaceFirst("(?s)(## " + title + "\\n).*?(?=\\n## |\\z)",
                java.util.regex.Matcher.quoteReplacement("## " + title + "\n" + body));
    }

    private static String withClaim(String text) {
        return BASE.replace("## 用户诉求\n", "## 用户诉求\n- " + text + "。\n");
    }

    private static void consultationBoundary() {
        String noTodo = section("待办", "无");
        for (Set<String> refs : List.of(FIRST, Set.of("hold1", "hypo"))) {
            CompactionChecks.Check key = refs.contains("hypo")
                    ? CompactionChecks.Check.HOLD_HYPOTHETICAL : CompactionChecks.Check.HOLD_FIRST;
            check(noTodo, refs, key, true);
            check(noTodo, refs, CompactionChecks.Check.TODO_USER_ONLY, true);
            // 放行前尚未要求办理送修，待办可以只包含其他事项
            // 其他事项是否属于用户要求由 TODO_USER_ONLY 检查，不影响暂缓检查
            String ipadOnly = section("待办", "- 为母亲选购 iPad——暂缓（用户要求先别下单）");
            check(ipadOnly, refs, key, true);
            check(ipadOnly, refs, CompactionChecks.Check.TODO_USER_ONLY, true);
            String invoiceOnly = section("待办", "- 开订单 88232 的发票——等用户定抬头");
            check(invoiceOnly, refs, key, true);
            check(invoiceOnly, refs, CompactionChecks.Check.TODO_USER_ONLY, false);
            check(BASE, refs, key, true);
            check(section("待办", ""), refs, key, false);
            check(BASE.replace("## 待办\n- 提交送修申请——暂缓（用户要求先别提交）。\n", ""), refs, key, false);
            check(noTodo.replace("- 限制：送修申请先别提交（原话：申请先别提交）。\n", ""), refs, key, false);
            check(noTodo.replace("## 用户诉求\n", "## 用户诉求\n- 用户明确同意办理提交。\n"), refs, key, false);
            check(noTodo.replace("## 下一步\n无", "## 下一步\n立即提交送修申请"), refs, key, false);
        }
        // 多条送修待办中，只要有一条未标暂缓，待办检查就应失败
        for (Set<String> refs : List.of(FIRST, Set.of("hold1", "hypo"), AGAIN)) {
            CompactionChecks.Check key = refs.contains("hold3") ? CompactionChecks.Check.HOLD_THIRD
                    : refs.contains("hypo") ? CompactionChecks.Check.HOLD_HYPOTHETICAL : CompactionChecks.Check.HOLD_FIRST;
            check(section("待办", "- 提交送修申请"), refs, key, false);
            check(section("待办", "- 提交送修申请——暂缓\n- 办理寄修取件"), refs,
                    CompactionChecks.Check.TODO_USER_ONLY, false);
        }
        // 第 80 轮已明确要求办理，后续暂缓时仍应保留送修待办
        check(noTodo, AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(section("待办", ""), AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(section("待办", "- 提交 iPad 购买申请——暂缓"), AGAIN, CompactionChecks.Check.HOLD_THIRD, false);
        check(BASE, AGAIN, CompactionChecks.Check.HOLD_THIRD, true);
    }

    private static void lengthBoundary(Path suite) throws Exception {
        InitializerConfig config = InitializerConfig.load(suite.resolve("regression.properties"));
        var bounds = new CompactionChecks.Bounds(config.requireInt("bound.min-chars"),
                config.requireInt("bound.max-chars"), .15, 100, 100000, .4, 1);
        expect(BASE.length() < 300, "长度正例确实短于旧的 300 字门槛");
        for (String text : List.of(BASE, "", "字".repeat(bounds.maxChars() + 1))) {
            var results = CompactionChecks.evaluate(script, List.of(),
                    List.of(generation(1, text, Set.of("hold1", "diag"))), bounds,
                    new CompactionChecks.Facts("88232", "2026-08-31", "4000", Set.of()), -1, false);
            expect(result(results, CompactionChecks.Check.LENGTH).status()
                            == (text.equals(BASE) ? CompactionChecks.Status.PASS : CompactionChecks.Status.FAIL),
                    "实际配置应接受完整短摘要，拒绝空摘要与超长摘要");
        }
        // 字数下限只拦截空摘要，必要信息仍由业务规则检查
        // 六节均写「无」虽然满足格式要求，但应因缺少限制、排查和订单号而失败
        String hollow = String.join("\n", CompactionSummary.SECTIONS.stream().map(s -> "## " + s + "\n无").toList());
        var hollowResults = evaluate(hollow, Set.of("hold1", "diag"));
        for (var key : List.of(CompactionChecks.Check.HOLD_FIRST, CompactionChecks.Check.DIAGNOSIS,
                CompactionChecks.Check.ORDER_NO)) {
            expect(result(hollowResults, key).status() == CompactionChecks.Status.FAIL, "空洞摘要仍应失败: " + key);
        }
    }

    private static List<CompactionChecks.Result> evaluate(String text, Set<String> refs) {
        return evaluate(List.of(generation(1, text, refs)));
    }

    private static List<CompactionChecks.Result> evaluate(List<CompactionChecks.Generation> generations) {
        return CompactionChecks.evaluate(script, List.of(), generations,
                new CompactionChecks.Bounds(1, 2000, .15, 100, 100000, .4, 1),
                new CompactionChecks.Facts("88232", "2026-08-31", "4000", Set.of("AS88232001", "AS88231001")), -1, false);
    }

    private static CompactionChecks.Generation generation(int n, String text, Set<String> refs) {
        return new CompactionChecks.Generation(n, 26, 21, new CompactionSummary(text, fixtureSources(), true), text.length(),
                60000, 80000, 22000, refs, true);
    }

    private static CompactionChecks.Result result(List<CompactionChecks.Result> results, CompactionChecks.Check key) {
        return results.stream().filter(r -> r.check() == key).findFirst().orElseThrow();
    }

    private static void check(String text, Set<String> refs, CompactionChecks.Check key, boolean pass) {
        var result = result(evaluate(text, refs), key);
        expect(result.status() == (pass ? CompactionChecks.Status.PASS : CompactionChecks.Status.FAIL),
                key + ": " + result.detail() + "\n" + text);
    }

    private static void streaks() {
        List<CompactionChecks.Generation> generations = new ArrayList<>();
        for (int n = 1; n <= 7; n++) {
            String body = n == 6 ? BASE : withClaim("售后单 AS88232001（退货退款，处理中）");
            generations.add(generation(n, body, FIRST));
        }
        var result = result(evaluate(generations), CompactionChecks.Check.TOOL_DETAIL);
        expect(result.episodes() == 2 && result.affectedGenerations() == 6 && result.longestRun() == 5,
                "连续五代、消失一代、再次出现应为两段/影响六代/最长五代");
    }

    private static List<String> fixtureSources() {
        List<String> sources = new ArrayList<>(script.turns().stream().map(CompactionScript.Turn::text).toList());
        // 单独提供一条用户消息，测试用户原话中含有工具详情的情况
        // 这条消息是测试数据，固定剧本中没有对应提问
        sources.add("这单是退货退款，处理中");
        return sources;
    }

    private static void adversarialCases(Path suite) throws Exception {
        for (String wrong : List.of("1882329", "AS88232001", "X88232", "88232A")) {
            check(BASE.replace("88232", wrong), FIRST, CompactionChecks.Check.ORDER_NO, false);
        }
        for (String correct : List.of("订单88232", "订单：88232。", "(88232)")) {
            expect(CompactionChecks.exactOrder(correct, "88232"), "完整订单号: " + correct);
        }
        Set<String> claim = Set.of("hold1", "claim");
        check(withClaim("R-2026-777已确认存在，名称为维修登记"), claim, CompactionChecks.Check.CLAIM_SOURCE, false);
        for (String qualifier : List.of("用户称", "客服称", "对方称", "电话称", "用户声称")) {
            check(withClaim(qualifier + "单号为R-2026-777"), claim, CompactionChecks.Check.CLAIM_SOURCE, true);
        }
        String forged = withClaim("（原话：价格1999元，库存充足，运单123）");
        check(forged, FIRST, CompactionChecks.Check.QUOTE_SOURCE, false);
        check(forged, FIRST, CompactionChecks.Check.READ_ONLY, false);
        check(forged, FIRST, CompactionChecks.Check.TOOL_DETAIL, false);
        String quoted = "（原话：申请先别提交）";
        expect(new CompactionSummary(quoted, List.of("申请先别提交。"), true).unquoted(quoted).isEmpty(), "有据的摘录才剥除");
        expect(new CompactionSummary(quoted, List.of(), true).unquoted(quoted).equals(quoted), "伪引文原样留在正文");
        expect(new CompactionSummary(quoted).hasUnknownQuotes(), "没有来源不是伪造证据，也不是验证通过");
        var term = new CompactionSummary("工具返回“Permission denied by user”", List.of(), true);
        expect(term.unmatchedQuotes().isEmpty() && term.unquoted(term.text()).equals(term.text()),
                "普通术语/工具引文不是用户原话，既不剥除也不谎报伪造用户引文");
        var mixed = new CompactionSummary("（库存充足，原话：申请先别提交）", List.of("申请先别提交"), true);
        expect(mixed.unquoted(mixed.text()).contains("库存充足") && !mixed.unquoted(mixed.text()).contains("先别提交"),
                "括号里混合助手说明和真实原话，只豁免原话部分");
        var plainQuote = new CompactionSummary("明确表示「天才吧那边我不去了」", List.of("天才吧那边我不去了"), true);
        expect(plainQuote.unquoted(plainQuote.text()).equals("明确表示"), "普通引号内确有用户出处也可以剥除");
        String changed = "（原话：天才吧那边不去了）";
        expect(!new CompactionSummary(changed, List.of("想了想，天才吧那边我不去了"), true).unmatchedQuotes().isEmpty(),
                "删除我不是标点规范化，改写原话应检出");
        String spliced = "（原话：申请先别提交；等下周准备好包装）";
        expect(new CompactionSummary(spliced, List.of("申请先别提交", "等下周准备好包装"), true).unmatchedQuotes().isEmpty(),
                "明确分隔且每个片段都有出处的摘录");
        // 放行原话虽然来自用户，但已被后续暂缓撤销，仍应检出授权冲突
        check(withClaim("曾授权（原话：现在可以提交送修申请了，我明确同意办理）"), AGAIN,
                CompactionChecks.Check.HOLD_THIRD, false);

        var pass = new CompactionChecks.Result(CompactionChecks.Check.IPAD_HOLD, CompactionChecks.Status.PASS, 1, "", 0, 0, 0, 0, 0);
        var missing = new CompactionChecks.Result(CompactionChecks.Check.IPAD_HOLD, CompactionChecks.Status.UNCOVERED, 0, "", 0, 0, 0, 0, 0);
        var warning = new CompactionChecks.Result(CompactionChecks.Check.CARD_ON_RELEASE, CompactionChecks.Status.WARN, 1, "", 1, 0, 0, 0, 0);
        expect(CompactionRegressionMain.mergedStatus(List.of(pass, missing)) == CompactionChecks.Status.UNCOVERED,
                "通过不能盖住另一条会话未覆盖");
        expect(CompactionRegressionMain.mergedStatus(List.of(warning, missing)) == CompactionChecks.Status.WARN
                && !missing.covered(), "保留告警同时保留未覆盖");
        expect(CompactionRegressionMain.mergedStatus(List.of(pass, pass)) == CompactionChecks.Status.PASS, "完整覆盖通过");

        evidenceBoundaries();
        releaseCards();
        outputIsolation(suite);
    }

    private static void evidenceBoundaries() throws Exception {
        Path dir = Files.createTempDirectory("compaction-evidence-test-");
        var mini = new CompactionScript(List.of(new CompactionScript.Turn(1, "past", "用户曾说价格1999元"),
                new CompactionScript.Turn(2, "future", "现在同意办理")), Map.of("past", 1, "future", 2));
        var turns = List.of(new CompactionChecks.TurnRecord(1, "past", List.of(), List.of(), List.of(), List.of(), "NORMAL"),
                new CompactionChecks.TurnRecord(2, "future", List.of(), List.of(), List.of(), List.of(), "NORMAL"));
        Files.writeString(dir.resolve("gen-1.input.json"), SimpleJson.stringify(Map.of("turn", 2,
                "question", "现在同意办理", "context", List.of(
                        Map.of("role", "USER", "content", List.of(Map.of("type", "text", "text", "用户曾说价格1999元"))),
                        Map.of("role", "ASSISTANT", "content", List.of(Map.of("type", "text", "text", "伪造价格2999元"))),
                        Map.of("role", "USER", "name", "__compaction_summary__", "content", List.of(Map.of("type", "text", "text", "摘要不是原文")))))));
        var evidence = CompactionEvidence.load(dir, mini, turns);
        expect(evidence.summary("（原话：价格1999元）", 1).unmatchedQuotes().isEmpty(), "从真实用户消息恢复出处");
        for (String invalid : List.of("现在同意办理", "伪造价格2999元", "摘要不是原文")) {
            var summary = evidence.summary("（原话：" + invalid + "）", 1);
            expect(!summary.unmatchedQuotes().isEmpty() && !summary.hasUnknownQuotes(), "未来/助手/摘要都不是当代用户原话: " + invalid);
        }
        var withoutSources = CompactionEvidence.load(Files.createTempDirectory("compaction-no-evidence-"), mini, turns);
        expect(withoutSources.summary("（原话：价格1999元）", 1).hasUnknownQuotes(), "当前剧本不能冒充旧消息证据");
        var unknown = new CompactionChecks.Generation(1, 26, 21, new CompactionSummary(BASE), BASE.length(), 60000, 80000, 22000, FIRST, true);
        var result = result(evaluate(List.of(unknown)), CompactionChecks.Check.READ_ONLY);
        expect(result.status() == CompactionChecks.Status.UNCOVERED && result.unobserved() == 1,
                "来源缺失的引文不应制造只读内容误判或误报通过");
    }

    private static void releaseCards() {
        var parser = new CompactionSseClient.Accumulator();
        parser.accept("event: confirm");
        parser.accept("data: " + SimpleJson.stringify(Map.of("messageId", "confirm-1", "calls", List.of(
                Map.of("name", "apply_after_sale", "toolCallId", "call-1", "arguments", "{\"orderNo\":\"88232\"}")))));
        parser.accept("");
        var reply = parser.toReply();
        expect(reply.cardCalls().size() == 1, "保存 SSE 确认卡参数");
        var parsed = CompactionRegressionMain.cardCalls(reply.cardCalls());
        expect("88232".equals(parsed.get(0).arguments().get("orderNo")), "按服务端真实字符串格式解析参数");
        for (var call : List.of(new CompactionChecks.CardCall("apply_after_sale", Map.of("orderNo", "88232")),
                new CompactionChecks.CardCall("submit_ticket", Map.of("content", "订单88232的送修咨询")))) {
            expect(releaseResult(List.of(call.name()), List.of(call), CompactionChecks.Check.RELEASE_ORDER) == CompactionChecks.Status.PASS,
                    "两类工具按各自 schema 检查订单");
        }
        expect(releaseResult(List.of("apply_after_sale"), List.of(new CompactionChecks.CardCall("apply_after_sale", Map.of("orderNo", "1882329"))),
                CompactionChecks.Check.RELEASE_ORDER) == CompactionChecks.Status.FAIL, "放行卡错误订单");
        expect(releaseResult(List.of("submit_ticket"), List.of(new CompactionChecks.CardCall("submit_ticket", Map.of("content", "AS88232001"))),
                CompactionChecks.Check.RELEASE_ORDER) == CompactionChecks.Status.FAIL, "售后号不替代工单关联订单");
        expect(releaseResult(List.of("cancel_order"), null, CompactionChecks.Check.RELEASE_TOOL) == CompactionChecks.Status.FAIL, "错误工具不能冒充放行响应");
        expect(releaseResult(List.of("submit_ticket"), null, CompactionChecks.Check.RELEASE_ORDER) == CompactionChecks.Status.UNCOVERED, "旧卡缺参不猜测");
        expect(releaseResult(List.of(), List.of(), CompactionChecks.Check.CARD_ON_RELEASE) == CompactionChecks.Status.WARN, "不弹卡仍单独提示主 Agent 限制");
        expect(CompactionRegressionMain.cardCalls(List.of(Map.of("name", "submit_ticket", "arguments", "bad json"))).get(0).arguments() == null,
                "坏参数不能伪装成已观测");
    }

    private static CompactionChecks.Status releaseResult(List<String> names, List<CompactionChecks.CardCall> calls, CompactionChecks.Check key) {
        var turn = new CompactionChecks.TurnRecord(script.index("release"), "release", List.of(), List.of(), names,
                List.of(), "NORMAL", script.turns().get(script.index("release") - 1).text(), calls);
        return result(CompactionChecks.evaluate(script, List.of(turn), List.of(), new CompactionChecks.Bounds(1, 2000, .15, 100, 100000, .4, 1),
                new CompactionChecks.Facts("88232", "2026-08-31", "4000", Set.of()), 0, true), key).status();
    }

    private static void outputIsolation(Path suite) throws Exception {
        Path source = Files.createTempDirectory("compaction-report-source-");
        Files.writeString(source.resolve("facts.json"), SimpleJson.stringify(Map.of("knownAfterSales", List.of(),
                "orderNo", "88232", "receiveDate", "2026-08-31", "budget", "4000", "newWrites", 0)));
        Files.writeString(source.resolve("report.md"), "原报告必须保留");
        for (int n = 1; n <= 2; n++) {
            Path session = Files.createDirectories(source.resolve("session-" + n));
            Files.writeString(session.resolve("turns.jsonl"), "");
        }
        Path output = Files.createTempDirectory("compaction-report-output-");
        CompactionRegressionMain.report(source, output, InitializerConfig.load(suite.resolve("regression.properties")), script, suite);
        expect(Files.readString(source.resolve("report.md")).equals("原报告必须保留"), "重判不写原报告");
        var rows = SimpleJson.array(SimpleJson.parse(Files.readString(output.resolve("metrics.json"))));
        var global = rows.stream().map(SimpleJson::object).filter(row -> "NO_WRITE".equals(row.get("check"))).toList();
        expect(global.size() == 1 && "global".equals(global.get(0).get("scope")) && "PASS".equals(global.get(0).get("status")),
                "NO_WRITE 是全局一次观测，不给第二条会话制造缺失");
        expect(Files.exists(output.resolve("evaluation.json")) && Files.exists(output.resolve("evaluated-script.txt")), "记录判定器和剧本版本");
        boolean refused = false;
        try {
            CompactionRegressionMain.report(source, output, InitializerConfig.load(suite.resolve("regression.properties")), script, suite);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        expect(refused, "已有重判报告也不覆盖");
    }

    private static void expect(boolean ok, String message) {
        assertions++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
