/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 直接读 t_agent_state 观测记忆状态
 * payload 是 AgentScope 自有编码的 JSON，本类只读不写，字段名与 AgentState / Msg 的 @JsonProperty 对齐
 */
final class AgentStateProbe {

    /**
     * 与 AgentContextTrimmer.EVICTED_PREFIX 同源，改了那边这里必须同步
     */
    static final String EVICTED_PREFIX = "[历史工具结果已省略，原长 ";

    // 与 AgentContextCompactor.SUMMARY_NAME 同源，改了那边必须同步
    private static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";

    private final JdbcClient jdbc;

    AgentStateProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Snapshot snapshot(String userId, String sessionId, String anchor) throws SQLException, IOException {
        String ctes = commonTableExpressions(userId, sessionId);

        List<List<String>> header = jdbc.queryRows(ctes + " SELECT"
                + " (SELECT length(payload::text) FROM s),"
                + " (SELECT COALESCE(jsonb_array_length(payload->'context'), 0) FROM s),"
                + " (SELECT COALESCE(string_agg(c->>'text', chr(10) ORDER BY ord), '') FROM m,"
                + " LATERAL jsonb_array_elements(COALESCE(msg->'content', '[]'::jsonb)) c"
                + " WHERE msg->>'name' = " + JdbcClient.literal(COMPACTION_SUMMARY_NAME)
                + " AND c->>'type' = 'text'),"
                + " (SELECT count(*) FROM m WHERE msg->>'name' = " + JdbcClient.literal(COMPACTION_SUMMARY_NAME) + "),"
                + " (SELECT count(*) FROM b WHERE blk->>'type' = 'text'),"
                + " (SELECT count(*) FROM b WHERE blk->>'type' = 'thinking'),"
                + " (SELECT count(*) FROM b WHERE blk->>'type' = 'tool_use'),"
                + " (SELECT count(*) FROM b WHERE blk->>'type' = 'tool_result'),"
                + " (SELECT count(*) FROM b WHERE blk->>'type' = 'tool_result' AND blk::text LIKE "
                + JdbcClient.literal("%" + EVICTED_PREFIX + "%") + "),"
                + " (SELECT count(DISTINCT ord) FROM m, LATERAL jsonb_array_elements("
                + "COALESCE(msg->'content', '[]'::jsonb)) c WHERE c->>'type' = 'tool_use'),"
                + " (SELECT count(*) FROM (SELECT blk->>'id' FROM b WHERE blk->>'type' = 'tool_use'"
                + " EXCEPT SELECT blk->>'id' FROM b WHERE blk->>'type' = 'tool_result') o),"
                + " (SELECT count(*) FROM (SELECT blk->>'id' FROM b WHERE blk->>'type' = 'tool_result'"
                + " EXCEPT SELECT blk->>'id' FROM b WHERE blk->>'type' = 'tool_use') o),"
                + " (SELECT COALESCE(sum(" + contextCharsExpression() + "), 0) FROM b),"
                + " (SELECT count(*) FROM s WHERE payload::text LIKE " + JdbcClient.literal("%" + anchor + "%") + "),"
                + " (SELECT to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') FROM s)");
        if (header.isEmpty() || header.get(0).get(0).isEmpty()) {
            return Snapshot.absent();
        }
        List<String> row = header.get(0);

        List<List<String>> results = jdbc.queryRows(ctes + " SELECT " + outputCharsExpression()
                + ", COALESCE(blk->>'name', '')"
                + " FROM b WHERE blk->>'type' = 'tool_result' ORDER BY 1 DESC");
        List<ToolResult> toolResults = new ArrayList<>(results.size());
        for (List<String> item : results) {
            toolResults.add(new ToolResult(Integer.parseInt(item.get(0)), item.get(1)));
        }

        // usage 由供应商回填并随消息一起落库，是本台唯一权威的 token 读数
        List<List<String>> usageRows = jdbc.queryRows(ctes + " SELECT ord,"
                + " COALESCE(msg->'usage'->>'inputTokens', '0'),"
                + " COALESCE(msg->'usage'->>'outputTokens', '0'),"
                + " COALESCE(msg->'usage'->>'cachedTokens', '0')"
                + " FROM m WHERE jsonb_typeof(msg->'usage') = 'object' ORDER BY ord");
        List<Usage> usages = new ArrayList<>(usageRows.size());
        for (List<String> item : usageRows) {
            usages.add(new Usage(Integer.parseInt(item.get(0)), Integer.parseInt(item.get(1)),
                    Integer.parseInt(item.get(2)), Integer.parseInt(item.get(3))));
        }

        return new Snapshot(true, Integer.parseInt(row.get(0)), Integer.parseInt(row.get(1)), row.get(2),
                Integer.parseInt(row.get(3)), Integer.parseInt(row.get(4)), Integer.parseInt(row.get(5)),
                Integer.parseInt(row.get(6)), Integer.parseInt(row.get(7)), Integer.parseInt(row.get(8)),
                Integer.parseInt(row.get(9)), Integer.parseInt(row.get(10)), Integer.parseInt(row.get(11)),
                Integer.parseInt(row.get(12)), Integer.parseInt(row.get(13)) > 0, row.get(14),
                List.copyOf(toolResults), List.copyOf(usages));
    }

    /**
     * 不按 state_key 过滤：键名是框架私有约定
     */
    private static String commonTableExpressions(String userId, String sessionId) {
        return "WITH s AS (SELECT payload, update_time FROM t_agent_state"
                + " WHERE user_id = " + JdbcClient.literal(userId)
                + " AND session_id = " + JdbcClient.literal(sessionId)
                + " AND jsonb_exists(payload, 'context')"
                + " ORDER BY update_time DESC LIMIT 1),"
                + " m AS (SELECT msg, ord FROM s, LATERAL jsonb_array_elements("
                + "COALESCE(s.payload->'context', '[]'::jsonb)) WITH ORDINALITY AS t(msg, ord)),"
                + " b AS (SELECT blk FROM m, LATERAL jsonb_array_elements("
                + "COALESCE(msg->'content', '[]'::jsonb)) blk)";
    }

    /**
     * 与 AgentContextChars 对齐的口径，tool_use 入参因 Map.toString vs JSON 有微小偏差
     */
    private static String contextCharsExpression() {
        return "CASE blk->>'type'"
                + " WHEN 'text' THEN length(COALESCE(blk->>'text', ''))"
                + " WHEN 'thinking' THEN length(COALESCE(blk->>'thinking', ''))"
                + " WHEN 'tool_use' THEN length(COALESCE(blk->>'name', ''))"
                + " + length(COALESCE(blk->'input', '{}'::jsonb)::text)"
                + " WHEN 'tool_result' THEN (" + outputCharsExpression() + ")"
                + " ELSE 0 END";
    }

    private static String outputCharsExpression() {
        return "(SELECT COALESCE(sum(length(COALESCE(o->>'text', ''))), 0)"
                + " FROM jsonb_array_elements(COALESCE(blk->'output', '[]'::jsonb)) o)";
    }

    /**
     * 会话状态只读快照
     */
    record Snapshot(boolean present, int payloadBytes, int messageCount, String summary, int summaryMessages,
                    int textBlocks, int thinkingBlocks, int toolUseBlocks, int toolResultBlocks,
                    int evictedToolResults, int toolCycles, int orphanToolUses, int orphanToolResults,
                    int contextChars, boolean anchorPresent, String updatedAt,
                    List<ToolResult> toolResults, List<Usage> usages) {

        static Snapshot absent() {
            return new Snapshot(false, 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, "", List.of(), List.of());
        }

        /**
         * 孤儿块说明有人动了消息条数
         */
        boolean structurallySound() {
            return orphanToolUses == 0 && orphanToolResults == 0;
        }

        int maxInputTokens() {
            int max = 0;
            for (Usage usage : usages) {
                max = Math.max(max, usage.inputTokens());
            }
            return max;
        }

        int maxCachedTokens() {
            int max = 0;
            for (Usage usage : usages) {
                max = Math.max(max, usage.cachedTokens());
            }
            return max;
        }

        List<Integer> toolResultChars() {
            List<Integer> chars = new ArrayList<>(toolResults.size());
            for (ToolResult item : toolResults) {
                chars.add(item.chars());
            }
            return chars;
        }
    }

    record ToolResult(int chars, String toolName) {
    }

    record Usage(int ord, int inputTokens, int outputTokens, int cachedTokens) {
    }
}
