/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.starlineagent.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Independent structured serializer; no dependency on the legacy trace package. */
@Component
@RequiredArgsConstructor
public class AgentAuditSerializer {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|secret|api[_-]?key|authorization|access[_-]?token|private[_-]?key|verification[_-]?code).*");
    private static final Pattern SECRET_TEXT = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{16,}|sk-[A-Za-z0-9_-]{16,}");
    private final ObjectMapper mapper;
    private final AgentAuditProperties properties;

    public String modelInput(ModelCallInput input) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("messages", input.messages() == null ? List.of() : input.messages().stream().map(this::describeMessage).toList());
        value.put("tools", input.tools() == null ? List.of() : input.tools().stream().map(t -> {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("name", t.getName()); item.put("description", t.getDescription()); item.put("parameters", t.getParameters());
            return item;
        }).toList());
        // GenerateOptions also carries credentials; only safe, reproducible settings are included.
        if (input.options() != null) {
            var o = input.options();
            Map<String,Object> options = new LinkedHashMap<>();
            options.put("model", o.getModelName()); options.put("temperature", o.getTemperature());
            options.put("top_p", o.getTopP()); options.put("max_tokens", o.getMaxTokens());
            options.put("max_completion_tokens", o.getMaxCompletionTokens()); options.put("stream", o.getStream());
            value.put("options", options);
        }
        return payload(value);
    }

    public Map<String,Object> describeMessage(Msg msg) {
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("role", msg.getRole() == null ? null : msg.getRole().name().toLowerCase(Locale.ROOT));
        item.put("name", msg.getName());
        item.put("blocks", describeBlocks(msg.getContent()));
        if (msg.getMetadata() != null && !msg.getMetadata().isEmpty()) item.put("metadata", msg.getMetadata());
        return item;
    }

    private List<Object> describeBlocks(List<ContentBlock> blocks) {
        if (blocks == null) return List.of();
        return blocks.stream().map(this::describeBlock).toList();
    }

    private Object describeBlock(ContentBlock block) {
        Map<String,Object> item = new LinkedHashMap<>();
        if (block instanceof TextBlock text) {
            item.put("type", "text"); item.put("text", text.getText());
        } else if (block instanceof ThinkingBlock thinking) {
            item.put("type", "thinking"); item.put("thinking", thinking.getThinking());
        } else if (block instanceof ToolUseBlock tool) {
            item.put("type", "tool_use"); item.put("id", tool.getId()); item.put("name", tool.getName());
            item.put("state", tool.getState()); item.put("input", tool.getInput());
        } else if (block instanceof ToolResultBlock result) {
            item.put("type", "tool_result"); item.put("id", result.getId()); item.put("name", result.getName());
            item.put("state", result.getState()); item.put("output", describeBlocks(result.getOutput()));
        } else {
            item.put("type", block == null ? "null" : block.getClass().getSimpleName());
            item.put("value", String.valueOf(block));
        }
        return item;
    }

    public String toolResult(ToolResultBlock result) { return payload(describeBlock(result)); }

    public Object parseOrRaw(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return mapper.readValue(raw, Object.class); } catch (Exception ignored) { return raw; }
    }

    public String payload(Object value) {
        if (!properties.isCaptureContent()) return null;
        try {
            Budget budget = new Budget(Math.max(4096, properties.getMaxPayloadChars()) / 2);
            Object safe = sanitize(value, "", budget, 0);
            Map<String,Object> wrapper = new LinkedHashMap<>();
            wrapper.put("data", safe);
            wrapper.put("truncated", budget.truncated);
            String json = mapper.writeValueAsString(wrapper);
            // Structural overhead and JSON escaping are also bounded; retain valid JSON on fallback.
            if (json.length() > properties.getMaxPayloadChars()) {
                return mapper.writeValueAsString(Map.of("data", clip(json, properties.getMaxPayloadChars() / 4), "truncated", true));
            }
            return json;
        } catch (Exception ignored) { return "{\"data\":\"[无法序列化]\",\"truncated\":true}"; }
    }

    private Object sanitize(Object value, String key, Budget budget, int depth) {
        if (SECRET_KEY.matcher(key).matches()) return "[REDACTED]";
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (depth > 20 || budget.left <= 0) { budget.truncated = true; return "[截断]"; }
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new LinkedHashMap<>();
            int count = 0;
            for (var entry : map.entrySet()) {
                if (++count > 500) { budget.truncated = true; break; }
                String k = clip(String.valueOf(entry.getKey()), 128);
                result.put(k, sanitize(entry.getValue(), k, budget, depth + 1));
            }
            return result;
        }
        if (value instanceof Collection<?> items) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : items) {
                if (++count > 500) { budget.truncated = true; break; }
                result.add(sanitize(item, "", budget, depth + 1));
            }
            return result;
        }
        if (value instanceof Enum<?> e) return e.name();
        String text;
        if (value instanceof CharSequence) text = value.toString();
        else {
            try { return sanitize(mapper.convertValue(value, Object.class), key, budget, depth + 1); }
            catch (Exception ignored) { text = String.valueOf(value); }
        }
        text = SECRET_TEXT.matcher(text).replaceAll("[REDACTED]");
        int limit = Math.min(Math.max(1024, properties.getMaxFieldChars()), budget.left);
        if (text.length() > limit) { budget.truncated = true; text = text.substring(0, Math.max(0, limit)) + "…[截断]"; }
        budget.left -= text.length();
        return text;
    }

    public String safeText(String value, int max) { return clip(value == null ? null : SECRET_TEXT.matcher(value).replaceAll("[REDACTED]"), max); }

    public static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…[截断]";
    }
    private static final class Budget {
        private int left; private boolean truncated;
        private Budget(int left) { this.left = left; }
    }
}
