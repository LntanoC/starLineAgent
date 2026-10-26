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

package com.nageoffer.ai.starlineagent.rag.core.vector;

import com.nageoffer.ai.starlineagent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.starlineagent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.starlineagent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorRetrieverService implements VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        float[] vector = embedAndNormalize(request.getQuery());
        return retrieveByVector(vector, request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        List<String> collectionNames = request.getEffectiveCollectionNames();
        if (collectionNames.isEmpty()) {
            return List.of();
        }
        // 单个或多个逻辑库都通过一条 SQL 过滤，LIMIT 是整个范围的总 TopK
        return queryByCollections(vector, collectionNames, request.getTopK(), request.getMetadataFilters());
    }

    @Override
    public float[] embedAndNormalize(String query) {
        return normalize(toArray(embeddingService.embed(query)));
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    /**
     * 在指定 collection 范围内执行一次向量相似度检索
     * <p>
     * 单库与全局共用此方法：单库传单元素列表，全局传多元素列表
     */
    private List<RetrievedChunk> queryByCollections(float[] vector,
                                                    List<String> collectionNames,
                                                    int limit,
                                                    Map<String, Object> requestedMetadataFilters) {
        // SET LOCAL 与查询处于同一只读事务连接，避免连接池换连接导致设置丢失或会话参数泄漏
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 200");
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");

        String vectorLiteral = toVectorLiteral(vector);
        String placeholders = collectionNames.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        Map<String, Object> metadataFilters = MetadataFilterSupport.normalize(requestedMetadataFilters);
        String metadataClause = metadataFilters.isEmpty() ? "" : " AND metadata @> ?::jsonb";

        List<Object> args = new ArrayList<>(collectionNames.size() + 4);
        args.add(vectorLiteral);
        args.addAll(collectionNames);
        if (!metadataFilters.isEmpty()) {
            args.add(MetadataFilterSupport.toJson(metadataFilters));
        }
        args.add(vectorLiteral);
        args.add(limit);

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query("SELECT id, content, collection_name, 1 - (embedding <=> ?::vector) AS score FROM t_knowledge_vector WHERE collection_name IN (" + placeholders + ")" + metadataClause + " ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .collectionName(rs.getString("collection_name"))
                        .score(rs.getFloat("score"))
                        .build(),
                args.toArray()
        );
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
