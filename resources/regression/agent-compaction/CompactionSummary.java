/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按摘要中的二级标题分节，供判定规则读取各节内容
 * 单独一行的「无」不计入内容行数
 * 检查助手措辞时，只移除能在用户原文中找到出处的引文
 */
final class CompactionSummary {

    // 标题及顺序须与比特严选 context-compaction.txt 中的输出格式保持一致
    static final List<String> SECTIONS = List.of("用户诉求", "待办", "下一步", "当前进度", "走不通的路", "已完成");

    private static final Pattern QUOTED = Pattern.compile(
            "（([^（）]*?原话[：:]\\s*)([^（）]*)）|\\(([^()]*?原话[：:]\\s*)([^()]*)\\)|「([^」]*)」|“([^”]*)”");
    private static final Pattern QUOTE_BODY = Pattern.compile("「([^」]+)」|（原话[：:]\\s*([^）]+)）");

    private final String text;
    private final List<String> userTexts;
    private final boolean sourcesComplete;
    private final List<String> headings = new ArrayList<>();
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    private final Map<String, List<String>> rawSections = new LinkedHashMap<>();
    private static final Pattern NONE = Pattern.compile("无(?:（[^\\r\\n]*）|\\([^\\r\\n]*\\))?[。.]?");

    CompactionSummary(String text) {
        this(text, List.of(), false);
    }

    CompactionSummary(String text, List<String> userTexts, boolean sourcesComplete) {
        this.text = text == null ? "" : text.strip();
        this.userTexts = userTexts.stream().map(CompactionSummary::normalizeQuote).toList();
        this.sourcesComplete = sourcesComplete;
        String current = null;
        for (String raw : this.text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                headings.add(current);
                sections.putIfAbsent(current, new ArrayList<>());
                rawSections.putIfAbsent(current, new ArrayList<>());
                continue;
            }
            if (current == null || line.isEmpty()) {
                continue;
            }
            String item = line.startsWith("- ") ? line.substring(2).strip() : line;
            rawSections.get(current).add(item);
            if (!item.equals("无") && !item.equals("无。")) {
                sections.get(current).add(item);
            }
        }
    }

    String text() {
        return text;
    }

    List<String> headings() {
        return headings;
    }

    boolean structured() {
        return text.startsWith("## ") && headings.equals(SECTIONS);
    }

    List<String> lines(String section) {
        return sections.getOrDefault(section, List.of());
    }

    String joined(String section) {
        return String.join("\n", lines(section));
    }

    boolean any(String section, Predicate<String> predicate) {
        return lines(section).stream().anyMatch(predicate);
    }

    /**
     * 返回该节中第一个不满足条件的内容行
     * 所有内容行均满足条件时返回 null
     */
    String firstViolation(String section, Predicate<String> predicate) {
        return lines(section).stream().filter(predicate.negate()).findFirst().orElse(null);
    }

    /**
     * 「无（……）」也表示没有下一步
     * 括号中可以保留提示词要求的依据说明
     */
    boolean nextStepIsNone() {
        return explicitlyNone("下一步");
    }

    boolean explicitlyNone(String section) {
        List<String> raw = rawSections.getOrDefault(section, List.of());
        return raw.size() == 1 && NONE.matcher(raw.get(0)).matches();
    }

    /**
     * 收集下一步以外各节标注的原话，用于检查重复引用
     * 下一步按提示词要求引用行动依据，不计入重复检查
     */
    List<String> quotesOutsideNextStep() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            if ("下一步".equals(entry.getKey())) {
                continue;
            }
            for (String line : entry.getValue()) {
                Matcher matcher = QUOTE_BODY.matcher(line);
                while (matcher.find()) {
                    String body = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (body.strip().length() >= 6) {
                        result.add(body.strip());
                    }
                }
            }
        }
        return result;
    }

    List<String> unmatchedQuotes() {
        List<String> result = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(text);
        while (matcher.find()) {
            String body = quoteBody(matcher);
            if (declaresUserQuote(text, matcher) && !verifiedQuote(body)) {
                result.add(body);
            }
        }
        return result;
    }

    boolean hasUnknownQuotes() {
        return !sourcesComplete && QUOTED.matcher(text).results().anyMatch(match -> !verifiedQuote(quoteBody(match)));
    }

    String unquoted(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = QUOTED.matcher(value);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(verifiedQuote(quoteBody(match))
                ? quotePrefix(match) : match.group()));
    }

    private static boolean declaresUserQuote(String value, java.util.regex.MatchResult match) {
        if (match.group(1) != null || match.group(3) != null) {
            return true;
        }
        String prefix = value.substring(Math.max(0, match.start() - 24), match.start());
        return Pattern.compile("(?:原话(?:是|为)?|用户(?:自述|说|表示|称))[：:]?\\s*$").matcher(prefix).find();
    }

    private static String quoteBody(java.util.regex.MatchResult match) {
        for (int i : new int[]{2, 4, 5, 6}) {
            if (match.group(i) != null) {
                return match.group(i);
            }
        }
        throw new IllegalArgumentException("引文缺少正文");
    }

    private static String quotePrefix(java.util.regex.MatchResult match) {
        for (int group : new int[]{1, 3}) {
            if (match.group(group) != null) {
                String prefix = match.group(group).replaceFirst("原话[：:]\\s*$", "");
                // 移除引文时保留括号中原话之前的说明
                // 这部分由助手撰写，仍需接受正文检查
                return prefix.isEmpty() ? "" : (group == 1 ? "（" + prefix + "）" : "(" + prefix + ")");
            }
        }
        return "";
    }

    private boolean verifiedQuote(String body) {
        String normalized = normalizeQuote(body);
        if (normalized.isEmpty()) {
            return false;
        }
        if (userTexts.stream().anyMatch(source -> source.contains(normalized))) {
            return true;
        }
        // 分号或省略号可以分隔多段摘录，每段都须在用户原文中找到相同文字
        // 这里仅核对出处，不接受同义改写，也不判断拼接后的含义是否改变
        String[] fragments = body.split("[；;]|…+|\\.{3,}");
        return fragments.length > 1 && java.util.Arrays.stream(fragments)
                .map(CompactionSummary::normalizeQuote)
                .allMatch(fragment -> !fragment.isEmpty()
                        && userTexts.stream().anyMatch(source -> source.contains(fragment)));
    }

    private static String normalizeQuote(String value) {
        return value.replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }
}
