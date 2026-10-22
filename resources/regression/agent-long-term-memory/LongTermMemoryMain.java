/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 长期记忆测评的命令行入口
 * 提供七轮偏好测评、自然聊天测试和指定条目撤回等运行方式
 * 通过已启动的聊天服务触发记忆变更，以只读数据库查询采集前后记录
 */
public final class LongTermMemoryMain {
    private static final String[] QUESTIONS = {
        "我一直喜欢小屏手机，平时单手用着方便。帮我记住这个偏好，今天先不用推荐具体型号。",
        "你还记得我选手机时，对屏幕大小有什么偏好吗？不知道就直说，先不用推荐型号。",
        "今天只是帮朋友临时挑手机，他这次的预算是三千元。帮我整理一下最近的对话记忆，告诉我有没有新增需要长期记住的内容，先不用推荐型号。",
        "我现在选手机更喜欢大屏了，看字和视频都舒服，之前喜欢小屏的偏好已经变了。请更新你保存的这条记忆，先不用推荐型号。",
        "你还记得我选手机时，对屏幕大小有什么偏好吗？不知道就直说，先不用推荐型号。",
        "请删掉你记住的我对手机屏幕大小的偏好，其他关于我的信息保留。请现在处理。",
        "你还记得我选手机时，对屏幕大小有什么偏好吗？如果没保存，就说不知道，不要猜。"
    };
    private static final String[] NAMES = {"新增稳定事实", "新会话召回", "临时事实不记", "修改事实", "新会话召回新值", "指定撤回", "撤回后新会话检查"};

    public static void main(String[] args) throws Exception {
        Path suite = Path.of(args[0]);
        String mode = args.length > 1 ? args[1] : "--plan";
        if (mode.equals("--self-test")) { selfTest(); return; }
        if (mode.equals("--natural-plan")) { NaturalMemoryCheck.printPlan(); return; }
        if (mode.equals("--plan")) {
            for (int i = 0; i < QUESTIONS.length; i++) System.out.println((i + 1) + ". " + NAMES[i] + "：" + QUESTIONS[i]);
            return;
        }
        if (!List.of("--inspect", "--run", "--run-natural", "--cleanup-legacy", "--cleanup-memory", "--verify-forgotten").contains(mode)) throw new IllegalArgumentException("使用 --plan / --inspect / --run / --natural-plan / --run-natural / --cleanup-legacy / --cleanup-memory ID 正文 / --verify-forgotten / --self-test");
        if (mode.equals("--cleanup-memory") && args.length != 4) throw new IllegalArgumentException("指定撤回必须提供条目 ID 与完整正文");
        InitializerConfig config = InitializerConfig.load(suite.resolve("regression.properties"));
        Path output = suite.resolve("artifacts/" + now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(output);
        System.out.println("产物目录：" + output);
        List<Map<String, Object>> turns = new ArrayList<>(), checks = new ArrayList<>();
        try (StarlineHttpClient http = new StarlineHttpClient(config); JdbcClient jdbc = new JdbcClient(config, "database")) {
            String user = http.login(config.require("auth.username"), config.require("auth.password")).userId();
            if (user == null || user.isBlank()) throw new IllegalStateException("登录响应缺少 userId");
            String uid = JdbcClient.literal(user);
            Map<String, Object> baseline = snapshot(jdbc, uid, "0", null);
            write(output.resolve("baseline.json"), baseline);
            write(output.resolve("baseline-full-memory.json"), rows(jdbc, "select * from t_agent_memory where user_id=" + uid + " order by id"));
            if (mode.equals("--run-natural")) {
                NaturalMemoryCheck.run(config, output, http, jdbc, uid, baseline);
                return;
            }
            if (mode.equals("--cleanup-legacy")) {
                LegacyMemoryCleanup.run(config, output, http, jdbc, uid, baseline);
                return;
            }
            if (mode.equals("--cleanup-memory")) {
                LegacyMemoryCleanup.runSpecific(config, output, http, jdbc, uid, baseline, args[2], args[3]);
                return;
            }
            if (mode.equals("--verify-forgotten")) {
                int result = MemoryRecallCheck.run(config, output, http, jdbc, uid, baseline);
                if (result != 0) System.exit(result);
                return;
            }
            List<Map<String, Object>> memories = objects(baseline.get("memory"));
            List<Map<String, Object>> pending = rows(jdbc, "select m.id,m.conversation_id,m.content from t_agent_message m join t_agent_memory_control c on c.user_id=m.user_id where m.user_id=" + uid
                    + " and m.role='user' and m.deleted=0 and m.create_time>=c.create_time and m.id>coalesce((select max(to_message_id) from t_agent_memory_extraction where user_id=" + uid + " and status in ('WRITTEN','NOOP','DROPPED')),'0') order by m.id");
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("scenarioVersion", "phone-preference-v2"); manifest.put("startedAt", now().toString()); manifest.put("userId", user); manifest.put("baseUrl", http.baseUrl());
            manifest.put("configuredModel", config.get("agent.chat.model", "unknown"));
            manifest.put("modelEvidence", "来自本地 application.yaml，未冒充运行中进程的有效配置");
            manifest.put("configuredWindowChars", config.get("agent.memory.context-window-chars", "unknown"));
            manifest.put("pendingBefore", pending); manifest.put("questions", List.of(QUESTIONS)); manifest.put("sourceSha256", fingerprints());
            write(output.resolve("run.json"), manifest);
            write(output.resolve("prompts.json"), rows(jdbc, "select a.id,a.name,p.slot_key,p.content from t_agent_profile a join t_agent_prompt p on p.agent_id=a.id where a.active=1 and a.deleted=0 and p.deleted=0 and p.slot_key like 'AGENT_MEMORY_%'"));
            System.out.println("生效记忆 " + active(memories).size() + " 条，待抽取消息 " + pending.size() + " 条");
            if (mode.equals("--inspect")) return;
            if (active(memories).stream().anyMatch(LongTermMemoryMain::target)) throw new IllegalStateException("已存在手机屏幕偏好记忆，停止以免覆盖旧数据");
            if (pending.size() > 5 || objects(baseline.get("extractions")).stream().anyMatch(e -> "PROCESSING".equals(e.get("status")))) throw new IllegalStateException("存在较多积压或在飞抽取，停止以免混入其他实验");
            if (active(memories).stream().mapToInt(m -> text(m, "content").length()).sum() > 1000) throw new IllegalStateException("存量接近容量下限，停止以免触发淘汰");
            String baselineExtraction = objects(baseline.get("extractions")).stream().map(m -> text(m, "id")).max(String::compareTo).orElse("0");
            MemorySseClient sse = new MemorySseClient();
            String conversation = null;
            List<Map<String, Object>> previous = memories;
            List<String> conversations = new ArrayList<>();
            for (int i = 0; i < QUESTIONS.length; i++) {
                int round = i + 1;
                if (List.of(1, 2, 4, 5, 7).contains(round)) conversation = null;
                System.out.println("开始第 " + round + "/7 轮：" + NAMES[i]);
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("round", round); turn.put("name", NAMES[i]); turn.put("question", QUESTIONS[i]); turn.put("freshConversation", conversation == null); turns.add(turn);
                Map<String, Object> response = sse.askObserved(http, QUESTIONS[i], conversation, config.getInt("turn.timeout-seconds", 180), output.resolve("turn-" + round + ".sse"));
                turn.putAll(response); conversation = text(response, "conversationId");
                check(checks, "第 " + round + " 轮回复完整成功", Boolean.TRUE.equals(response.get("replyComplete")), text(response,"messageStatus") + " " + response.get("errors"));
                if (!conversations.contains(conversation)) conversations.add(conversation);
                String conv = JdbcClient.literal(conversation);
                String requestId = jdbc.queryRows("select reply_to_message_id from t_agent_message where user_id=" + uid + " and id=" + JdbcClient.literal(text(response, "messageId"))).get(0).get(0);
                if (requestId.isBlank()) throw new IllegalStateException("缺少本轮用户消息 ID");
                turn.put("requestMessageId", requestId);
                boolean requiresExtraction = List.of(1, 3, 4, 6).contains(round);
                long deadline = System.nanoTime() + config.getInt("probe.timeout-seconds", 30) * 1_000_000_000L;
                Map<String, Object> snap;
                List<Map<String, Object>> covering;
                do {
                    snap = snapshot(jdbc, uid, baselineExtraction, conv);
                    covering = covering(objects(snap.get("extractions")), requestId);
                    if (!objects(snap.get("state")).isEmpty() && (!requiresExtraction || !covering.isEmpty())) break;
                    Thread.sleep(250);
                } while (System.nanoTime() < deadline);
                write(output.resolve("snapshot-" + round + ".json"), snap); turn.put("coveringExtractions", covering);
                List<Map<String, Object>> current = objects(snap.get("memory"));
                evaluate(round, response, previous, current, covering, checks);
                check(checks, "第 " + round + " 轮会话状态已保存", !objects(snap.get("state")).isEmpty(), "snapshot-" + round + ".json");
                check(checks, "第 " + round + " 轮记忆块未写入会话状态", objects(snap.get("state")).stream().noneMatch(s -> SimpleJson.stringify(s.get("payload")).contains("__user_memory__")), "按消息名检查");
                write(output.resolve("turns.json"), turns); write(output.resolve("checks.json"), checks); report(output, turns, checks, null);
                System.out.println("完成第 " + round + " 轮（" + text(response,"messageStatus") + "）：" + text(response, "answer").replace('\n', ' '));
                previous = current;
            }
            List<Map<String, Object>> finalMemory = rows(jdbc, "select * from t_agent_memory where user_id=" + uid + " order by id");
            check(checks, "原有生效记忆保持不变", active(memories).stream().allMatch(old -> finalMemory.stream().anyMatch(n -> Objects.equals(old.get("id"), n.get("id")) && Objects.equals(old.get("content"), n.get("content")) && n.get("invalid_at") == null)), "按基线 ID 与正文核对");
            String ids = String.join(",", conversations.stream().map(JdbcClient::literal).toList());
            List<Map<String, Object>> compactions = rows(jdbc, "select id,conversation_id,generation from t_agent_context_compaction where user_id=" + uid + " and conversation_id in (" + ids + ")");
            write(output.resolve("compactions.json"), compactions);
            check(checks, "测评会话未发生摘要压缩", compactions.isEmpty(), "未用摘要指标判断长期记忆");
            checks.add(Map.of("name", "后台门槛边界、CLEAR、容量合并与淘汰的独立验证", "status", "UNCOVERED", "evidence", "以显式整理为主；积压可能触发后台抽取，但未独立验证门槛边界；未清空 admin 全部记忆"));
            write(output.resolve("final-memory.json"), finalMemory); write(output.resolve("checks.json"), checks); report(output, turns, checks, null);
        } catch (Exception e) {
            if (mode.equals("--run-natural")) {
                write(output.resolve("natural-error.json"), Map.of("status", "INCOMPLETE", "errorType", e.getClass().getSimpleName(), "observedAt", now().toString()));
                throw e;
            }
            write(output.resolve("turns.json"), turns);
            checks.add(Map.of("name", "运行完成", "status", "UNKNOWN", "evidence", e.getClass().getSimpleName()));
            write(output.resolve("checks.json"), checks);
            report(output, turns, checks, e.getClass().getSimpleName() + ": " + e.getMessage()); throw e;
        }
        if (checks.stream().anyMatch(c -> "FAIL".equals(c.get("status")))) System.exit(1);
        if (checks.stream().anyMatch(c -> "UNKNOWN".equals(c.get("status")))) System.exit(2);
    }
    static void evaluate(int round, Map<String, Object> response, List<Map<String, Object>> previous, List<Map<String, Object>> current, List<Map<String, Object>> covering, List<Map<String, Object>> checks) {
        List<Map<String, Object>> facts = active(current).stream().filter(LongTermMemoryMain::target).toList();
        String answer = text(response, "answer");
        boolean settled = covering.stream().anyMatch(e -> List.of("WRITTEN", "NOOP").contains(e.get("status")));
        if (List.of(1, 3, 4, 6).contains(round)) check(checks, "第 " + round + " 轮抽取已覆盖请求", settled, SimpleJson.stringify(covering));
        switch (round) {
            case 1 -> check(checks, "ADD：小屏偏好生效", settled && facts.size() == 1 && smallScreen(text(facts.get(0), "content")), SimpleJson.stringify(facts));
            case 2 -> checks.add(Map.of("name", "跨会话召回小屏偏好（人工核对）", "status", "UNKNOWN", "evidence", answer));
            case 3 -> check(checks, "NOOP：本轮台账无变更且全量记忆未变", settled && covering.stream().anyMatch(e -> "NOOP".equals(e.get("status"))) && previous.equals(current), "要求前后全量记忆快照相等且台账 NOOP；失败不单独证明朋友预算被误写，需人工读新增正文");
            case 4 -> check(checks, "SUPERSEDE：旧小屏偏好指向新大屏偏好", settled && facts.size() == 1 && largeScreen(text(facts.get(0), "content")) && current.stream().anyMatch(old -> target(old) && smallScreen(text(old,"content")) && old.get("invalid_at") != null && Objects.equals(old.get("superseded_by"), facts.get(0).get("id"))), SimpleJson.stringify(current.stream().filter(LongTermMemoryMain::target).toList()));
            case 5 -> checks.add(Map.of("name", "跨会话召回大屏偏好（人工核对）", "status", "UNKNOWN", "evidence", answer));
            case 6 -> check(checks, "RETRACT：大屏偏好失效且没有取代者", settled && facts.isEmpty() && current.stream().anyMatch(m -> target(m) && largeScreen(text(m,"content")) && m.get("invalid_at") != null && m.get("superseded_by") == null), SimpleJson.stringify(current.stream().filter(LongTermMemoryMain::target).toList()));
            case 7 -> {
                check(checks, "撤回后记忆表无屏幕偏好", facts.isEmpty(), SimpleJson.stringify(facts));
                checks.add(Map.of("name", "撤回后回答是否承认未知（人工核对）", "status", "UNKNOWN", "evidence", answer));
            }
            default -> throw new IllegalArgumentException();
        }
    }
    static Map<String, Object> snapshot(JdbcClient jdbc, String uid, String from, String conv) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("observedAt", now().toString());
        result.put("memory", rows(jdbc, "select id,content,source_type,invalid_at,superseded_by,create_time from t_agent_memory where user_id=" + uid + " order by id"));
        result.put("control", rows(jdbc, "select * from t_agent_memory_control where user_id=" + uid));
        result.put("extractions", rows(jdbc, "select * from t_agent_memory_extraction where user_id=" + uid + " and id>" + JdbcClient.literal(from) + " order by id"));
        result.put("state", conv == null ? List.of() : rows(jdbc, "select * from t_agent_state where user_id=" + uid + " and session_id=" + conv)); return result;
    }
    static List<Map<String, Object>> covering(List<Map<String, Object>> rows, String request) { return rows.stream().filter(e -> List.of("WRITTEN", "NOOP", "DROPPED").contains(e.get("status")) && text(e,"from_message_id").compareTo(request)<=0 && text(e,"to_message_id").compareTo(request)>=0).toList(); }
    static List<Map<String, Object>> active(List<Map<String, Object>> rows) { return rows.stream().filter(m -> m.get("invalid_at") == null).toList(); }
    static boolean target(Map<String, Object> m) { String s = text(m,"content"); return s.contains("手机") && (s.contains("小屏") || s.contains("大屏") || s.contains("屏幕") || s.contains("尺寸")); }
    static boolean smallScreen(String s) { return s.contains("小屏") || s.contains("小尺寸"); }
    static boolean largeScreen(String s) { return s.contains("大屏") || s.contains("大尺寸"); }
    static String text(Map<String, Object> m, String key) { return Objects.toString(m.get(key), ""); }
    static List<Map<String, Object>> objects(Object v) { return ((List<?>) v).stream().map(SimpleJson::object).toList(); }
    static List<Map<String, Object>> rows(JdbcClient jdbc, String sql) throws Exception { return jdbc.queryRows("select row_to_json(q)::text from (" + sql + ") q").stream().map(r -> SimpleJson.object(SimpleJson.parse(r.get(0)))).toList(); }
    static void write(Path path, Object value) throws Exception { Files.writeString(path, SimpleJson.stringify(value)); }
    static void check(List<Map<String, Object>> checks, String name, boolean ok, String evidence) { checks.add(Map.of("name",name,"status",ok?"PASS":"FAIL","evidence",evidence)); }
    static ZonedDateTime now() { return ZonedDateTime.now(ZoneId.of("Asia/Shanghai")); }
    static Map<String, String> fingerprints() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Path dir = Path.of("agent/src/main/java/com/nageoffer/ai/starlineagent/agent/memory");
        for (String name : List.of("AgentMemoryJudge.java","AgentMemoryPipeline.java","AgentMemoryRepository.java","AgentUserMemoryMiddleware.java","AgentMemoryProperties.java")) result.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(name)))));
        return result;
    }
    static void report(Path output, List<Map<String, Object>> turns, List<Map<String, Object>> checks, String error) throws Exception {
        StringBuilder md = new StringBuilder("# 长期记忆短测评\n\n7 轮计划，5 个会话。只读数据库核证，记忆变更均由真实聊天服务处理。\n\n");
        if (error != null) md.append("运行未完成：").append(error).append("\n\n");
        md.append("| 轮次 | 检查目的 | 会话 | 回复状态 | 回答 |\n|---|---|---|---|---|\n");
        for (var t : turns) md.append("| ").append(t.get("round")).append(" | ").append(t.get("name")).append(" | ").append(text(t,"conversationId")).append(" | ").append(text(t,"messageStatus")).append(" | ").append(text(t,"answer").replace("\n","<br>").replace("|","\\|")).append(" |\n");
        md.append("\n| 断言 | 状态 |\n|---|---|\n");
        for (var c : checks) md.append("| ").append(c.get("name")).append(" | ").append(c.get("status")).append(" |\n");
        md.append("\n原始证据见 `turn-*.sse`、`snapshot-*.json`、`checks.json`、`run.json`。自然语言检查保留人工复核，UNKNOWN 和 UNCOVERED 不计通过。\n");
        Files.writeString(output.resolve("report.md"), md);
    }
    static void selfTest() {
        if (!target(Map.of("content", "用户喜欢小屏手机")) || target(Map.of("content", "用户戴眼镜"))) throw new AssertionError("场景定位错误");
        List<Map<String, Object>> failed = new ArrayList<>();
        evaluate(1, Map.of("answer", "已记住"), List.of(), List.of(), List.of(), failed);
        if (failed.stream().anyMatch(c -> "PASS".equals(c.get("status")))) throw new AssertionError("口头确认不能代替数据库证据");
        String ok = "event:meta\ndata:{\"conversationId\":\"1\"}\n\nevent:message\ndata:{\"type\":\"reasoning\",\"delta\":\"小屏\"}\n\nevent:message\ndata:{\"delta\":\"不知道\"}\n\nevent:finish\ndata:{\"messageId\":\"2\",\"messageStatus\":\"NORMAL\"}\n\nevent:done\ndata:{}\n\n";
        if (!"不知道".equals(MemorySseClient.parse(ok).get("answer"))) throw new AssertionError("思考内容不能算召回答案");
        for (String broken : List.of(ok.replace("event:done","event:hint"),ok.replace("NORMAL","FAILED"))) {
            try { MemorySseClient.parse(broken); throw new AssertionError("失败流被误判成功"); } catch (IllegalStateException expected) { }
        }
        var interrupted = MemorySseClient.parseObserved(ok.replace("NORMAL", "INTERRUPTED"));
        if (Boolean.TRUE.equals(interrupted.get("replyComplete"))) throw new AssertionError("业务中断不能算回复成功");
        var error = MemorySseClient.parseObserved(ok.replace("\"delta\":\"不知道\"", "\"type\":\"error\",\"delta\":\"中断\""));
        if (Boolean.TRUE.equals(error.get("replyComplete")) || !"".equals(error.get("answer"))) throw new AssertionError("错误提示不能作为模型答案");
        var dropped = List.of(Map.<String,Object>of("status","DROPPED","from_message_id","10","to_message_id","20"));
        if (covering(dropped,"15").isEmpty() || !covering(dropped,"21").isEmpty()) throw new AssertionError("抽取覆盖判定错误");
        System.out.println("自检通过：SSE 截断、异常状态、思考隔离、抽取覆盖边界");
    }
}
