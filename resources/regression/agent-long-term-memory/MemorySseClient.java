/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 发送 Agent SSE 请求并保存原始响应
 * 超时限制覆盖请求发送和完整响应体的接收过程
 * 从响应中提取结束状态、回答正文和工具事件，供调用方检查本轮结果
 */
final class MemorySseClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    Map<String, Object> ask(StarlineHttpClient http, String question, String conversation, int timeout, Path rawFile) throws Exception {
        Map<String, Object> result = askObserved(http, question, conversation, timeout, rawFile);
        requireSuccessful(result);
        return result;
    }
    /**
     * 采集具有完整结束信息的响应，包括 INTERRUPTED 等未正常完成的轮次
     * 缺少流结束事件或必要的会话、消息、状态字段时抛出异常
     * 调用方根据 replyComplete 判断回复是否成功
     */
    Map<String, Object> askObserved(StarlineHttpClient http, String question, String conversation, int timeout, Path rawFile) throws Exception {
        String url = http.baseUrl() + "/agent/v1/chat?question=" + StarlineHttpClient.encodeQuery(question)
                + (conversation == null ? "" : "&conversationId=" + StarlineHttpClient.encodeQuery(conversation));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout))
                .header("Authorization", http.authorization()).header("Accept", "text/event-stream").GET().build();
        long start = System.nanoTime();
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try { response = future.get(timeout, TimeUnit.SECONDS); }
        catch (Exception e) { future.cancel(true); throw e; }
        Files.writeString(rawFile, response.body());
        if (response.statusCode() != 200) throw new IllegalStateException("SSE HTTP " + response.statusCode());
        Map<String, Object> result = parseObserved(response.body());
        result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return result;
    }
    static Map<String, Object> parse(String raw) {
        Map<String, Object> result = parseObserved(raw);
        requireSuccessful(result);
        return result;
    }
    private static void requireSuccessful(Map<String, Object> result) {
        if (!Boolean.TRUE.equals(result.get("replyComplete"))) throw new IllegalStateException("SSE 回复未完整成功，详见原始响应");
    }
    static Map<String, Object> parseObserved(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder answer = new StringBuilder();
        List<Object> tools = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean done = false;
        for (String frame : raw.replace("\r\n", "\n").split("\n\n")) {
            String event = "";
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) event = line.substring(6).strip();
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (event.equals("done")) { done = true; continue; }
            if (event.equals("cancel") || event.equals("error")) throw new IllegalStateException("SSE " + event);
            if (!List.of("meta", "message", "block", "finish").contains(event)) continue;
            Map<String, Object> body = SimpleJson.object(SimpleJson.parse(data.toString()));
            switch (event) {
                case "meta", "finish" -> result.putAll(body);
                case "message" -> {
                    if ("error".equals(body.get("type"))) errors.add(Objects.toString(body.get("delta"), "服务端 error 消息"));
                    else if (!"reasoning".equals(body.get("type")) && body.get("delta") instanceof String delta) answer.append(delta);
                }
                case "block" -> { if ("tool".equals(body.get("kind"))) tools.add(body); }
                default -> { }
            }
        }
        if (!done || result.get("conversationId") == null || result.get("messageId") == null
                || result.get("messageStatus") == null) throw new IllegalStateException("SSE 未完整终止，详见原始响应");
        result.put("replyComplete", "NORMAL".equals(result.get("messageStatus")) && !answer.isEmpty() && errors.isEmpty());
        result.put("errors", errors);
        result.put("answer", answer.toString()); result.put("toolEvents", tools);
        return result;
    }
}
