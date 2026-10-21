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

package com.nageoffer.ai.ragent.agent.trace;

import io.agentscope.core.agent.RuntimeContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 根 span 的结局回写：两条中断路径各写什么、谁能盖过谁，都在这里钉死
 */
class AgentRunTracerTest {

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private RuntimeContext ctx;
    private Span root;

    @BeforeEach
    void setUp() {
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingSpanExporter(exported)).build())
                .build();
        ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        root = tracerProvider.get("test").spanBuilder("invoke_agent ragent").startSpan();
        AgentRunTracer.bindRoot(ctx, root);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    /**
     * 优雅中断下框架自己收了尾也存了盘，官方那层随后写 OK，这里补一个 ERROR 只会被它盖掉
     * 更要紧的是不写 error.type：OK 配着一个错误类型，是自相矛盾的数据
     */
    @Test
    void shouldMarkGracefulInterruptWithoutStatusOrErrorType() {
        AgentRunTracer.markInterrupted(ctx);
        root.setStatus(StatusCode.OK);
        root.end();

        SpanData data = onlySpan();
        assertThat(data.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_INTERRUPTED);
        assertThat(data.getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_WARNING);
        assertThat(data.getAttributes().get(AgentTraceAttributes.STATUS_MESSAGE)).isNotBlank();
        // 反向钉桩：防止后人「顺手补一个」，造出 OK 配 error.type 的数据
        assertThat(data.getAttributes().get(AgentErrorTypes.KEY)).isNull();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    /**
     * 强制断流必然发生在优雅中断之后：先写的 interrupted 必须让位，否则真被掐链的运行永远显示成正常中断
     */
    @Test
    void shouldUpgradeInterruptedToAbortedOnForcedDisposal() {
        AgentRunTracer.markInterrupted(ctx);
        AgentRunTracer.markAborted(ctx);
        root.end();

        SpanData data = onlySpan();
        assertThat(data.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_ABORTED);
        assertThat(data.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.CANCELLED.value());
        assertThat(data.getAttributes().get(AgentTraceAttributes.LEVEL))
                .isEqualTo(AgentTraceAttributes.LEVEL_ERROR);
    }

    /**
     * 中断走的是 onComplete，官方与本仓的完成回调随后都会跑一遍，它们不能把中断这件事抹掉
     */
    @Test
    void shouldNotLetCompletionOverwriteInterrupt() {
        AgentRunTracer.markInterrupted(ctx);
        AgentRunTracer.markCompleted(ctx);
        root.end();

        assertThat(onlySpan().getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_INTERRUPTED);
    }

    /**
     * 掐链之后上游多半还会抛一次异常，同理不能把 aborted 降级成普通失败
     */
    @Test
    void shouldNotLetFailureOverwriteAbort() {
        AgentRunTracer.markAborted(ctx);
        AgentRunTracer.markFailed(ctx);
        root.end();

        assertThat(onlySpan().getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_ABORTED);
    }

    /**
     * 没被中断过的运行照常落 completed，级别不动：正常跑完不该在 Langfuse 里带任何告警色
     */
    @Test
    void shouldWriteCompletedOutcomeOnNormalFinish() {
        AgentRunTracer.markCompleted(ctx);
        root.end();

        SpanData data = onlySpan();
        assertThat(data.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_COMPLETED);
        assertThat(data.getAttributes().get(AgentTraceAttributes.LEVEL)).isNull();
        assertThat(data.getAttributes().get(AgentErrorTypes.KEY)).isNull();
    }

    /**
     * 失败的级别与文案由 onAgent 的错误回调写，这里只补结局，两处各写各的免得盖掉更具体的报错原文
     */
    @Test
    void shouldWriteFailedOutcomeWithoutTouchingLevel() {
        AgentRunTracer.markFailed(ctx);
        root.end();

        SpanData data = onlySpan();
        assertThat(data.getAttributes().get(RagentAttributes.RUN_OUTCOME))
                .isEqualTo(RagentAttributes.RUN_OUTCOME_FAILED);
        assertThat(data.getAttributes().get(AgentTraceAttributes.LEVEL)).isNull();
    }

    /**
     * 追踪关掉时上下文里没有根 span，中断链路照跑不误
     */
    @Test
    void shouldStayQuietWhenNoRootSpanBound() {
        RuntimeContext bare = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();

        assertThatCode(() -> {
            AgentRunTracer.markInterrupted(bare);
            AgentRunTracer.markAborted(bare);
            AgentRunTracer.markCompleted(null);
            AgentRunTracer.markFailed(null);
        }).doesNotThrowAnyException();

        root.end();
        assertThat(onlySpan().getAttributes().get(RagentAttributes.RUN_OUTCOME)).isNull();
    }

    private SpanData onlySpan() {
        assertThat(exported).hasSize(1);
        return exported.get(0);
    }
}
