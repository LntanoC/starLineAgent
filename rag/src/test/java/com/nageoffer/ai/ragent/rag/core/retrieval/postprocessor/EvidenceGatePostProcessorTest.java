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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据相关性闸门的放行 / 丢弃判定
 * <p>
 * 每个用例的 {@code score} 与 {@code rerankScore} 都设成分叉值，实现若退回读 {@code getScore()} 必须变红
 */
class EvidenceGatePostProcessorTest {

    private static final float RRF_SCORE = 0.03f;

    private static EvidenceGatePostProcessor gateWithFloor(double minRerankScore) {
        return gateWithFloor(minRerankScore, true);
    }

    private static EvidenceGatePostProcessor gateWithFloor(double minRerankScore, boolean rerankEnabled) {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getEvidence().setMinRerankScore(minRerankScore);
        RAGConfigProperties ragConfig = new RAGConfigProperties();
        ragConfig.setRerankEnabled(rerankEnabled);
        return new EvidenceGatePostProcessor(props, ragConfig);
    }

    private static RetrievedChunk chunk(String id, Float score, Float rerankScore) {
        return RetrievedChunk.builder().id(id).text(id).score(score).rerankScore(rerankScore).build();
    }

    private static List<RetrievedChunk> gate(EvidenceGatePostProcessor processor, List<RetrievedChunk> chunks) {
        return processor.process(chunks, List.of(), SearchContext.builder().build());
    }

    @Test
    @DisplayName("无精排分可读时放行：noop 降级不打分，照拦会在上游最不稳时关掉 KB 侧")
    void passesThroughWhenRerankScoreIsAbsent() {
        List<RetrievedChunk> chunks = List.of(chunk("a", RRF_SCORE, null), chunk("b", RRF_SCORE, null));
        assertEquals(2, gate(gateWithFloor(0.2), chunks).size());
    }

    @Test
    @DisplayName("精排分过线则放行，与 score 上残留的 RRF 量级无关")
    void keepsEvidenceWhenRerankScoreClearsFloor() {
        List<RetrievedChunk> chunks = List.of(chunk("a", RRF_SCORE, 0.85f), chunk("b", RRF_SCORE, 0.10f));
        assertEquals(2, gate(gateWithFloor(0.2), chunks).size(), "整批凭最高分过闸，弱证据跟着一起留");
    }

    @Test
    @DisplayName("最高精排分低于下限则整批丢弃，哪怕 score 上是高位余弦")
    void dropsWholeBatchWhenTopRerankScoreIsBelowFloor() {
        List<RetrievedChunk> chunks = List.of(chunk("a", 0.92f, 0.05f), chunk("b", 0.88f, 0.01f));
        assertTrue(gate(gateWithFloor(0.2), chunks).isEmpty());
    }

    @Test
    @DisplayName("取整批最高分而非首条：RerankClient 未承诺返回序，回填条目本就没分")
    void usesBatchMaxNotFirstChunk() {
        List<RetrievedChunk> chunks = List.of(chunk("a", RRF_SCORE, 0.01f), chunk("b", RRF_SCORE, null), chunk("c", RRF_SCORE, 0.90f));
        assertEquals(3, gate(gateWithFloor(0.2), chunks).size());
    }

    @Test
    @DisplayName("恰好等于下限视为过线，边界不误杀")
    void keepsEvidenceExactlyAtFloor() {
        List<RetrievedChunk> chunks = List.of(chunk("a", RRF_SCORE, 0.2f));
        assertEquals(1, gate(gateWithFloor(0.2), chunks).size());
    }

    @Test
    @DisplayName("非有限精排分按缺分处理：NaN 参与比较会把毒值抬成最高分，反而永远过闸")
    void treatsNonFiniteRerankScoreAsAbsent() {
        List<RetrievedChunk> chunks = List.of(chunk("a", 0.92f, Float.NaN), chunk("b", 0.88f, null));
        assertEquals(2, gate(gateWithFloor(0.2), chunks).size(), "全批无有效分应走放行路径");
    }

    @Test
    @DisplayName("下限 <=0 即关闭闸门，是配置侧的回退路径")
    void disabledWhenFloorNotPositive() {
        SearchContext context = SearchContext.builder().build();
        assertFalse(gateWithFloor(0).isEnabled(context));
        assertFalse(gateWithFloor(-1).isEnabled(context));
        assertTrue(gateWithFloor(0.2).isEnabled(context));
    }

    @Test
    @DisplayName("空输入原样返回")
    void passesThroughEmptyInput() {
        assertTrue(gate(gateWithFloor(0.2), List.of()).isEmpty());
    }

    @Test
    @DisplayName("闸门开着却关了精排：判据永远读不到，启动即失败而不是上线后恒放行")
    void refusesToStartWhenGateIsOnButRerankIsOff() {
        assertThrows(IllegalStateException.class, () -> gateWithFloor(0.2, false).afterPropertiesSet());
    }

    @Test
    @DisplayName("闸门关掉则不管精排开关，两者只在闸门开着时才互相约束")
    void allowsRerankOffWhenGateIsOff() {
        gateWithFloor(0, false).afterPropertiesSet();
        gateWithFloor(0.2, true).afterPropertiesSet();
    }
}
