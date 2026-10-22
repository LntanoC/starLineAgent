/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从轮次记录和上下文快照中恢复用户原文，供摘要引文校验使用
 * 当前剧本用于确定旧快照中消息的轮次，不能作为用户实际发送过消息的依据
 */
final class CompactionEvidence {
    private final Map<Integer, String> questions = new HashMap<>();
    private final Set<Integer> sent;

    private CompactionEvidence(List<CompactionChecks.TurnRecord> turns) {
        sent = turns.stream().map(CompactionChecks.TurnRecord::index).collect(Collectors.toSet());
        for (var turn : turns) {
            put(turn.index(), turn.question());
        }
    }

    static CompactionEvidence load(Path dir, CompactionScript script,
                                   List<CompactionChecks.TurnRecord> turns) throws IOException {
        CompactionEvidence evidence = new CompactionEvidence(turns);
        Map<String, Integer> positions = new HashMap<>();
        script.turns().forEach(turn -> positions.put(turn.text(), turn.index()));
        if (!Files.isDirectory(dir)) {
            return evidence;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("gen-\\d+\\.input\\.json")).sorted().toList()) {
                Map<String, Object> input = SimpleJson.object(SimpleJson.parse(Files.readString(file)));
                int turn = SimpleJson.integer(input, "turn", 0);
                String question = SimpleJson.string(input, "question");
                evidence.put(turn, question);
                // 先核对快照中的提问与剧本轮次，避免剧本变更后对应到错误的消息
                // 提问无法对应时，只保留快照中记录的当前问题，不恢复其他轮次的原文
                if (!Integer.valueOf(turn).equals(positions.get(question))) {
                    continue;
                }
                List<Map.Entry<Integer, String>> recovered = new ArrayList<>();
                int previous = 0;
                boolean aligned = true;
                for (Object item : SimpleJson.array(input.get("context"))) {
                    Map<String, Object> message = SimpleJson.object(item);
                    if (!"USER".equals(message.get("role")) || "__compaction_summary__".equals(message.get("name"))) {
                        continue;
                    }
                    StringBuilder body = new StringBuilder();
                    for (Object block : SimpleJson.array(message.get("content"))) {
                        Map<String, Object> content = SimpleJson.object(block);
                        if ("text".equals(content.get("type"))) {
                            body.append(SimpleJson.string(content, "text"));
                        }
                    }
                    String text = body.toString().strip();
                    Integer index = positions.get(text);
                    if (index == null || index <= previous || index >= turn || !evidence.sent.contains(index)) {
                        aligned = false;
                        break;
                    }
                    recovered.add(Map.entry(index, text));
                    previous = index;
                }
                if (aligned) {
                    recovered.forEach(entry -> evidence.put(entry.getKey(), entry.getValue()));
                }
            }
        }
        return evidence;
    }

    CompactionSummary summary(String text, int through) {
        List<String> sources = questions.entrySet().stream().filter(entry -> entry.getKey() <= through)
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        boolean complete = through >= 0;
        for (int index = 1; index <= through; index++) {
            if (!questions.containsKey(index)) {
                complete = false;
            }
        }
        return new CompactionSummary(text, sources, complete);
    }

    int recoveredQuestions() {
        return questions.size();
    }

    private void put(int index, String text) {
        if (!sent.contains(index) || text == null || text.isBlank()) {
            return;
        }
        String prior = questions.putIfAbsent(index, text.strip());
        if (prior != null && !prior.equals(text.strip())) {
            throw new IllegalArgumentException("第 " + index + " 轮用户原文证据相互冲突");
        }
    }
}
