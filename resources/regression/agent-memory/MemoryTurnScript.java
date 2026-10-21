/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * turns.properties 的对象化：一份按顺序串行提问的剧本
 * 剧本只描述问什么和期望什么，判定归 AgentMemoryRegressionMain
 */
record MemoryTurnScript(String anchor, List<Turn> turns) {

    // 已实现的记忆层，三层齐了；未列出的层判失败只记 PENDING，不判死
    static final List<String> IMPLEMENTED_TIERS = List.of("short-term", "mid-term", "long-term");

    static final String SESSION_MAIN = "main";
    static final String SESSION_FRESH = "fresh";

    static MemoryTurnScript load(Path file) throws IOException {
        Properties values = InitializerConfig.loadProperties(file);
        String anchor = required(values, "anchor", file);
        List<Turn> turns = new ArrayList<>();
        for (String ref : splitList(required(values, "turn.refs", file))) {
            String prefix = "turn." + ref + ".";
            String session = values.getProperty(prefix + "session", SESSION_MAIN).trim();
            if (!SESSION_MAIN.equals(session) && !SESSION_FRESH.equals(session)) {
                throw new IllegalArgumentException(prefix + "session 只能是 " + SESSION_MAIN
                        + " 或 " + SESSION_FRESH + "，实际: " + session);
            }
            String tier = required(values, prefix + "tier", file);
            turns.add(new Turn(ref, session, tier,
                    values.getProperty(prefix + "purpose", "").trim(),
                    required(values, prefix + "text", file),
                    splitAlternatives(values.getProperty(prefix + "expect-any", "")),
                    values.getProperty(prefix + "expect-tool", "").trim(),
                    values.getProperty(prefix + "forbid-tool", "").trim()));
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("剧本里没有任何轮次: " + file);
        }
        return new MemoryTurnScript(anchor, List.copyOf(turns));
    }

    private static String required(Properties values, String key, Path file) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("剧本缺少配置项 " + key + "，文件: " + file);
        }
        return value.trim();
    }

    private static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> splitAlternatives(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split("\\|")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 单轮剧本；expectAny 命中任一即算记住，为空不判内容；forbidTool 禁止重查以验证记忆
     */
    record Turn(String ref, String session, String tier, String purpose, String text,
                List<String> expectAny, String expectTool, String forbidTool) {

        boolean fresh() {
            return SESSION_FRESH.equals(session);
        }

        /**
         * 未实现的层不判死
         */
        boolean enforced() {
            return IMPLEMENTED_TIERS.contains(tier);
        }

        boolean matched(String answer) {
            if (expectAny.isEmpty() || answer == null) {
                return expectAny.isEmpty();
            }
            for (String keyword : expectAny) {
                if (answer.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}
