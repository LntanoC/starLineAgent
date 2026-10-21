/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * 按固定剧本运行长会话，拒绝确认卡，并采集逐代摘要供规则检查
 * 默认连接已启动的服务执行剧本
 * --evaluate 读取已有运行或离线重放产物，检查结果写入新的输出目录
 */
public final class CompactionRegressionMain {

    // 摘要消息名称须与 AgentContextCompactor.SUMMARY_NAME 保持一致
    private static final String SUMMARY_NAME = "__compaction_summary__";
    private static final String COMPACTION_SLOT = "AGENT_CONTEXT_COMPACTION";
    // AgentScope 使用此键存储 AgentState，PgAgentStateStore 将其写入 state_key
    private static final String STATE_KEY = "agent_state";
    private static final int MAX_DENIALS = 3;
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CompactionRegressionMain() {
    }

    public static void main(String[] args) {
        try {
            Map<String, String> arguments = parseArguments(args);
            Path suiteDir = Path.of(require(arguments, "suite-dir")).toAbsolutePath().normalize();
            InitializerConfig config = InitializerConfig.load(suiteDir.resolve("regression.properties"));
            CompactionScript script = CompactionScript.load(suiteDir.resolve(config.require("script.file")));
            String evaluate = arguments.get("evaluate");
            Path runDir = evaluate == null
                    ? runLive(suiteDir, config, script, arguments)
                    : Path.of(evaluate).toAbsolutePath().normalize();
            Path outputDir = evaluate == null ? runDir : Path.of(arguments.getOrDefault("output-dir",
                    runDir + "-evaluated-" + RUN_ID.format(LocalDateTime.now()))).toAbsolutePath().normalize();
            if (evaluate != null && (outputDir.startsWith(runDir) || Files.exists(outputDir))) {
                throw new IllegalArgumentException("重判输出目录必须是源产物之外的新目录：" + outputDir);
            }
            // 优先使用运行时保存的剧本快照
            // 旧产物没有快照时，当前剧本仅用于定位轮次，引文出处仍以实际用户消息为准
            if (Files.isRegularFile(runDir.resolve("script.txt"))) {
                script = CompactionScript.load(runDir.resolve("script.txt"));
            }
            boolean failed = report(runDir, outputDir, config, script, suiteDir);
            System.exit(failed ? 1 : 0);
        } catch (Exception ex) {
            System.err.println("[compaction] FAILED: " + describe(ex));
            if (Boolean.parseBoolean(System.getenv().getOrDefault("RAGENT_REGRESSION_DEBUG", "false"))) {
                ex.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static Path runLive(Path suiteDir, InitializerConfig config, CompactionScript script,
                                Map<String, String> arguments) throws Exception {
        String resume = arguments.get("resume");
        Path runDir = resume == null
                ? suiteDir.resolve("artifacts").resolve(RUN_ID.format(LocalDateTime.now()))
                : Path.of(resume).toAbsolutePath().normalize();
        Files.createDirectories(runDir);
        Path savedScript = runDir.resolve("script.txt");
        if (resume == null) {
            Files.copy(suiteDir.resolve(config.require("script.file")), savedScript);
        } else if (Files.exists(savedScript) && !CompactionScript.load(savedScript).equals(script)) {
            throw new IllegalArgumentException("续跑剧本与已保存版本不同，拒绝混用轮次");
        }
        int sessions = Integer.parseInt(arguments.getOrDefault("sessions", config.get("run.sessions", "2")));
        int limit = Integer.parseInt(arguments.getOrDefault("limit", String.valueOf(script.turns().size())));
        int steadyGenerations = Integer.parseInt(arguments.getOrDefault("steady-generations",
                config.get("run.steady-generations", "0")));
        // 续跑从每条会话末尾连续失败的第一轮开始，保留此前已完成的轮次和摘要
        List<Resume> resumes = new ArrayList<>();
        if (resume != null) {
            try (Stream<Path> stream = Files.list(runDir)) {
                for (Path dir : stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("session-")).sorted().toList()) {
                    resumes.add(Resume.load(dir, script));
                }
            }
            if (resumes.isEmpty()) {
                throw new IllegalStateException("续跑目录里没有会话产物: " + runDir);
            }
            sessions = resumes.size();
        }
        try (RagentHttpClient http = new RagentHttpClient(config);
             JdbcClient platform = new JdbcClient(config, "database");
             JdbcClient biz = new JdbcClient(config, "biz-database")) {
            String userId = http.login(config.require("auth.username"), config.require("auth.password")).userId();
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException("登录响应缺少 userId，无法定位会话状态");
            }
            Map<String, Object> facts = new LinkedHashMap<>(preflight(suiteDir, config, platform));
            if (Files.isRegularFile(savedScript)) {
                facts.put("scriptSha256", sha256(Files.readAllBytes(savedScript)));
            }
            facts.putAll(businessFacts(config, biz, userId));
            long baseline = writes(biz, userId);
            log("产物目录 " + runDir + "，" + sessions + " 条会话 × " + limit + " 轮");

            ExecutorService pool = Executors.newFixedThreadPool(sessions);
            List<Future<?>> futures = new ArrayList<>();
            for (int number = 1; number <= sessions; number++) {
                Session session = new Session(number, runDir.resolve("session-" + number), script, limit,
                        steadyGenerations, new CompactionSseClient(http, config), platform, config, userId);
                Resume start = resumes.isEmpty() ? Resume.FRESH : resumes.get(number - 1);
                futures.add(pool.submit(() -> {
                    session.run(start);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();
            facts.put("newWrites", writes(biz, userId) - baseline);
            Files.writeString(runDir.resolve("facts.json"), SimpleJson.stringify(facts), StandardCharsets.UTF_8);
        }
        return runDir;
    }

    /**
     * 逐槽位核对激活人设的提示词与数据集文件
     * 发现不一致时停止运行，避免对不同版本的提示词给出结论
     */
    private static Map<String, Object> preflight(Path suiteDir, InitializerConfig config, JdbcClient platform)
            throws Exception {
        if (!config.getBoolean("agent.memory.summary-enabled", true)) {
            throw new IllegalStateException("application.yaml 关掉了 agent.memory.summary-enabled，没有摘要可测");
        }
        String profileName = config.require("bit.profile-name");
        List<List<String>> profiles = platform.queryRows("SELECT id, name FROM t_agent_profile"
                + " WHERE active = 1 AND deleted = 0");
        if (profiles.size() != 1 || !profileName.equals(profiles.get(0).get(1))) {
            throw new IllegalStateException("当前激活的人设不是「" + profileName + "」: " + profiles);
        }
        String profileId = profiles.get(0).get(0);
        Path promptsDir = suiteDir.resolve(config.require("bit.prompts-dir")).normalize();
        Properties manifest = new Properties();
        try (Reader reader = Files.newBufferedReader(promptsDir.resolveSibling("agent-profile.properties"),
                StandardCharsets.UTF_8)) {
            manifest.load(reader);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("profileId", profileId);
        for (String key : manifest.stringPropertyNames()) {
            if (!key.startsWith("prompt.")) {
                continue;
            }
            String slot = key.substring("prompt.".length());
            String expected = Files.readString(promptsDir.resolveSibling(manifest.getProperty(key).trim()),
                    StandardCharsets.UTF_8);
            List<List<String>> rows = platform.queryRows("SELECT content FROM t_agent_prompt WHERE deleted = 0"
                    + " AND agent_id = " + JdbcClient.literal(profileId) + " AND slot_key = " + JdbcClient.literal(slot));
            String actual = rows.isEmpty() ? null : rows.get(0).get(0);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("库里槽位 " + slot + " 与 " + manifest.getProperty(key)
                        + " 不一致，先用初始化器或接口同步再跑");
            }
            if (COMPACTION_SLOT.equals(slot)) {
                facts.put("compactionPromptMd5", md5(expected));
                facts.put("compactionPromptChars", expected.length());
            }
        }
        if (!facts.containsKey("compactionPromptMd5")) {
            throw new IllegalStateException("数据集没有覆盖 " + COMPACTION_SLOT + "，被测的不是比特严选那份压缩提示词");
        }
        return facts;
    }

    private static Map<String, Object> businessFacts(InitializerConfig config, JdbcClient biz, String userId)
            throws Exception {
        String orderNo = config.require("bit.order-no");
        List<List<String>> order = biz.queryRows("SELECT to_char(receive_time AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD')"
                + " FROM t_order WHERE order_no = " + JdbcClient.literal(orderNo)
                + " AND user_id = " + JdbcClient.literal(userId));
        if (order.isEmpty() || order.get(0).get(0).isEmpty()) {
            throw new IllegalStateException("业务库里没有挂在当前账号名下、已签收的订单 " + orderNo);
        }
        List<String> afterSales = new ArrayList<>();
        for (List<String> row : biz.queryRows("SELECT a.after_sale_no FROM t_after_sale a"
                + " JOIN t_order o ON o.order_no = a.order_no WHERE o.user_id = " + JdbcClient.literal(userId))) {
            afterSales.add(row.get(0));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderNo", orderNo);
        facts.put("receiveDate", order.get(0).get(0));
        facts.put("budget", config.require("bit.budget"));
        facts.put("knownAfterSales", afterSales);
        return facts;
    }

    /**
     * 当前剧本拒绝所有确认卡，运行前后的工单和售后单总数应保持一致
     */
    private static long writes(JdbcClient biz, String userId) throws Exception {
        String user = JdbcClient.literal(userId);
        return biz.queryLong("SELECT (SELECT count(*) FROM t_ticket WHERE user_id = " + user + ")"
                + " + (SELECT count(*) FROM t_after_sale a JOIN t_order o ON o.order_no = a.order_no"
                + " WHERE o.user_id = " + user + ")");
    }

    /**
     * 保存续跑所需的会话 ID、起始轮次、已有摘要代数和稳态期观测代数
     * 加载时从 turns.jsonl 移除末尾连续失败的记录
     * 续跑前须手动清理数据库中对应的消息和状态，此处只修改本地轮次记录
     */
    private record Resume(String conversationId, int startTurn, int seen, int steady) {

        static final Resume FRESH = new Resume(null, 1, 0, 0);

        static Resume load(Path dir, CompactionScript script) throws IOException {
            Path file = dir.resolve("turns.jsonl");
            List<Map<String, Object>> records = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(SimpleJson.object(SimpleJson.parse(line)));
                }
            }
            if (records.isEmpty()) {
                throw new IllegalStateException(dir + " 没有已跑过的轮次，不能续跑");
            }
            int cut = records.size();
            while (cut > 0 && !"NORMAL".equals(SimpleJson.string(records.get(cut - 1), "status"))) {
                cut--;
            }
            String conversationId = SimpleJson.string(records.get(0), "conversationId");
            int startTurn = cut == records.size()
                    ? SimpleJson.integer(records.get(cut - 1), "index", 0) + 1
                    : SimpleJson.integer(records.get(cut), "index", 0);
            StringBuilder kept = new StringBuilder();
            for (Map<String, Object> record : records.subList(0, cut)) {
                kept.append(SimpleJson.stringify(record)).append('\n');
            }
            Files.writeString(file, kept.toString(), StandardCharsets.UTF_8);
            int seen = 0;
            int steady = 0;
            int hold3 = script.index("hold3");
            for (int number = 1; Files.exists(dir.resolve("gen-" + number + ".json")); number++) {
                Map<String, Object> gen = SimpleJson.object(SimpleJson.parse(
                        Files.readString(dir.resolve("gen-" + number + ".json"), StandardCharsets.UTF_8)));
                seen = number;
                if (SimpleJson.integer(gen, "compactedThrough", 0) >= hold3) {
                    steady++;
                }
            }
            log(dir.getFileName() + " 续跑：会话 " + conversationId + "，裁掉第 " + startTurn + " 轮起的 "
                    + (records.size() - cut) + " 条失败记录，已有 " + seen + " 代，稳态期已观测 " + steady + " 代");
            return new Resume(conversationId, startTurn, seen, steady);
        }
    }

    /**
     * 执行一条独立会话，逐轮发送提问并拒绝确认卡
     * 等待状态落库后采集本轮新生成的摘要
     */
    private static final class Session {

        private final int number;
        private final Path dir;
        private final CompactionScript script;
        private final int limit;
        private final int steadyGenerations;
        private final CompactionSseClient sse;
        private final JdbcClient platform;
        private final String userId;
        private final Duration timeout;
        private final int maxAttempts;
        private final int probeAttempts;
        private final long probeInterval;
        private final long turnInterval;

        Session(int number, Path dir, CompactionScript script, int limit, int steadyGenerations,
                CompactionSseClient sse, JdbcClient platform, InitializerConfig config, String userId) {
            this.number = number;
            this.dir = dir;
            this.script = script;
            this.limit = limit;
            this.steadyGenerations = steadyGenerations;
            this.sse = sse;
            this.platform = platform;
            this.userId = userId;
            this.timeout = Duration.ofSeconds(config.getInt("turn.timeout-seconds", 900));
            this.maxAttempts = Math.max(1, config.getInt("turn.max-attempts", 2));
            this.probeAttempts = Math.max(1, config.getInt("probe.max-attempts", 10));
            this.probeInterval = config.getInt("probe.retry-interval-seconds", 2) * 1000L;
            this.turnInterval = config.getInt("turn.interval-seconds", 1) * 1000L;
        }

        /**
         * hold3 移出原始上下文后，开始统计稳态期摘要
         * 观测满 steadyGenerations 代后跳到末轮，省略后续重复的稳态比较
         */
        void run(Resume resume) throws Exception {
            Files.createDirectories(dir);
            List<CompactionScript.Turn> turns = script.turns().subList(resume.startTurn() - 1, limit);
            if (turns.isEmpty()) {
                log("会话 " + number + " 没有剩余轮次");
                return;
            }
            CompactionScript.Turn last = turns.get(turns.size() - 1);
            int hold3 = script.index("hold3");
            String conversationId = resume.conversationId();
            int seen = resume.seen();
            int steady = resume.steady();
            for (CompactionScript.Turn turn : turns) {
                if (steadyGenerations > 0 && steady >= steadyGenerations && turn != last) {
                    continue;
                }
                String before = conversationId == null ? null : contextJson(conversationId);
                Map<String, Object> outcome = play(turn, conversationId);
                conversationId = (String) outcome.get("conversationId");
                append(dir.resolve("turns.jsonl"), outcome);
                Set<String> context = awaitContext(conversationId, turn.text());
                int through = compactedThrough(context, turn.index());
                boolean firstOfTurn = true;
                for (List<String> row : platform.queryRows("SELECT generation, summary, summary_chars,"
                        + " material_chars, context_chars_before, context_chars_after FROM t_agent_context_compaction"
                        + " WHERE conversation_id = " + JdbcClient.literal(conversationId)
                        + " AND generation > " + seen + " ORDER BY generation")) {
                    seen = Integer.parseInt(row.get(0));
                    if (firstOfTurn && before != null) {
                        saveInput(seen, turn, before);
                    }
                    firstOfTurn = false;
                    if (through >= hold3) {
                        steady++;
                    }
                    Map<String, Object> gen = new LinkedHashMap<>();
                    gen.put("number", seen);
                    gen.put("turn", turn.index());
                    gen.put("compactedThrough", through);
                    gen.put("summaryChars", Integer.parseInt(row.get(2)));
                    gen.put("materialChars", Integer.parseInt(row.get(3)));
                    gen.put("before", Integer.parseInt(row.get(4)));
                    gen.put("after", Integer.parseInt(row.get(5)));
                    gen.put("summary", row.get(1));
                    Files.writeString(dir.resolve("gen-" + seen + ".json"), SimpleJson.stringify(gen), StandardCharsets.UTF_8);
                    Files.writeString(dir.resolve("gen-" + seen + ".md"), row.get(1), StandardCharsets.UTF_8);
                    log("会话 " + number + " 第 " + turn.index() + " 轮压出第 " + seen + " 代：" + row.get(2)
                            + " 字，原文压到第 " + through + " 轮");
                }
                log("会话 " + number + " 第 " + turn.index() + "/" + limit + " 轮 " + outcome.get("status")
                        + " 工具 " + outcome.get("tools") + (((List<?>) outcome.get("cardTools")).isEmpty() ? ""
                        : " 拒卡 " + outcome.get("cardTools")));
                if (steadyGenerations > 0 && steady >= steadyGenerations && turn != last) {
                    log("会话 " + number + " 稳态期已观测到 " + steady + " 代，跳到末轮");
                }
                Thread.sleep(turnInterval);
            }
            Files.writeString(dir.resolve("conversation.txt"), String.valueOf(conversationId), StandardCharsets.UTF_8);
        }

        /**
         * 读取发问前的上下文，供离线重放和引文来源恢复使用
         * 读取失败时记录原因并继续实跑，该代可能缺少重放素材或原文依据
         */
        private String contextJson(String conversationId) {
            try {
                List<List<String>> rows = platform.queryRows("SELECT (payload->'context')::text FROM t_agent_state"
                        + " WHERE user_id = " + JdbcClient.literal(userId)
                        + " AND session_id = " + JdbcClient.literal(conversationId)
                        + " AND state_key = " + JdbcClient.literal(STATE_KEY));
                return rows.isEmpty() || rows.get(0).get(0).isEmpty() ? null : rows.get(0).get(0);
            } catch (Exception ex) {
                log("会话 " + number + " 发问前读上下文快照失败，这一轮压出的摘要将无法重放: " + describe(ex));
                return null;
            }
        }

        /**
         * 压缩在轮次开始、追加用户消息后触发，输入由发问前的上下文和本轮问题组成
         * 保存这两部分，供离线重放调用生产压缩器
         * 同一轮只保存第一代摘要的输入，后续摘要没有对应快照，无法重放
         */
        private void saveInput(int generation, CompactionScript.Turn turn, String before) throws IOException {
            Files.writeString(dir.resolve("gen-" + generation + ".input.json"),
                    "{\"turn\":" + turn.index() + ",\"question\":" + SimpleJson.stringify(turn.text())
                            + ",\"context\":" + before + "}",
                    StandardCharsets.UTF_8);
        }

        /**
         * 拒绝本轮所有确认卡，并继续收集模型响应
         * 拒绝后若再次出现确认卡则继续处理，每轮最多拒绝 MAX_DENIALS 张确认卡
         */
        private Map<String, Object> play(CompactionScript.Turn turn, String conversationId) throws Exception {
            CompactionSseClient.Reply reply = null;
            List<String> errors = new ArrayList<>();
            for (int attempt = 1; attempt <= maxAttempts && reply == null; attempt++) {
                try {
                    reply = sse.ask(turn.text(), conversationId, timeout);
                } catch (IOException | IllegalStateException ex) {
                    errors.add("第 " + attempt + " 次发送中断: " + ex.getMessage());
                    // 问题已经落库时停止重发，避免同一句话重复进入上下文
                    if (conversationId != null && questionStored(conversationId, turn.text())) {
                        break;
                    }
                }
            }
            String resolved = reply == null || reply.conversationId() == null ? conversationId : reply.conversationId();
            if (resolved == null) {
                throw new IllegalStateException("会话 " + number + " 首轮没拿到 conversationId: " + errors);
            }
            Set<String> tools = new LinkedHashSet<>();
            List<String> results = new ArrayList<>();
            List<String> cardTools = new ArrayList<>();
            List<Map<String, Object>> cardCalls = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            String status = reply == null ? "BROKEN" : reply.messageStatus();
            for (int denials = 0; reply != null; denials++) {
                tools.addAll(reply.tools());
                results.addAll(reply.results());
                errors.addAll(reply.errors());
                answer.append(reply.answer());
                status = reply.messageStatus();
                if (reply.confirmMessageId() == null) {
                    break;
                }
                cardTools.addAll(reply.cardTools());
                cardCalls.addAll(reply.cardCalls());
                tools.addAll(reply.cardTools());
                if (denials >= MAX_DENIALS) {
                    errors.add("连拒 " + MAX_DENIALS + " 次仍在弹卡，会话停在待确认");
                    break;
                }
                answer.append("\n[已拒绝确认卡 ").append(reply.cardTools()).append("]\n");
                reply = sse.deny(resolved, reply.confirmMessageId(), timeout);
            }
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("index", turn.index());
            outcome.put("ref", turn.ref());
            outcome.put("question", turn.text());
            outcome.put("conversationId", resolved);
            outcome.put("status", status == null ? "UNKNOWN" : status);
            outcome.put("tools", List.copyOf(tools));
            outcome.put("cardTools", List.copyOf(cardTools));
            outcome.put("cardCalls", List.copyOf(cardCalls));
            outcome.put("errors", List.copyOf(errors));
            outcome.put("answer", answer.toString());
            outcome.put("results", List.copyOf(results));
            return outcome;
        }

        private boolean questionStored(String conversationId, String text) throws Exception {
            return platform.queryLong("SELECT count(*) FROM t_agent_message WHERE role = 'user'"
                    + " AND conversation_id = " + JdbcClient.literal(conversationId)
                    + " AND content = " + JdbcClient.literal(text)) > 0;
        }

        /**
         * 流结束后状态仍可能在异步写入
         * 等待读到本轮问题后，再确定原始上下文保留到了哪一轮
         */
        private Set<String> awaitContext(String conversationId, String text) throws Exception {
            Set<String> context = Set.of();
            for (int attempt = 0; attempt < probeAttempts; attempt++) {
                context = userTexts(platform, userId, conversationId);
                if (context.contains(text)) {
                    return context;
                }
                Thread.sleep(probeInterval);
            }
            log("会话 " + number + " 状态里一直没读到本轮问题，按现有状态判定: " + text);
            return context;
        }

        /**
         * 压缩只移除上下文前缀
         * 仍保留在原始上下文中的第一条问题之前，所有轮次都应已纳入摘要
         */
        private int compactedThrough(Set<String> context, int current) {
            for (CompactionScript.Turn turn : script.turns().subList(0, current)) {
                if (context.contains(turn.text())) {
                    return turn.index() - 1;
                }
            }
            return current;
        }
    }

    private static Set<String> userTexts(JdbcClient platform, String userId, String conversationId) throws Exception {
        Set<String> texts = new HashSet<>();
        for (List<String> row : platform.queryRows("SELECT c->>'text' FROM t_agent_state s,"
                + " LATERAL jsonb_array_elements(COALESCE(s.payload->'context', '[]'::jsonb)) m,"
                + " LATERAL jsonb_array_elements(COALESCE(m->'content', '[]'::jsonb)) c"
                + " WHERE s.user_id = " + JdbcClient.literal(userId)
                + " AND s.session_id = " + JdbcClient.literal(conversationId)
                + " AND m->>'role' = 'USER' AND COALESCE(m->>'name', '') <> " + JdbcClient.literal(SUMMARY_NAME)
                + " AND c->>'type' = 'text'")) {
            texts.add(row.get(0).strip());
        }
        return texts;
    }

    private static void append(Path file, Map<String, Object> record) throws IOException {
        Files.writeString(file, SimpleJson.stringify(record) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * 分别检查每条会话，再合并规则结果和覆盖情况
     * 任一会话出现硬性检查失败时，合并结果也记为失败
     * 同一会话的多代摘要存在继承关系，不作为独立样本计数
     */
    static boolean report(Path runDir, Path outputDir, InitializerConfig config, CompactionScript script,
                          Path suiteDir) throws Exception {
        Files.createDirectories(outputDir);
        if (Files.exists(outputDir.resolve("report.md"))) {
            throw new IllegalArgumentException("不覆盖已有报告：" + outputDir);
        }
        String version = saveEvaluator(outputDir, suiteDir, runDir, script, config);
        Files.writeString(outputDir.resolve("inputs-sha256.json"), SimpleJson.stringify(hashInputs(runDir)));
        if (!Files.isRegularFile(runDir.resolve("facts.json"))) {
            return incomplete(outputDir, "缺少 facts.json，无法恢复业务真值及运行完成状态");
        }
        Map<String, Object> facts = SimpleJson.object(SimpleJson.parse(Files.readString(runDir.resolve("facts.json"))));
        if (facts.get("scriptSha256") != null && Files.isRegularFile(runDir.resolve("script.txt"))
                && !facts.get("scriptSha256").equals(sha256(Files.readAllBytes(runDir.resolve("script.txt"))))) {
            throw new IllegalArgumentException("保存的剧本与 facts.json 中的哈希不一致");
        }
        List<String> known = strings(facts.get("knownAfterSales"));
        CompactionChecks.Facts checkFacts = new CompactionChecks.Facts(SimpleJson.string(facts, "orderNo"),
                SimpleJson.string(facts, "receiveDate"), SimpleJson.string(facts, "budget"), Set.copyOf(known));
        int newWrites = SimpleJson.integer(facts, "newWrites", -1);
        String replayOf = SimpleJson.string(facts, "replayOf");
        CompactionChecks.Bounds bounds = new CompactionChecks.Bounds(config.requireInt("bound.min-chars"),
                config.requireInt("bound.max-chars"), config.getDouble("bound.steady-growth-ratio", 0.15),
                config.getInt("bound.steady-growth-slack", 100), config.requireInt("agent.memory.context-window-chars"),
                config.getDouble("bound.max-after-ratio", 0.4), config.getInt("bound.min-generations", 5));
        StringBuilder out = new StringBuilder("# 记忆摘要规则回归报告\n\n源产物 ").append(runDir)
                .append("\n输出 ").append(outputDir).append("\n判定器 SHA-256 ").append(version)
                .append("\n压缩提示词 md5 ").append(facts.get("compactionPromptMd5"))
                .append("\n\n本报告只说明已实现的规则检查结果；不证明摘要语义完整或授权经过压缩后可靠继承。")
                .append("正确性与覆盖分开报告；未覆盖项不能解释为通过。\n");
        if (replayOf != null) {
            out.append("\n离线重放自 ").append(replayOf).append("；主 Agent 行为及业务库写入不判。\n");
        }
        Map<CompactionChecks.Check, List<CompactionChecks.Result>> merged = new EnumMap<>(CompactionChecks.Check.class);
        List<Map<String, Object>> metrics = new ArrayList<>();
        StringBuilder observations = new StringBuilder();
        List<Map<String, Object>> sourceCoverage = new ArrayList<>();
        List<Path> sessionDirs;
        try (Stream<Path> stream = Files.list(runDir)) {
            sessionDirs = stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("session-")).sorted().toList();
        }
        if (sessionDirs.isEmpty()) {
            return incomplete(outputDir, "产物中没有任何会话目录");
        }
        for (Path dir : sessionDirs) {
            List<CompactionChecks.TurnRecord> turns = loadTurns(dir);
            Path evidenceDir = dir;
            if (replayOf != null) {
                // 重放使用原实跑中的用户消息，-rN 后缀表示同一会话的第 N 次重放
                Path original = Path.of(replayOf).resolve(dir.getFileName().toString().replaceFirst("-r[0-9]+$", ""));
                if (Files.isDirectory(original)) {
                    evidenceDir = original;
                }
            }
            CompactionEvidence evidence = CompactionEvidence.load(evidenceDir, script, turns);
            sourceCoverage.add(Map.of("session", dir.getFileName().toString(), "source", evidenceDir.toString(),
                    "recoveredQuestions", evidence.recoveredQuestions(), "recordedTurns", turns.size(),
                    "sourceInputSha256", hashInputs(evidenceDir)));
            List<CompactionChecks.Generation> gens = loadGenerations(dir, script, turns, checkFacts.orderNo(), evidence);
            out.append("\n## ").append(dir.getFileName()).append("（").append(turns.size()).append(" 轮，")
                    .append(gens.size()).append(" 代）\n\n已恢复用户原文 ").append(evidence.recoveredQuestions())
                    .append(" 轮；来源 ").append(evidenceDir).append("\n\n")
                    .append("| 代 | 轮 | 压到 | 摘要字 | 素材字 | 压缩前 | 压缩后 |\n|---|---|---|---|---|---|---|\n");
            for (var gen : gens) {
                out.append("| ").append(gen.number()).append(" | ").append(gen.turn()).append(" | ")
                        .append(gen.compactedThrough()).append(" | ").append(gen.summaryChars()).append(" | ")
                        .append(gen.materialChars()).append(" | ").append(gen.before()).append(" | ")
                        .append(gen.after()).append(" |\n");
            }
            var evaluation = CompactionChecks.evaluateDetailed(script, turns, gens, bounds, checkFacts, -1, replayOf == null);
            for (var result : evaluation.results()) {
                if (result.check() == CompactionChecks.Check.NO_WRITE) {
                    // 业务库写入数属于整次运行，在会话循环结束后统一统计
                    continue;
                }
                merged.computeIfAbsent(result.check(), key -> new ArrayList<>()).add(result);
                metrics.add(metric(dir.getFileName().toString(), "session", result));
                if (result.status() != CompactionChecks.Status.PASS || !result.covered()) {
                    out.append("\n- ").append(result.status()).append(" ").append(result.check().label)
                            .append(result.covered() ? "" : "［覆盖不足］").append("：").append(result.detail()).append('\n');
                }
            }
            for (var observation : evaluation.observations()) {
                observations.append(SimpleJson.stringify(Map.of("session", dir.getFileName().toString(),
                        "check", observation.check().name(), "generation", observation.generation(),
                        "status", observation.status().name(), "detail", observation.detail()))).append('\n');
            }
        }
        if (replayOf == null) {
            var status = newWrites < 0 ? CompactionChecks.Status.UNCOVERED
                    : newWrites == 0 ? CompactionChecks.Status.PASS : CompactionChecks.Status.FAIL;
            var global = new CompactionChecks.Result(CompactionChecks.Check.NO_WRITE, status, newWrites < 0 ? 0 : 1,
                    newWrites < 0 ? "整次运行的业务库写入数未观测" : "整次运行新增工单/售后单 " + newWrites,
                    newWrites > 0 ? 1 : 0, 0, 0, 0, newWrites < 0 ? 1 : 0);
            merged.put(global.check(), List.of(global));
            metrics.add(metric("run", "global", global));
        }
        out.append("\n## 合并结论\n\n覆盖列为完整观测的会话数/应观测会话数；NO_WRITE 为全局 1 项。")
                .append("一条会话部分代数缺少引文证据也计为覆盖不足。FAIL/WARN 与覆盖不足可以同时存在。")
                .append("连续失败段不等于独立样本；摘要代数之间存在继承关系。\n\n")
                .append("| 结论 | 检查 | 覆盖 | 观测 | 失败段 | 影响代数 | 最长连续 | 说明 |\n|---|---|---|---|---|---|---|---|\n");
        boolean failed = false;
        boolean uncoveredCore = false;
        boolean warned = false;
        boolean incomplete = false;
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (var entry : merged.entrySet()) {
            var check = entry.getKey();
            var results = entry.getValue();
            var status = mergedStatus(results);
            long covered = results.stream().filter(CompactionChecks.Result::covered).count();
            boolean full = covered == results.size();
            failed |= status == CompactionChecks.Status.FAIL;
            warned |= status == CompactionChecks.Status.WARN;
            incomplete |= !full;
            uncoveredCore |= check.core && !full;
            int observed = results.stream().mapToInt(CompactionChecks.Result::observed).sum();
            String detail = results.stream().filter(r -> r.status() != CompactionChecks.Status.PASS || !r.covered())
                    .map(CompactionChecks.Result::detail).findFirst().orElse("已观测的规则检查通过");
            out.append("| ").append(status).append(" | ").append(check.label).append(check.hard ? "" : "（软）")
                    .append(" | ").append(covered).append('/').append(results.size()).append(" | ").append(observed)
                    .append(" | ").append(results.stream().mapToInt(CompactionChecks.Result::episodes).sum())
                    .append(" | ").append(results.stream().mapToInt(CompactionChecks.Result::affectedGenerations).sum())
                    .append(" | ").append(results.stream().mapToInt(CompactionChecks.Result::longestRun).max().orElse(0))
                    .append(" | ").append(detail.replace("|", "\\|").replace("\n", " ")).append(" |\n");
            coverage.add(Map.of("check", check.name(), "status", status.name(), "core", check.core,
                    "coveredScopes", covered, "expectedScopes", results.size(), "observed", observed,
                    "scope", check == CompactionChecks.Check.NO_WRITE ? "global" : "session"));
        }
        String verdict = failed ? "不通过：有硬性规则检查失败"
                : uncoveredCore ? "无法下结论：核心检查覆盖不足"
                : incomplete ? "已观测规则未见硬失败，但有未覆盖项" : warned ? "规则检查通过，但有软性告警" : "规则检查通过";
        out.append("\n结论：").append(verdict).append('\n');
        if (failed && uncoveredCore) {
            out.append("同时存在核心检查覆盖不足。\n");
        }
        Files.writeString(outputDir.resolve("report.md"), out);
        Files.writeString(outputDir.resolve("metrics.json"), SimpleJson.stringify(metrics));
        Files.writeString(outputDir.resolve("coverage.json"), SimpleJson.stringify(coverage));
        Files.writeString(outputDir.resolve("observations.jsonl"), observations);
        Files.writeString(outputDir.resolve("evidence.json"), SimpleJson.stringify(sourceCoverage));
        System.out.println(out);
        return failed || uncoveredCore;
    }

    private static Map<String, String> hashInputs(Path dir) throws Exception {
        Map<String, String> inputHashes = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p ->
                    p.getFileName().toString().matches("facts\\.json|turns\\.jsonl|script\\.txt|gen-\\d+(?:\\.input)?\\.json")).toList()) {
                inputHashes.put(dir.relativize(file).toString(), sha256(Files.readAllBytes(file)));
            }
        }
        return inputHashes;
    }

    private static Map<String, Object> metric(String session, String scope, CompactionChecks.Result result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session", session);
        row.put("scope", scope);
        row.put("check", result.check().name());
        row.put("status", result.status().name());
        row.put("observed", result.observed());
        row.put("failures", result.failures());
        row.put("episodes", result.episodes());
        row.put("affectedGenerations", result.affectedGenerations());
        row.put("longestRun", result.longestRun());
        row.put("unobserved", result.unobserved());
        row.put("covered", result.covered());
        row.put("detail", result.detail());
        return row;
    }

    static CompactionChecks.Status mergedStatus(List<CompactionChecks.Result> results) {
        for (var status : List.of(CompactionChecks.Status.FAIL, CompactionChecks.Status.WARN)) {
            if (results.stream().anyMatch(result -> result.status() == status)) {
                return status;
            }
        }
        return results.stream().allMatch(CompactionChecks.Result::covered)
                ? CompactionChecks.Status.PASS : CompactionChecks.Status.UNCOVERED;
    }

    private static boolean incomplete(Path outputDir, String reason) throws IOException {
        String message = "# 记忆摘要规则回归报告\n\n结论：无法下结论：" + reason + "\n";
        Files.writeString(outputDir.resolve("report.md"), message);
        Files.writeString(outputDir.resolve("metrics.json"), "[]");
        Files.writeString(outputDir.resolve("coverage.json"), "[]");
        System.out.println(message);
        return true;
    }

    private static String saveEvaluator(Path outputDir, Path suiteDir, Path runDir, CompactionScript script,
                                        InitializerConfig config) throws Exception {
        Map<String, String> hashes = new java.util.TreeMap<>();
        for (Path sourceDir : List.of(suiteDir, suiteDir.resolve("../../initializer/common").normalize())) {
            try (Stream<Path> files = Files.list(sourceDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String name = (sourceDir.equals(suiteDir) ? "suite/" : "common/") + file.getFileName();
                    byte[] bytes = Files.readAllBytes(file);
                    hashes.put(name, sha256(bytes));
                    Path target = outputDir.resolve("evaluator-source").resolve(name);
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
            }
        }
        String version = sha256(SimpleJson.stringify(hashes).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String key : List.of("bound.min-chars", "bound.max-chars", "bound.steady-growth-ratio",
                "bound.steady-growth-slack", "bound.max-after-ratio", "bound.min-generations", "agent.memory.context-window-chars")) {
            settings.put(key, config.require(key));
        }
        String evaluatedScript = script.turns().stream().map(turn -> (turn.ref() == null ? "" : "[" + turn.ref() + "] ")
                + turn.text()).collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        Files.writeString(outputDir.resolve("evaluated-script.txt"), evaluatedScript);
        Files.writeString(outputDir.resolve("evaluation.json"), SimpleJson.stringify(Map.of(
                "evaluatorSha256", version, "sourceSha256", hashes, "settings", settings,
                "sourceRun", runDir.toString(),
                "scriptSha256", sha256(evaluatedScript.getBytes(StandardCharsets.UTF_8)),
                "createdAt", java.time.Instant.now().toString())));
        return version;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<CompactionChecks.TurnRecord> loadTurns(Path dir) throws IOException {
        List<CompactionChecks.TurnRecord> turns = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("turns.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> record = SimpleJson.object(SimpleJson.parse(line));
            turns.add(new CompactionChecks.TurnRecord(SimpleJson.integer(record, "index", 0),
                    SimpleJson.string(record, "ref"), strings(record.get("tools")), strings(record.get("results")),
                    strings(record.get("cardTools")), strings(record.get("errors")), SimpleJson.string(record, "status"),
                    SimpleJson.string(record, "question"), cardCalls(record.get("cardCalls"))));
        }
        return turns;
    }

    /**
     * 根据已经移出原始上下文的轮次，确定本代需要执行的业务规则
     * 标记轮次仍保留在上下文中时，不要求摘要重复记录该轮信息
     */
    private static List<CompactionChecks.Generation> loadGenerations(Path dir, CompactionScript script,
                                                                     List<CompactionChecks.TurnRecord> turns,
                                                                     String orderNo, CompactionEvidence evidence) throws IOException {
        List<Map<String, Object>> raw = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().matches("gen-\\d+\\.json")).toList()) {
                raw.add(SimpleJson.object(SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
            }
        }
        raw.sort((left, right) -> Integer.compare(SimpleJson.integer(left, "number", 0),
                SimpleJson.integer(right, "number", 0)));
        List<CompactionChecks.Generation> gens = new ArrayList<>();
        for (Map<String, Object> gen : raw) {
            int through = SimpleJson.integer(gen, "compactedThrough", 0);
            Set<String> refs = new HashSet<>();
            script.refs().forEach((ref, index) -> {
                if (index <= through) {
                    refs.add(ref);
                }
            });
            // 固定剧本以查询到含签收时间的订单详情作为订单号保留检查的起点
            // 只返回单号和下单时间的订单列表不触发这项检查
            boolean orderQueried = turns.stream().filter(turn -> turn.index() <= through)
                    .flatMap(turn -> turn.results().stream())
                    .anyMatch(result -> result.startsWith("query_order\n") && result.contains("【订单 " + orderNo + "】")
                            && result.contains("签收时间"));
            gens.add(new CompactionChecks.Generation(SimpleJson.integer(gen, "number", 0),
                    SimpleJson.integer(gen, "turn", 0), through, evidence.summary(SimpleJson.string(gen, "summary"), through),
                    SimpleJson.integer(gen, "summaryChars", 0), SimpleJson.integer(gen, "materialChars", 0),
                    SimpleJson.integer(gen, "before", 0), SimpleJson.integer(gen, "after", 0),
                    Set.copyOf(refs), orderQueried));
        }
        return gens;
    }

    static List<CompactionChecks.CardCall> cardCalls(Object value) {
        if (value == null) {
            return null;
        }
        List<CompactionChecks.CardCall> calls = new ArrayList<>();
        for (Object item : SimpleJson.array(value)) {
            Map<String, Object> call = SimpleJson.object(item);
            Map<String, Object> arguments = null;
            try {
                Object raw = call.get("arguments");
                if (raw != null) {
                    arguments = SimpleJson.object(raw instanceof String text ? SimpleJson.parse(text) : raw);
                }
            } catch (IllegalArgumentException ignored) {
                // 解析失败时保留 null，后续按缺少参数记录处理
                // 若改为空对象，会将采集问题误报为调用参数值错误
            }
            calls.add(new CompactionChecks.CardCall(SimpleJson.string(call, "name"), arguments));
        }
        return List.copyOf(calls);
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value != null) {
            SimpleJson.array(value).forEach(item -> result.add(String.valueOf(item)));
        }
        return List.copyOf(result);
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static synchronized void log(String message) {
        System.out.println("[compaction] " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " " + message);
    }

    private static String require(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + name);
        }
        return value;
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("参数形如 --name value: " + token);
            }
            result.put(token.substring(2), args[++index].trim());
        }
        return result;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getName() + "（服务或数据库未启动时通常是这个）"
                : message;
    }
}
