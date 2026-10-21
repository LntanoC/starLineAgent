/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small RESP2 client for PING, SET NX EX, GET, SCAN and DEL/UNLINK. */
final class RedisRespClient implements Closeable {

    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeoutMillis;
    private Socket socket;
    private InputStream input;
    private OutputStream output;

    RedisRespClient(InitializerConfig config) {
        this.host = config.get("redis.host", "127.0.0.1");
        this.port = config.getInt("redis.port", 6379);
        this.password = config.get("redis.password", "");
        this.database = config.getInt("redis.database", 0);
        this.timeoutMillis = config.getInt("redis.timeout-millis", 5000);
    }

    String ping() throws IOException {
        return String.valueOf(command("PING"));
    }

    boolean acquireLock(String key, String value, int ttlSeconds) throws IOException {
        Object result = command("SET", key, value, "NX", "EX", String.valueOf(ttlSeconds));
        return "OK".equals(result);
    }

    void releaseLock(String key, String expectedValue) throws IOException {
        Object current = command("GET", key);
        if (expectedValue.equals(current)) {
            command("DEL", key);
        }
    }

    List<String> scan(String pattern) throws IOException {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        do {
            List<?> response = asList(command("SCAN", cursor, "MATCH", pattern, "COUNT", "200"));
            if (response.size() != 2) {
                throw new IOException("Redis SCAN 返回格式异常");
            }
            cursor = String.valueOf(response.get(0));
            for (Object key : asList(response.get(1))) {
                keys.add(String.valueOf(key));
            }
        } while (!"0".equals(cursor));
        return keys;
    }

    int deleteByPattern(String pattern) throws IOException {
        List<String> keys = scan(pattern);
        if (keys.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (int offset = 0; offset < keys.size(); offset += 100) {
            List<String> command = new ArrayList<>();
            command.add("UNLINK");
            command.addAll(keys.subList(offset, Math.min(offset + 100, keys.size())));
            try {
                deleted += ((Number) command(command.toArray(String[]::new))).intValue();
            } catch (RedisCommandException unsupported) {
                command.set(0, "DEL");
                deleted += ((Number) command(command.toArray(String[]::new))).intValue();
            }
        }
        return deleted;
    }

    int deleteExact(List<String> keys) throws IOException {
        if (keys.isEmpty()) {
            return 0;
        }
        List<String> command = new ArrayList<>();
        command.add("DEL");
        command.addAll(keys);
        return ((Number) command(command.toArray(String[]::new))).intValue();
    }

    Object command(String... arguments) throws IOException {
        ensureConnected();
        writeCommand(arguments);
        return readReply();
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        if (password != null && !password.isBlank()) {
            writeCommand(new String[]{"AUTH", password});
            readReply();
        }
        if (database != 0) {
            writeCommand(new String[]{"SELECT", String.valueOf(database)});
            readReply();
        }
    }

    private void writeCommand(String[] arguments) throws IOException {
        output.write(("*" + arguments.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private Object readReply() throws IOException {
        int prefix = input.read();
        if (prefix < 0) {
            throw new EOFException("Redis 连接已关闭");
        }
        return switch (prefix) {
            case '+' -> readLine();
            case '-' -> throw new RedisCommandException(readLine());
            case ':' -> Long.parseLong(readLine());
            case '$' -> readBulkString();
            case '*' -> readArray();
            default -> throw new IOException("未知 Redis RESP 类型: " + (char) prefix);
        };
    }

    private String readBulkString() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length < 0) {
            return null;
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Redis Bulk String 不完整");
        }
        expectCrLf();
        return new String(value, StandardCharsets.UTF_8);
    }

    private List<Object> readArray() throws IOException {
        int size = Integer.parseInt(readLine());
        if (size < 0) {
            return List.of();
        }
        List<Object> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(readReply());
        }
        return result;
    }

    private String readLine() throws IOException {
        StringBuilder result = new StringBuilder();
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("Redis 行响应不完整");
            }
            if (current == '\r') {
                int next = input.read();
                if (next != '\n') {
                    throw new IOException("Redis 行响应缺少 LF");
                }
                return result.toString();
            }
            result.append((char) current);
        }
    }

    private void expectCrLf() throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Redis Bulk String 缺少 CRLF");
        }
    }

    private static List<?> asList(Object value) throws IOException {
        if (!(value instanceof List<?> list)) {
            throw new IOException("Redis 返回值不是数组: " + value);
        }
        return list;
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
        }
    }

    private static final class RedisCommandException extends IOException {
        private static final long serialVersionUID = 1L;

        private RedisCommandException(String message) {
            super(message);
        }
    }
}
