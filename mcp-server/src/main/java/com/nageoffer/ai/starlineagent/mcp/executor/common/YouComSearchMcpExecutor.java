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

package com.nageoffer.ai.starlineagent.mcp.executor.common;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.starlineagent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.starlineagent.mcp.config.YdcApiKeyConfiguredCondition;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolResults;
import com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema;
import com.nageoffer.ai.starlineagent.mcp.executor.starline.WebSearchClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.starlineagent.mcp.executor.McpToolSchema.string;

/** You.com generic web search MCP tool. HTTP access and provider parsing live in WebSearchClient. */
@Slf4j
@Component
@Conditional(YdcApiKeyConfiguredCondition.class)
@RequiredArgsConstructor
public class YouComSearchMcpExecutor {

    private static final String TOOL_ID = "youcom_search";
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 20;
    private static final List<String> FRESHNESS_VALUES = List.of("day", "week", "month", "year");

    @org.springframework.beans.factory.annotation.Qualifier("youComWebSearchClient")
    private final WebSearchClient searchClient;

    @Bean
    public McpServerFeatures.SyncToolSpecification youComSearchToolSpecification() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("query", "检索关键词或问题"))
                .optional(integer("count", "最多返回的结果条数（网页+新闻合计），默认 5，最大 20").defaultTo(5))
                .optional(string("freshness", "结果时效过滤：day(一天内)、week(一周内)、month(一月内)、year(一年内)，不传则不限")
                        .options(FRESHNESS_VALUES))
                .build();
        Tool tool = Tool.builder()
                .name(TOOL_ID)
                .description("基于 You.com Search API 的通用联网搜索，返回网页标题、来源链接与摘录。需要配置 YDC_API_KEY。")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String query = StrUtil.trimToNull(MapUtil.getStr(args, "query"));
            Integer count = MapUtil.getInt(args, "count");
            String freshness = StrUtil.trimToNull(MapUtil.getStr(args, "freshness"));
            if (query == null) {
                return McpToolResults.error("请提供检索关键词 query");
            }
            if (count == null || count <= 0) count = DEFAULT_COUNT;
            count = Math.min(count, MAX_COUNT);
            if (freshness != null && !FRESHNESS_VALUES.contains(freshness)) {
                return McpToolResults.error("freshness 参数不合法，可选值：" + String.join("、", FRESHNESS_VALUES));
            }
            List<WebSearchClient.RawResult> results = searchClient.search(query, freshness, count);
            String formatted = formatResults(results);
            log.info("MCP 工具调用完成, toolId={}, resultCount={}, elapsed={}ms",
                    TOOL_ID, results.size(), System.currentTimeMillis() - startMs);
            return McpToolResults.success(formatted);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms", TOOL_ID,
                    System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("搜索", e);
        }
    }

    private String formatResults(List<WebSearchClient.RawResult> items) {
        if (items == null || items.isEmpty()) {
            return "未检索到相关结果，请尝试更换关键词。";
        }
        StringBuilder result = new StringBuilder(String.format("检索完成，共 %d 条结果：%n%n", items.size()));
        int index = 1;
        for (WebSearchClient.RawResult item : items) {
            result.append(index++).append(". ").append(StrUtil.blankToDefault(item.title(), "(无标题)")).append('\n');
            if (StrUtil.isNotBlank(item.url())) result.append("   链接: ").append(item.url()).append('\n');
            if (StrUtil.isNotBlank(item.excerpt())) result.append("   摘录: ").append(item.excerpt()).append('\n');
            result.append('\n');
        }
        return result.toString().trim();
    }
}
