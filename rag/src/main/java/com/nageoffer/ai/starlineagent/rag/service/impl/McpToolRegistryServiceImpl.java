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

package com.nageoffer.ai.starlineagent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolUpdateRequest;
import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolConfigureRequest;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.dao.mapper.McpToolMapper;
import com.nageoffer.ai.starlineagent.rag.service.McpToolRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class McpToolRegistryServiceImpl implements McpToolRegistryService {

    private final McpToolMapper mapper;

    @Override
    public List<McpToolDO> listAll() {
        return mapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getDeleted, 0)
                .orderByAsc(McpToolDO::getSortOrder)
                .orderByAsc(McpToolDO::getToolId));
    }

    @Override
    public List<McpToolDO> listEnabled() {
        return mapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getEnabled, 1)
                .eq(McpToolDO::getDeleted, 0)
                .orderByAsc(McpToolDO::getSortOrder)
                .orderByAsc(McpToolDO::getToolId));
    }

    @Override
    public List<McpToolDO> listDispatchable() {
        return mapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getEnabled, 1)
                .eq(McpToolDO::getDispatchable, 1)
                .eq(McpToolDO::getDeleted, 0)
                .orderByAsc(McpToolDO::getSortOrder)
                .orderByAsc(McpToolDO::getToolId));
    }

    @Override
    public Map<String, McpToolDO> enabledById() {
        Map<String, McpToolDO> result = new LinkedHashMap<>();
        listDispatchable().forEach(tool -> result.put(tool.getToolId(), tool));
        return Map.copyOf(result);
    }

    @Override
    @Transactional
    public void update(String toolId, McpToolUpdateRequest request) {
        McpToolDO row = find(toolId);
        String displayName = request.displayName().trim();
        String description = request.description().trim();
        if (description.isEmpty()) {
            throw new ClientException("工具描述不能为空");
        }
        row.setDisplayName(displayName);
        row.setDescription(description);
        row.setDescriptionHash(sha256(description));
        row.setRequireConfirm(request.requireConfirm() ? 1 : 0);
        row.setResident(request.resident() ? 1 : 0);
        row.setDispatchable(request.dispatchable() ? 1 : 0);
        row.setEnabled(request.enabled() ? 1 : 0);
        row.setSortOrder(Objects.requireNonNullElse(request.sortOrder(), 900));
        row.setRemark(request.remark() == null ? null : request.remark().trim());
        mapper.updateById(row);
    }

    @Override
    @Transactional
    public void configure(McpToolConfigureRequest request) {
        registerDiscovered(request.toolId(), request.displayName(), request.description());
        update(request.toolId(), new McpToolUpdateRequest(request.displayName(), request.description(),
                request.requireConfirm(), request.resident(), request.dispatchable(), request.enabled(),
                request.sortOrder(), request.remark()));
    }

    @Override
    @Transactional
    public int registerDiscovered(String toolId, String displayName, String description) {
        if (toolId == null || toolId.isBlank()) {
            return 0;
        }
        String normalizedDescription = description == null || description.isBlank()
                ? "MCP 工具：" + toolId : description.trim();
        McpToolDO existing = mapper.selectOne(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getToolId, toolId)
                .eq(McpToolDO::getDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return 0;
        }
        mapper.insert(McpToolDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .toolId(toolId)
                .displayName(displayName == null || displayName.isBlank() ? toolId : displayName.trim())
                .description(normalizedDescription)
                .descriptionHash(sha256(normalizedDescription))
                .requireConfirm(0)
                .resident(0)
                .dispatchable(0)
                .enabled(0)
                .sortOrder(900)
                .remark("新发现工具，默认停用；管理员审核后再启用")
                .deleted(0)
                .build());
        return 1;
    }

    private McpToolDO find(String toolId) {
        McpToolDO row = mapper.selectOne(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getToolId, toolId)
                .eq(McpToolDO::getDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) {
            throw new ClientException("MCP 工具不存在: " + toolId);
        }
        return row;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算工具描述指纹", e);
        }
    }
}
