/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.nageoffer.ai.ragent.initializer.AgentStateProbe.Snapshot;
import com.nageoffer.ai.ragent.initializer.AgentStateProbe.ToolResult;
import com.nageoffer.ai.ragent.initializer.AgentStateProbe.Usage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单会话状态只读排查：把 t_agent_state 里的 payload 摊开看
 */
final class AgentMemoryProbeMain {

    private AgentMemoryProbeMain() {
    }

    public static void main(String[] args) {
        RegressionContext.run(args, AgentMemoryProbeMain::execute);
    }

    private static void execute(RegressionContext context) throws Exception {
        String sessionId = context.argument("session", "");
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("缺少参数 --session <会话ID>");
        }
        String anchor = context.argument("anchor", defaultAnchor(context));
        String userId = context.login();

        Snapshot snapshot = context.probe().snapshot(userId, sessionId, anchor);
        System.out.println("=== 会话状态 ===");
        System.out.println("  userId       " + userId);
        System.out.println("  sessionId    " + sessionId);
        if (!snapshot.present()) {
            System.out.println("  t_agent_state 里没有这条会话的上下文，可能是会话不属于该账号，或对话还没结束");
            return;
        }
        System.out.println("  更新时间     " + snapshot.updatedAt());
        System.out.println("  payload      " + snapshot.payloadBytes() + " 字节");
        System.out.println("  上下文       " + snapshot.messageCount() + " 条消息 / ≈" + snapshot.contextChars() + " 字符");
        System.out.println("  内容块       text " + snapshot.textBlocks()
                + " / thinking " + snapshot.thinkingBlocks()
                + " / tool_use " + snapshot.toolUseBlocks()
                + " / tool_result " + snapshot.toolResultBlocks());
        System.out.println("  工具循环     " + snapshot.toolCycles() + " 个");
        System.out.println("  已清理结果   " + snapshot.evictedToolResults() + " 块（前缀 " + AgentStateProbe.EVICTED_PREFIX + "…）");
        System.out.println("  结构完整     " + (snapshot.structurallySound() ? "是"
                : "否，孤儿 tool_use " + snapshot.orphanToolUses() + " / 孤儿 tool_result " + snapshot.orphanToolResults()));
        System.out.println("  锚点         " + anchor + " " + (snapshot.anchorPresent() ? "仍在 payload 里" : "已不在 payload 里"));
        System.out.println("  会话摘要     " + snapshot.summaryMessages() + " 条摘要消息"
                + (snapshot.summary().isBlank() ? "（本次没压缩过）"
                : "，正文 " + snapshot.summary().length() + " 字符"));

        // 摘要正文给人读的，没有可自动判的性质
        if (!snapshot.summary().isBlank()) {
            System.out.println();
            System.out.println("=== 末代摘要正文（含围栏，与回填进上下文的那份逐字一致） ===");
            System.out.println(snapshot.summary());
        }

        System.out.println();
        System.out.println("=== tool_result 体量（降序） ===");
        if (snapshot.toolResults().isEmpty()) {
            System.out.println("  这条会话还没有任何工具结果");
        }
        for (ToolResult item : snapshot.toolResults()) {
            System.out.printf("  %8d 字符  %s%n", item.chars(), item.toolName().isBlank() ? "(未命名)" : item.toolName());
        }

        System.out.println();
        System.out.println("=== 逐条 usage（按上下文顺序） ===");
        if (snapshot.usages().isEmpty()) {
            System.out.println("  没有带 usage 的消息，说明供应商没回填 token 用量");
        }
        for (Usage usage : snapshot.usages()) {
            System.out.printf("  #%-4d input %7d  output %7d  cached %7d%n",
                    usage.ord(), usage.inputTokens(), usage.outputTokens(), usage.cachedTokens());
        }
        System.out.println("  输入峰值 " + snapshot.maxInputTokens() + " token，缓存命中峰值 "
                + snapshot.maxCachedTokens() + " token");
        System.out.println();
        System.out.println("[regression] SUCCESS");
    }

    /**
     * 默认沿用剧本里的锚点，剧本不在就要求显式传入
     */
    private static String defaultAnchor(RegressionContext context) throws Exception {
        Path script = context.suiteDir().resolve("turns.properties");
        if (!Files.isRegularFile(script)) {
            throw new IllegalArgumentException("剧本不存在，请用 --anchor <关键词> 指定要检查的锚点: " + script);
        }
        return MemoryTurnScript.load(script).anchor();
    }
}
