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

import com.nageoffer.ai.starlineagent.infra.config.AIModelProperties;
import com.nageoffer.ai.starlineagent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolVectorDO;
import com.nageoffer.ai.starlineagent.rag.dao.mapper.McpToolVectorMapper;
import com.nageoffer.ai.starlineagent.rag.service.McpToolRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolVectorService {

    private final McpToolVectorMapper mapper;
    private final McpToolRegistryService registry;
    private final EmbeddingService embeddingService;
    private final AIModelProperties modelProperties;

    private volatile Map<String, StoredVector> vectorCache;

    public synchronized int rebuildDirty() {
        String modelId = embeddingModelId();
        Map<String, StoredVector> stored = loadVectors();
        int rebuilt = 0;
        for (McpToolDO tool : registry.listEnabled()) {
            String hash = sha256(tool.getDescription());
            StoredVector existing = stored.get(tool.getToolId());
            if (existing != null && existing.modelId().equals(modelId)
                    && existing.descriptionHash().equals(hash)) {
                continue;
            }
            List<Float> values = embeddingService.embed(tool.getDescription(), modelId);
            float[] vector = toArray(values);
            if (vector.length == 0) {
                throw new IllegalStateException("Embedding 模型返回空向量: " + modelId);
            }
            mapper.upsert(tool.getToolId(), modelId, vector.length, toBytes(vector), hash);
            log.info("MCP 工具描述向量已更新, toolId={}, dimension={}, modelId={}",
                    tool.getToolId(), vector.length, modelId);
            rebuilt++;
        }
        vectorCache = null;
        return rebuilt;
    }

    public Map<String, Double> cosineScores(String question, Collection<McpToolDO> tools) {
        if (tools == null || tools.isEmpty()) {
            return Map.of();
        }
        String modelId = embeddingModelId();
        Map<String, StoredVector> current = loadVectors();
        Map<String, McpToolDO> eligible = tools.stream()
                .filter(tool -> {
                    StoredVector vector = current.get(tool.getToolId());
                    return vector != null && vector.modelId().equals(modelId)
                            && vector.descriptionHash().equals(sha256(tool.getDescription()));
                })
                .collect(Collectors.toMap(McpToolDO::getToolId, tool -> tool, (a, b) -> a,
                        LinkedHashMap::new));
        if (eligible.isEmpty()) {
            return Map.of();
        }

        float[] query = toArray(embeddingService.embed(question, modelId));
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String toolId : eligible.keySet()) {
            float[] vector = current.get(toolId).vector();
            if (query.length != vector.length) {
                log.warn("MCP 工具向量维度不一致，跳过语义分数, toolId={}, queryDimension={}, toolDimension={}",
                        toolId, query.length, vector.length);
                continue;
            }
            double score = cosine(query, vector);
            scores.put(toolId, Math.max(0, Math.min(1, score)));
        }
        return scores;
    }

    public String embeddingModelId() {
        String configured = modelProperties.getEmbedding().getDefaultModel();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return modelProperties.getEmbedding().getCandidates().stream()
                .map(AIModelProperties.ModelCandidate::getId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未配置 MCP 工具 Embedding 模型"));
    }

    private Map<String, StoredVector> loadVectors() {
        Map<String, StoredVector> current = vectorCache;
        if (current == null) {
            synchronized (this) {
                current = vectorCache;
                if (current == null) {
                    Map<String, StoredVector> loaded = new LinkedHashMap<>();
                    for (McpToolVectorDO row : mapper.selectAll()) {
                        float[] vector = fromBytes(row.getEmbedding(), row.getDimension() == null ? 0 : row.getDimension());
                        if (vector.length == row.getDimension()) {
                            loaded.put(row.getToolId(), new StoredVector(row.getModelId(),
                                    row.getDescriptionHash(), vector));
                        }
                    }
                    current = Map.copyOf(loaded);
                    vectorCache = current;
                }
            }
        }
        return current;
    }

    private float[] toArray(List<Float> values) {
        if (values == null) {
            return new float[0];
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i) == null ? 0 : values.get(i);
        }
        return result;
    }

    private byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] fromBytes(byte[] bytes, int dimension) {
        if (bytes == null || dimension <= 0 || bytes.length != dimension * Float.BYTES) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算工具描述指纹", e);
        }
    }

    private record StoredVector(String modelId, String descriptionHash, float[] vector) {
    }
}
