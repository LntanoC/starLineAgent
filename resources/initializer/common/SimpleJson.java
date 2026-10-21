/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON codec implemented only with the JDK. */
final class SimpleJson {

    private SimpleJson() {
    }

    static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("JSON 末尾存在多余内容");
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("期望 JSON Object，实际为: " + typeName(value));
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("期望 JSON Array，实际为: " + typeName(value));
        }
        return (List<Object>) list;
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static int integer(Map<String, Object> object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static void write(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), output);
                output.append(':');
                write(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                write(item, output);
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            for (int index = 0; index < Array.getLength(value); index++) {
                if (index > 0) {
                    output.append(',');
                }
                write(Array.get(value, index), output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 类型: " + value.getClass().getName());
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        output.append(String.format("\\u%04x", (int) ch));
                    } else {
                        output.append(ch);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input == null ? "" : input;
        }

        private Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("JSON 内容为空");
            }
            return switch (input.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(index) != '"') {
                    throw error("JSON Object 的 Key 必须是字符串");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return result.toString();
                }
                if (ch != '\\') {
                    result.append(ch);
                    continue;
                }
                if (atEnd()) {
                    throw error("JSON 字符串转义不完整");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> throw error("不支持的 JSON 转义: \\" + escaped);
                }
            }
            throw error("JSON 字符串未闭合");
        }

        private char readUnicode() {
            if (index + 4 > input.length()) {
                throw error("Unicode 转义不完整");
            }
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException ex) {
                throw error("非法 Unicode 转义: " + hex);
            }
        }

        private Object readNumber() {
            int start = index;
            if (take('-')) {
                // sign consumed
            }
            readDigits();
            if (take('.')) {
                readDigits();
            }
            if (!atEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (!atEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                readDigits();
            }
            if (start == index) {
                throw error("无法识别 JSON 值");
            }
            try {
                return new BigDecimal(input.substring(start, index));
            } catch (NumberFormatException ex) {
                throw error("非法 JSON 数字");
            }
        }

        private void readDigits() {
            int start = index;
            while (!atEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("JSON 数字缺少数字部分");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("非法 JSON 字面量");
            }
            index += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || input.charAt(index) != expected) {
                throw error("期望字符 '" + expected + "'");
            }
            index++;
        }

        private boolean take(char expected) {
            if (!atEnd() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean atEnd() {
            return index >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "，位置 " + index);
        }
    }
}
