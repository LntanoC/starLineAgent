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

import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolBatchFact;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 只接管官方中间件的 acting 钩子，其余钩子与 Reactor 上下文透传原样沿用父类
 * <p>
 * 官方那层给整批工具建一个 `execute_tool` 并写上 `gen_ai.tool.*`，可它盖着的是一整批调用，
 * 单工具的耗时与成败它一个都给不出；等工具体自己建 span 之后，同一次执行还会被两个工具语义节点各表达一遍
 * 这里换成 `tool_batch`：只说「这是一批、几个人、整批跑没跑」，工具语义留给真正的执行体
 */
public class RagentOtelTracingMiddleware extends OtelTracingMiddleware {

    /**
     * 固定串，工具名与调用 ID 一律进属性：span 名带动态参数会让后端的按名聚合失去意义
     */
    private static final String SPAN_NAME = "tool_batch";

    private final Tracer tracer;

    public RagentOtelTracingMiddleware(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Context parent = AgentRunTracer.otelContext(ctxView);
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(ctx);
            // 内层批中间件此刻还没开批：defer 体自外向内跑，现在的批数正是本批将要落到的下标
            int batchIndex = facts == null ? -1 : facts.batchCount();
            List<ToolUseBlock> calls = input.toolCalls() == null ? List.of() : input.toolCalls();

            Span span = tracer.spanBuilder(SPAN_NAME)
                    .setParent(parent)
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_SPAN);
            span.setAttribute(RagentAttributes.TOOL_BATCH_SIZE, calls.size());

            BatchOutcome outcome = new BatchOutcome();
            AtomicBoolean ended = new AtomicBoolean();
            return ContextPropagationOperator.runWithContext(next.apply(input)
                    .doOnNext(event -> observe(facts, outcome, event))
                    .doOnComplete(() -> {
                        if (ended.compareAndSet(false, true)) {
                            // 不补 OK：内层可能已把这一批判成 ERROR，SDK 允许后写的 OK 覆盖它
                            settle(span, facts, batchIndex, outcome);
                            span.end();
                        }
                    })
                    .doOnError(error -> {
                        if (ended.compareAndSet(false, true)) {
                            settle(span, facts, batchIndex, outcome);
                            span.setStatus(StatusCode.ERROR);
                            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofThrowable(error));
                            span.recordException(error);
                            span.end();
                        }
                    })
                    .doOnCancel(() -> {
                        if (ended.compareAndSet(false, true)) {
                            outcome.interrupted = true;
                            settle(span, facts, batchIndex, outcome);
                            span.setStatus(StatusCode.ERROR);
                            Long terminationAt = facts == null ? null : facts.terminationAt();
                            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofCancelledSpan(terminationAt));
                            endAt(span, terminationAt);
                        }
                    }), span.storeInContext(parent));
        });
    }

    /**
     * 事件只用来判断整批究竟跑没跑，逐条真相由内层中间件写进批 span 的产出名册
     */
    private static void observe(AgentToolExecutionFacts facts, BatchOutcome outcome, AgentEvent event) {
        if (event instanceof RequireUserConfirmEvent) {
            outcome.awaiting = true;
        } else if (event instanceof AllToolsDeniedEvent) {
            outcome.denied = true;
        } else if (event instanceof ToolResultEndEvent end) {
            ToolFact fact = facts == null ? null : facts.toolFact(end.getToolCallId());
            // 短路的调用不计进「跑过」：混批里另有人被拒时，报 denied 才是这一批的真相
            // 短路与否问事实源，不看结果状态反推：状态只有四档，遮蔽与框架前置失败在它上面同形
            if (fact == null || fact.shortCircuitReason() == null) {
                outcome.member(AgentToolStatus.of(end.getState()));
            }
        }
    }

    /**
     * 批次号回读事实源，不在这里另算一个：算第二遍就有第二份真相
     */
    private static void settle(Span span, AgentToolExecutionFacts facts, int batchIndex, BatchOutcome outcome) {
        ToolBatchFact batch = facts == null ? null : facts.batchAt(batchIndex);
        if (batch != null) {
            span.setAttribute(RagentAttributes.TOOL_BATCH_ID, batch.batchId());
        }
        span.setAttribute(RagentAttributes.TOOL_BATCH_OUTCOME, outcome.resolve());
    }

    /**
     * 提前收口的 span 一律对齐中断时刻，用断流那一刻的墙上时间会把中断后的等待窗口算进耗时
     */
    private static void endAt(Span span, Long terminationAt) {
        if (terminationAt == null) {
            span.end();
            return;
        }
        span.end(Instant.ofEpochMilli(terminationAt));
    }

    /**
     * 批级结局只回答整批跑没跑，优先级 interrupted > awaiting > executed > denied
     * 混合结局取靠前那个：一条真发出去了还报「整批被拒」，就是把已经落地的写操作写没了
     */
    private static final class BatchOutcome {

        private volatile boolean awaiting;
        private volatile boolean denied;
        private volatile boolean executed;
        private volatile boolean interrupted;

        private void member(AgentToolStatus status) {
            switch (status) {
                case DENIED -> denied = true;
                case INTERRUPTED -> interrupted = true;
                // 拒绝与中断之外的终局都说明工具体真的跑过，成败是那一条调用自己的事
                default -> executed = true;
            }
        }

        private String resolve() {
            if (interrupted) {
                return RagentAttributes.OUTCOME_INTERRUPTED;
            }
            if (awaiting) {
                return RagentAttributes.OUTCOME_AWAITING;
            }
            if (denied && !executed) {
                return RagentAttributes.OUTCOME_DENIED;
            }
            // 一个事件都没收到时也报 executed：批已经交给框架执行，说它被拒或在等待都不是真话
            return RagentAttributes.OUTCOME_EXECUTED;
        }
    }
}
