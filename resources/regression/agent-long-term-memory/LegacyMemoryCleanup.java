/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

import java.nio.file.Path;
import java.util.*;

import static com.nageoffer.ai.starlineagent.initializer.LongTermMemoryMain.*;

/**
 * 撤回旧测评条目或调用方指定的测试条目
 * 先核对条目 ID 和完整正文，再通过聊天请求撤回
 * 使用只读数据库查询确认撤回结果，并核对其他记忆是否发生变化
 */
final class LegacyMemoryCleanup {
    private static final String ID = "2110548997088772096";
    private static final String CONTENT = "用户平时用 Apple Pencil 记笔记时习惯左手握笔";

    static void run(InitializerConfig config, Path output, StarlineHttpClient http, JdbcClient jdbc,
                    String uid, Map<String, Object> baseline) throws Exception {
        runSpecific(config, output, http, jdbc, uid, baseline, ID, CONTENT);
    }
    static void runSpecific(InitializerConfig config, Path output, StarlineHttpClient http, JdbcClient jdbc,
                    String uid, Map<String, Object> baseline, String id, String content) throws Exception {
        List<Map<String, Object>> before = objects(baseline.get("memory"));
        Map<String, Object> target = before.stream().filter(m -> id.equals(text(m, "id"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到指定测试条目，未发送请求"));
        if (!content.equals(text(target, "content"))) throw new IllegalStateException("条目正文已变化，未发送请求");
        if (target.get("invalid_at") != null) {
            write(output.resolve("cleanup.json"), Map.of("memoryId", id, "status", "ALREADY_INACTIVE"));
            System.out.println("测试条目已经失效，无需重复撤回");
            return;
        }
        String question = "请删掉你保存的‘" + content + "’这条长期记忆，其他关于我的信息保留。请现在处理。";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memoryId", id); result.put("question", question); result.put("startedAt", now().toString());
        String from = objects(baseline.get("extractions")).stream().map(m -> text(m,"id")).max(String::compareTo).orElse("0");
        Exception failure = null;
        try {
            System.out.println("通过已启动服务指定撤回测试条目 " + id);
            Map<String, Object> response = new MemorySseClient().askObserved(http, question, null,
                    config.getInt("turn.timeout-seconds", 180), output.resolve("cleanup.sse"));
            result.put("response", response);
            String requestId = jdbc.queryRows("select reply_to_message_id from t_agent_message where user_id=" + uid
                    + " and id=" + JdbcClient.literal(text(response,"messageId"))).get(0).get(0);
            result.put("requestMessageId", requestId);
            if (requestId.isBlank()) throw new IllegalStateException("缺少撤回请求消息 ID");
            long deadline = System.nanoTime() + config.getInt("probe.timeout-seconds", 30) * 1_000_000_000L;
            do {
                Map<String, Object> snap = snapshot(jdbc, uid, from, null);
                if (!covering(objects(snap.get("extractions")), requestId).isEmpty()) break;
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
        } catch (Exception e) {
            failure = e;
            result.put("requestError", e.getClass().getSimpleName());
        }
        Map<String, Object> after = snapshot(jdbc, uid, from, null);
        write(output.resolve("cleanup-after.json"), after);
        List<Map<String, Object>> current = objects(after.get("memory"));
        boolean retracted = current.stream().anyMatch(m -> id.equals(text(m,"id"))
                && m.get("invalid_at") != null && m.get("superseded_by") == null);
        boolean othersUnchanged = before.stream().filter(m -> !id.equals(text(m,"id"))).toList()
                .equals(current.stream().filter(m -> !id.equals(text(m,"id"))).toList());
        result.put("targetRetracted", retracted); result.put("otherRowsUnchanged", othersUnchanged);
        result.put("finishedAt", now().toString());
        result.put("status", retracted && othersUnchanged ? "PASS" : "INCOMPLETE");
        write(output.resolve("cleanup.json"), result);
        System.out.println("数据库核对：指定条目已撤回=" + retracted + "，其他条目未变=" + othersUnchanged);
        if (failure != null) throw failure;
        if (!retracted || !othersUnchanged) throw new IllegalStateException("撤回未通过数据库核对，详见 cleanup.json");
    }
}
