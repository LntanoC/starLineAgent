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

package com.nageoffer.ai.starlineagent.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.starlineagent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.starlineagent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolUpdateRequest;
import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolConfigureRequest;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.service.McpToolRegistryService;
import com.nageoffer.ai.starlineagent.rag.service.ToolRagService;
import com.nageoffer.ai.starlineagent.rag.service.impl.McpToolVectorService;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Admin-only MCP registry, Tool RAG recall, and vector maintenance endpoints. */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@RequestMapping("/admin/mcp-tools")
public class McpToolAdminController {

    private final McpToolRegistryService registry;
    private final AgentMcpClients mcpClients;
    private final ToolRagService toolRagService;
    private final McpToolVectorService vectorService;

    @GetMapping
    public Result<List<McpToolAdminVO>> list() {
        requireAdmin();
        mcpClients.refreshConnections();
        List<McpToolAdminVO> result = registry.listAll().stream()
                .map(this::toVO)
                .sorted(Comparator.comparing(McpToolAdminVO::sortOrder)
                        .thenComparing(McpToolAdminVO::toolId))
                .toList();
        return Results.success(result);
    }

    /** New remote tools are added disabled; an administrator must explicitly enable and review them. */
    @PostMapping("/sync")
    public Result<SyncResult> sync() {
        requireAdmin();
        mcpClients.refreshConnections();
        int discovered = 0;
        int registered = 0;
        for (var entry : mcpClients.allTools().entrySet()) {
            RemoteTool remote = entry.getValue();
            String description = remote.definition().description();
            if (registry.registerDiscovered(entry.getKey(), entry.getKey(), description) > 0) {
                registered++;
            }
            discovered++;
        }
        return Results.success(new SyncResult(discovered, registered));
    }

    @PutMapping("/{toolId}")
    public Result<Void> update(@PathVariable String toolId, @Valid @RequestBody McpToolUpdateRequest request) {
        requireAdmin();
        registry.update(toolId, request);
        return Results.success();
    }

    /** Seed an explicitly reviewed tool manifest; unlike sync, this may enable known tools. */
    @PostMapping("/configure")
    public Result<Integer> configure(@Valid @RequestBody ConfigureBatchRequest request) {
        requireAdmin();
        List<McpToolConfigureRequest> requests = request.tools();
        for (McpToolConfigureRequest item : requests) {
            registry.configure(item);
        }
        return Results.success(requests.size());
    }

    @PostMapping("/rebuild-vectors")
    public Result<RebuildResult> rebuildVectors() {
        requireAdmin();
        return Results.success(new RebuildResult(vectorService.rebuildDirty()));
    }

    @PostMapping("/recall-test")
    public Result<RecallTestResult> recallTest(@Valid @RequestBody RecallTestRequest request) {
        requireAdmin();
        mcpClients.refreshConnections();
        ToolRagService.RecallResult recall = toolRagService.recallForTest(request.query().trim());
        List<RecallItem> residents = recall.residents().stream()
                .map(tool -> toRecallItem(tool, true, 1.0, "resident"))
                .toList();
        List<RecallItem> recalled = recall.recalled().stream()
                .map(hit -> toRecallItem(hit.tool(), false, hit.score(), hit.source()))
                .toList();
        return Results.success(new RecallTestResult(residents, recalled, recall.vectorFallback()));
    }

    private McpToolAdminVO toVO(McpToolDO row) {
        RemoteTool remote = mcpClients.get(row.getToolId());
        Boolean readOnlyHint = null;
        if (remote != null) {
            ToolAnnotations annotations = remote.definition().annotations();
            readOnlyHint = annotations == null ? null : annotations.readOnlyHint();
        }
        return new McpToolAdminVO(row.getToolId(), row.getDisplayName(), row.getDescription(),
                Integer.valueOf(1).equals(row.getRequireConfirm()), Integer.valueOf(1).equals(row.getResident()),
                Integer.valueOf(1).equals(row.getDispatchable()), Integer.valueOf(1).equals(row.getEnabled()),
                row.getSortOrder(), row.getRemark(), row.getUpdateTime(), remote != null, readOnlyHint);
    }

    private RecallItem toRecallItem(McpToolDO tool, boolean resident, double score, String source) {
        return new RecallItem(tool.getToolId(), tool.getDisplayName(), resident, score, source,
                mcpClients.get(tool.getToolId()) != null);
    }

    private void requireAdmin() {
        StpUtil.checkRole("admin");
    }

    public record McpToolAdminVO(String toolId, String displayName, String description,
                                 boolean requireConfirm, boolean resident, boolean dispatchable,
                                 boolean enabled, Integer sortOrder, String remark,
                                 java.util.Date updateTime, boolean discovered, Boolean readOnlyHint) {
    }

    public record SyncResult(int discovered, int newlyRegistered) {
    }

    public record ConfigureBatchRequest(@NotEmpty List<@Valid McpToolConfigureRequest> tools) {
    }

    public record RebuildResult(int rebuilt) {
    }

    public record RecallTestRequest(@NotBlank @Size(max = 1000) String query) {
    }

    public record RecallTestResult(List<RecallItem> residents, List<RecallItem> recalled,
                                   boolean vectorFallback) {
        public RecallTestResult {
            residents = List.copyOf(residents);
            recalled = List.copyOf(recalled);
        }
    }

    public record RecallItem(String toolId, String displayName, boolean resident,
                              double score, String source, boolean discovered) {
    }
}
