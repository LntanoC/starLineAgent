/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Path;
import java.util.*;

import static com.nageoffer.ai.ragent.initializer.LongTermMemoryMain.*;

/**
 * 撤回屏幕偏好后，在新会话中补测记忆召回
 * 保存回答并核对记忆表是否变化，回答含义由人工复核
 * 补测结果单独记录，保留原七轮测评的结论
 */
final class MemoryRecallCheck {
    static int run(InitializerConfig config, Path output, StarlineHttpClient http, JdbcClient jdbc,
                   String uid, Map<String, Object> baseline) throws Exception {
        List<Map<String, Object>> before = objects(baseline.get("memory"));
        if (active(before).stream().anyMatch(LongTermMemoryMain::target))
            throw new IllegalStateException("屏幕偏好仍生效，应先完成指定撤回，未发送补测问题");
        if (objects(baseline.get("extractions")).stream().anyMatch(e -> "PROCESSING".equals(e.get("status"))))
            throw new IllegalStateException("存在在飞抽取，未发送补测问题");
        String question = "你还记得我选手机时，对屏幕大小有什么偏好吗？如果没保存，就说不知道，不要猜。";
        String from = objects(baseline.get("extractions")).stream().map(m -> text(m,"id")).max(String::compareTo).orElse("0");
        write(output.resolve("supplement-run.json"), Map.of("mode", "verify-forgotten", "startedAt", now().toString(),
                "question", question, "freshConversation", true, "baseUrl", http.baseUrl(), "sourceSha256", fingerprints()));
        Map<String, Object> response = new MemorySseClient().askObserved(http, question, null,
                config.getInt("turn.timeout-seconds", 180), output.resolve("recall.sse"));
        write(output.resolve("recall-response.json"), response);
        String conv = JdbcClient.literal(text(response,"conversationId"));
        Map<String, Object> after;
        long deadline = System.nanoTime() + config.getInt("probe.timeout-seconds", 30) * 1_000_000_000L;
        do {
            after = snapshot(jdbc, uid, from, conv);
            if (!objects(after.get("state")).isEmpty()) break;
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        write(output.resolve("recall-after.json"), after);
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "补测回复完整成功", Boolean.TRUE.equals(response.get("replyComplete")), text(response,"messageStatus"));
        check(checks, "补测后仍无屏幕偏好", active(objects(after.get("memory"))).stream().noneMatch(LongTermMemoryMain::target), "recall-after.json");
        check(checks, "补测没有改变记忆表", before.equals(objects(after.get("memory"))), "基线与补测后全量记忆快照比较");
        check(checks, "补测会话状态已保存", !objects(after.get("state")).isEmpty(), "recall-after.json");
        check(checks, "补测状态未持久化记忆块", objects(after.get("state")).stream()
                .noneMatch(s -> SimpleJson.stringify(s.get("payload")).contains("__user_memory__")), "按消息名检查");
        List<Map<String, Object>> compactions = rows(jdbc, "select id,conversation_id,generation from t_agent_context_compaction where user_id=" + uid + " and conversation_id=" + conv);
        write(output.resolve("compactions.json"), compactions);
        check(checks, "补测未发生摘要压缩", compactions.isEmpty(), "compactions.json");
        checks.add(Map.of("name", "撤回后新会话是否承认未知（人工复核）", "status", "UNKNOWN", "evidence", text(response,"answer")));
        write(output.resolve("checks.json"), checks);
        report(output, List.of(Map.of("round", "补测", "name", "撤回后新会话", "conversationId", text(response,"conversationId"),
                "messageStatus", text(response,"messageStatus"), "answer", text(response,"answer"))), checks, null);
        // 共用报告方法默认使用七轮测评标题，这里改为单轮补测的实际范围
        Path report = output.resolve("report.md");
        java.nio.file.Files.writeString(report, java.nio.file.Files.readString(report).replace(
                "7 轮计划，5 个会话。", "独立补测 1 轮、新建 1 个会话；不改写原七轮结论。"));
        System.out.println("补测回复（" + text(response,"messageStatus") + "）：" + text(response,"answer"));
        return checks.stream().anyMatch(c -> "FAIL".equals(c.get("status"))) ? 1 : 2;
    }
}
