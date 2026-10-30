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
import com.nageoffer.ai.starlineagent.agent.tool.AgentMcpServerConfig;
import com.nageoffer.ai.starlineagent.agent.tool.AgentMcpServerRegistry;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@RequestMapping("/admin/mcp-servers")
public class McpServerAdminController {

    private final AgentMcpServerRegistry serverRegistry;
    private final AgentMcpClients mcpClients;

    @GetMapping
    public Result<List<McpServerVO>> list() {
        requireAdmin();
        mcpClients.refreshConnections();
        return Results.success(serverRegistry.list().stream().map(this::toVO).toList());
    }

    @PostMapping
    public Result<McpServerVO> create(@Valid @RequestBody McpServerSaveRequest request) {
        requireAdmin();
        AgentMcpServerConfig created = serverRegistry.create(request.name(), request.url());
        mcpClients.applyServers(serverRegistry.list());
        return Results.success(toVO(created));
    }

    @PutMapping("/{id}")
    public Result<McpServerVO> update(@PathVariable String id,
                                      @Valid @RequestBody McpServerSaveRequest request) {
        requireAdmin();
        AgentMcpServerConfig updated = serverRegistry.update(id, request.name(), request.url());
        mcpClients.applyServers(serverRegistry.list());
        return Results.success(toVO(updated));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        requireAdmin();
        serverRegistry.delete(id);
        mcpClients.applyServers(serverRegistry.list());
        return Results.success();
    }

    private McpServerVO toVO(AgentMcpServerConfig server) {
        return new McpServerVO(server.id(), server.name(), server.url(),
                mcpClients.isConnected(server.name()), mcpClients.toolCount(server.name()));
    }

    private void requireAdmin() {
        StpUtil.checkRole("admin");
    }

    public record McpServerSaveRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String name,
            @NotBlank @Size(max = 512) String url) {
    }

    public record McpServerVO(String id, String name, String url, boolean connected, int toolCount) {
    }
}
