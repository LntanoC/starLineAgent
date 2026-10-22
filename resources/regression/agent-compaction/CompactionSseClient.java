/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * /agent/v1/chat 与确认接口的 SSE 客户端
 * 确认卡轮次允许回答为空，客户端仍需返回卡片信息以便拒绝操作
 */
final class CompactionSseClient {

    private static final String CHAT_PATH = "/agent/v1/chat";
    private static final String CONFIRM_PATH = "/agent/v1/chat/confirm";

    private final StarlineHttpClient http;
    private final Duration connectTimeout;

    CompactionSseClient(StarlineHttpClient http, InitializerConfig config) {
        this.http = http;
        this.connectTimeout = Duration.ofSeconds(config.getInt("server.connect-timeout-seconds", 5));
    }

    /**
     * 每次请求使用独立的 HttpClient
     * 共享客户端曾在长 SSE 流中断后持续返回 closed，导致后续轮次无法发送
     */
    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    Reply ask(String question, String conversationId, Duration timeout) throws IOException, InterruptedException {
        String path = CHAT_PATH + "?question=" + StarlineHttpClient.encodeQuery(question)
                + (conversationId == null ? "" : "&conversationId=" + StarlineHttpClient.encodeQuery(conversationId));
        return read(HttpRequest.newBuilder(URI.create(http.baseUrl() + path)).GET(), timeout, question);
    }

    /**
     * 拒绝确认卡，避免回归测试执行实际业务操作
     * 拒绝后的模型响应也纳入轮次记录
     */
    Reply deny(String conversationId, String messageId, Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("messageId", messageId);
        body.put("approved", Boolean.FALSE);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(http.baseUrl() + CONFIRM_PATH))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(body), StandardCharsets.UTF_8));
        return read(builder, timeout, "拒绝确认卡 " + messageId);
    }

    private Reply read(HttpRequest.Builder builder, Duration timeout, String label)
            throws IOException, InterruptedException {
        HttpRequest request = builder.timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("Authorization", http.authorization())
                .build();
        HttpResponse<Stream<String>> response = newClient().send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + "，" + label);
            }
            Accumulator accumulator = new Accumulator();
            // 请求超时只覆盖接收响应头的阶段，开始读流后另行检查截止时间
            Instant deadline = Instant.now().plus(timeout);
            lines.forEach(line -> {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("SSE 流超过单轮超时仍未结束: " + timeout + "，" + label);
                }
                accumulator.accept(line);
            });
            accumulator.flush();
            if (!accumulator.done) {
                throw new IOException("SSE 流未收到 done 事件即结束，" + label);
            }
            return accumulator.toReply();
        }
    }

    /**
     * results 按「工具名\n结果」保存工具返回内容
     * confirmMessageId 非空表示本轮正在等待确认，cardTools 保存待确认的工具名
     * cardCalls 保留确认卡中的调用 ID 和原始参数，供后续检查使用
     */
    record Reply(String conversationId, String answer, List<String> tools, List<String> results,
                 List<String> cardTools, List<Map<String, Object>> cardCalls, String confirmMessageId,
                 List<String> errors, String messageStatus) {
    }

    static final class Accumulator {

        private final StringBuilder data = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final Set<String> tools = new LinkedHashSet<>();
        private final List<String> cardTools = new ArrayList<>();
        private final List<Map<String, Object>> cardCalls = new ArrayList<>();
        private final List<String> results = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private String event;
        private String conversationId;
        private String confirmMessageId;
        private String messageStatus;
        private boolean done;

        void accept(String line) {
            if (line.isEmpty()) {
                flush();
                return;
            }
            if (line.startsWith(":")) {
                return;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            value = value.startsWith(" ") ? value.substring(1) : value;
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
        }

        void flush() {
            if (event != null || !data.isEmpty()) {
                handle(event, data.toString());
            }
            event = null;
            data.setLength(0);
        }

        private void handle(String name, String payload) {
            if (name == null) {
                return;
            }
            Map<String, Object> body = asObject(payload);
            switch (name) {
                case "meta" -> conversationId = SimpleJson.string(body, "conversationId");
                case "message" -> {
                    String text = SimpleJson.string(body, "delta");
                    if (text != null && !"reasoning".equals(SimpleJson.string(body, "type"))) {
                        answer.append(text);
                    }
                }
                case "block" -> {
                    String toolName = SimpleJson.string(body, "name");
                    if ("tool".equals(SimpleJson.string(body, "kind")) && toolName != null && !toolName.isBlank()) {
                        tools.add(toolName);
                        String result = SimpleJson.string(body, "result");
                        if (result != null && !result.isBlank()) {
                            results.add(toolName + "\n" + result);
                        }
                    }
                }
                case "confirm" -> {
                    confirmMessageId = SimpleJson.string(body, "messageId");
                    messageStatus = "AWAITING_CONFIRM";
                    Object calls = body.get("calls");
                    if (calls != null) {
                        for (Object call : SimpleJson.array(calls)) {
                            Map<String, Object> rawCall = SimpleJson.object(call);
                            String toolName = SimpleJson.string(rawCall, "name");
                            if (toolName != null) {
                                cardTools.add(toolName);
                                // 原样保存服务端返回的 arguments，缺少参数时保留 null
                                // 后续检查据此区分参数缺失和参数值错误
                                Map<String, Object> recorded = new LinkedHashMap<>();
                                recorded.put("name", toolName);
                                recorded.put("toolCallId", rawCall.get("toolCallId"));
                                recorded.put("arguments", rawCall.get("arguments"));
                                cardCalls.add(recorded);
                            }
                        }
                    }
                }
                case "finish" -> messageStatus = SimpleJson.string(body, "messageStatus");
                case "error", "cancel" -> errors.add(name + ": " + abbreviate(payload));
                case "done" -> done = true;
                default -> {
                    // hint 等事件与摘要判定无关
                }
            }
        }

        Reply toReply() {
            return new Reply(conversationId, answer.toString(), List.copyOf(tools), List.copyOf(results),
                    List.copyOf(cardTools), List.copyOf(cardCalls), confirmMessageId, List.copyOf(errors), messageStatus);
        }

        private static Map<String, Object> asObject(String payload) {
            try {
                return SimpleJson.object(SimpleJson.parse(payload));
            } catch (RuntimeException ex) {
                return Map.of();
            }
        }

        private static String abbreviate(String value) {
            return value.length() <= 300 ? value : value.substring(0, 300) + "...";
        }
    }
}
