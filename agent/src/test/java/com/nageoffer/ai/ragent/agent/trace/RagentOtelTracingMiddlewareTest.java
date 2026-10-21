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

import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 官方那层的 execute_tool 盖着整批调用却写着单工具语义，这里换成不冒充工具的 tool_batch
 * 断言的是导出快照：span 名、属性分层、四种批结局、以及取消时的收口时刻
 */
class RagentOtelTracingMiddlewareTest {

    private static final String RUN_ID = "t-9001";
    private static final String TOOL_BATCH_SPAN = "tool_batch";
    private static final String ROOT_SPAN = "invoke_agent";

    private static ContextPropagationOperator reactorHook;

    private List<SpanData> exported;
    private SdkTracerProvider tracerProvider;
    private MutableClock clock;
    private AgentToolExecutionFacts facts;
    private RagentOtelTracingMiddleware middleware;
    private AgentToolBatchMiddleware batchMiddleware;

    /**
     * 不装这个 Hook，runWithContext 原样返回，子层取不到父亲，整棵树的形状都不成立
     */
    @BeforeAll
    static void installReactorHook() {
        reactorHook = ContextPropagationOperator.builder().build();
        reactorHook.registerOnEachOperator();
    }

    @AfterAll
    static void resetReactorHook() {
        reactorHook.resetOnEachOperator();
    }

    @BeforeEach
    void setUp() {
        exported = Collections.synchronizedList(new ArrayList<>());
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(new CollectingExporter(exported)).build())
                .build();
        clock = new MutableClock(Instant.now());
        facts = new AgentToolExecutionFacts(RUN_ID, clock);
        middleware = new RagentOtelTracingMiddleware(tracerProvider.get("test"));
        batchMiddleware = new AgentToolBatchMiddleware();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    /**
     * 单工具跑完：批节点只说自己是一批，不写任何单工具语义的标准属性
     */
    @Test
    void shouldOpenOneFixedNameBatchSpanCarryingNoToolSemantics() {
        RuntimeContext ctx = context();
        body("call-1", 12);

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.SUCCESS))));

        SpanData batch = batchSpan();
        // 名字里带工具名或调用 ID 就是把动态参数塞进 span 名，后端按名聚合当场炸开
        assertThat(batch.getName()).isEqualTo(TOOL_BATCH_SPAN);
        assertThat(batch.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(batch.getParentSpanId()).isEqualTo(span(ROOT_SPAN).getSpanId());
        assertThat(batch.getAttributes().asMap().keySet().stream().map(AttributeKey::getKey))
                .noneMatch(key -> key.startsWith("gen_ai."));
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_ID)).isEqualTo(RUN_ID + "-0");
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_SIZE)).isEqualTo(1L);
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
        // 跑顺了不写 error.type，也不落 OK：内层还要往同一个 span 上写更坏的结论
        assertThat(batch.getAttributes().get(AgentErrorTypes.KEY)).isNull();
        assertThat(batch.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(batch.getEvents()).isEmpty();
        // 一次执行一个批节点，同名并行也不该多出第二个
        assertThat(exported.stream().filter(data -> TOOL_BATCH_SPAN.equals(data.getName()))).hasSize(1);
    }

    /**
     * 同名并行两条：批只报人数，谁快谁慢是各自工具节点的事
     */
    @Test
    void shouldReportBatchSizeForParallelCallsWithIdenticalName() {
        RuntimeContext ctx = context();
        body("call-1", 12);
        body("call-2", 30);

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1"), leaveSubmit("call-2")), Flux.just(
                new ToolResultStartEvent("r1", "call-2", "leave_submit"),
                new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.SUCCESS),
                new ToolResultEndEvent("r1", "call-2", "leave_submit", ToolResultState.SUCCESS))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_SIZE)).isEqualTo(2L);
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
    }

    /**
     * 等确认：一步没跑，只在批上记结局不建子 span——给它一个几毫秒的 span 就是把人类等待写成执行耗时
     */
    @Test
    void shouldOnlyMarkOutcomeWithoutChildSpanWhenAwaitingConfirmation() {
        RuntimeContext ctx = context();
        ToolUseBlock write = leaveSubmit("call-1");
        ToolUseBlock read = leaveSubmit("call-2");

        runUnderRoot(acting(ctx, List.of(write, read), Flux.just(
                new RequireUserConfirmEvent("r1", List.of(write, read)))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_AWAITING);
        // 逐条待确认的调用由内层中间件写进产出名册，批上不再落 OTel 事件：LangFuse 摄取时会丢掉它们
        assertThat(batch.getEvents()).isEmpty();
        assertThat(exported.stream().filter(data -> !ROOT_SPAN.equals(data.getName()))).hasSize(1);
    }

    /**
     * 整批被拒：同样一步没跑，批级结局是 denied
     */
    @Test
    void shouldMarkDeniedOutcomeWhenUserRefusesWholeBatch() {
        RuntimeContext ctx = context();
        ToolUseBlock write = leaveSubmit("call-1");

        runUnderRoot(acting(ctx, List.of(write), Flux.just(new AllToolsDeniedEvent(List.of(write)))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_DENIED);
        assertThat(batch.getEvents()).isEmpty();
    }

    /**
     * 一条被拒一条真跑：批级结局取 executed，写「整批被拒」会把真发出去的那条写没
     */
    @Test
    void shouldPreferExecutedOverDeniedWhenBatchOutcomeIsMixed() {
        RuntimeContext ctx = context();
        // 序号在归批时按名册分配，被拒那条也占一个位；订阅后才起工具体，顺序照真实链路摆
        Flux<AgentEvent> events = Flux.defer(() -> {
            body("call-2", 18);
            return Flux.just(
                    new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.DENIED),
                    new ToolResultStartEvent("r1", "call-2", "leave_submit"),
                    new ToolResultEndEvent("r1", "call-2", "leave_submit", ToolResultState.SUCCESS));
        });

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1"), leaveSubmit("call-2")), events));

        assertThat(batchSpan().getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
    }

    /**
     * 工具报错：批照样算跑过，批节点自己不写 error.type——失败属于那一条调用，不属于这一批
     * 跑过就不该被判成短路，判据是事实源里有没有起点，不是结果状态好不好看
     */
    @Test
    void shouldStillCountBatchAsExecutedWhenOneToolFails() {
        RuntimeContext ctx = context();
        body("call-1", 25);

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.ERROR))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
        assertThat(batch.getAttributes().get(AgentErrorTypes.KEY)).isNull();
        assertThat(batch.getEvents()).isEmpty();
        assertThat(facts.toolFact("call-1").shortCircuitReason()).isNull();
    }

    /**
     * 技能遮蔽：工具体一步没跑，全树不许出现工具语义节点，遮蔽标识只留在事实源上
     */
    @Test
    void shouldNotOpenAnyToolSpanWhenMaskingRefusesTheCall() {
        RuntimeContext ctx = context();
        // 遮蔽判定在工具体之前，结果事件到达时事实源已经带着原因，这里照真实顺序摆
        facts.markShortCircuit("call-1", AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED, "leave");

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.ERROR))));

        SpanData batch = batchSpan();
        assertThat(batch.getEvents()).isEmpty();
        // 遮蔽的两项事实只在这里，追踪侧不再复制一份
        assertThat(facts.toolFact("call-1").shortCircuitReason())
                .isEqualTo(AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED);
        assertThat(facts.toolFact("call-1").shortCircuitDetail()).isEqualTo("leave");
        // 「零 span」按全树扫描断言，不是「时长很短」
        assertThat(exported.stream().filter(data -> data.getName().startsWith("execute_tool"))).isEmpty();
        // 批级结局是四值闭集，没有短路那一档，整批全遮蔽时退回兜底值 executed
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
    }

    /**
     * 框架在进入工具体之前否掉的调用：短路不计进「跑过」，也不许留下起止时刻
     */
    @Test
    void shouldNotCountShortCircuitedCallWhenResultArrivesWithoutBodyRecord() {
        RuntimeContext ctx = context();

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.ERROR))));

        SpanData batch = batchSpan();
        assertThat(batch.getEvents()).isEmpty();
        // 未注册、工具组停用、入参校验失败在这一层完全同形，分不出谁否的，故只记一个钝原因
        assertThat(facts.toolFact("call-1").shortCircuitReason())
                .isEqualTo(AgentToolExecutionFacts.SHORT_CIRCUIT_PRE_EXECUTION);
        assertThat(facts.toolFact("call-1").shortCircuitDetail()).isNull();
        // 短路不该留下起止：给了就是凭空造出一段执行
        assertThat(facts.toolFact("call-1").startedAt()).isNull();
        assertThat(facts.toolFact("call-1").durationMs()).isNull();
        assertThat(exported.stream().filter(data -> data.getName().startsWith("execute_tool"))).isEmpty();
    }

    /**
     * 被拒与被中断本来就不进工具体，把它们也算成前置失败，等于给每一次人工拒绝都记一笔框架错误
     */
    @Test
    void shouldNotTreatDeniedOrInterruptedResultAsPreExecutionError() {
        RuntimeContext ctx = context();

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1"), leaveSubmit("call-2")), Flux.just(
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.DENIED),
                new ToolResultEndEvent("r1", "call-2", "leave_submit", ToolResultState.INTERRUPTED))));

        assertThat(batchSpan().getEvents()).isEmpty();
        assertThat(facts.toolFact("call-1").shortCircuitReason()).isNull();
        assertThat(facts.toolFact("call-2").shortCircuitReason()).isNull();
    }

    /**
     * 优雅中断：流自己正常结束，结局是 interrupted 但不是错误，写 error.type 会和根上的 OK 打架
     */
    @Test
    void shouldMarkInterruptedOutcomeWithoutErrorTypeOnGracefulStop() {
        RuntimeContext ctx = context();

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultStartEvent("r1", "call-1", "leave_submit"),
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.INTERRUPTED))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_INTERRUPTED);
        assertThat(batch.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(batch.getAttributes().get(AgentErrorTypes.KEY)).isNull();
    }

    /**
     * 流异常：error.type 取异常全限定名，不碰异常文案——文案里带员工姓名和单号
     */
    @Test
    void shouldRecordExceptionClassNameRatherThanMessage() {
        RuntimeContext ctx = context();

        assertThatCode(() -> runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")),
                Flux.error(new IllegalStateException("张三的请假单 L-77 提交失败")))))
                .isInstanceOf(IllegalStateException.class);

        SpanData batch = batchSpan();
        assertThat(batch.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(batch.getAttributes().get(AgentErrorTypes.KEY))
                .isEqualTo(IllegalStateException.class.getName());
        // 描述留空，异常正文只进 exception 事件，别顺着 status_message 漏出去
        assertThat(batch.getStatus().getDescription()).isEmpty();
        assertThat(batch.getEvents()).extracting(EventData::getName).contains("exception");
    }

    /**
     * 强制断流：未完的批对齐先写入的那个中断时刻，用断流当时的墙上时间就会把中断后那两秒算进耗时
     */
    @Test
    void shouldEndCancelledBatchAtTerminationInstant() {
        RuntimeContext ctx = context();
        Flux<AgentEvent> events = Flux.<AgentEvent>just(new ToolResultStartEvent("r1", "call-1", "leave_submit"))
                .concatWith(Flux.never());

        Disposable subscription = acting(ctx, List.of(leaveSubmit("call-1")), events).subscribe();
        clock.advance(40);
        facts.markInterrupted();
        clock.advance(2000);
        facts.markCancelled();
        subscription.dispose();

        SpanData batch = batchSpan();
        assertThat(batch.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(batch.getAttributes().get(AgentErrorTypes.KEY)).isEqualTo(AgentErrorTypes.CANCELLED.value());
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_INTERRUPTED);
        assertThat(batch.getEndEpochNanos())
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(facts.terminationAt()));
    }

    /**
     * 没有任何中断时刻的取消只可能来自上游超时，按结构判定，不去异常文案里找 timeout 字样
     */
    @Test
    void shouldFallBackToTimeoutWhenCancelledWithoutInterruptStamp() {
        RuntimeContext ctx = context();
        Flux<AgentEvent> events = Flux.<AgentEvent>just(new ToolResultStartEvent("r1", "call-1", "leave_submit"))
                .concatWith(Flux.never());

        acting(ctx, List.of(leaveSubmit("call-1")), events).subscribe().dispose();

        assertThat(batchSpan().getAttributes().get(AgentErrorTypes.KEY))
                .isEqualTo(AgentErrorTypes.TIMEOUT.value());
    }

    /**
     * 内层把批判成 ERROR 后流仍正常完成，收口不能补一句 OK：SDK 允许 OK 覆盖 ERROR，一补就把失败洗白
     */
    @Test
    void shouldNotOverwriteErrorStatusWrittenByInnerLayer() {
        RuntimeContext ctx = context();
        Flux<AgentEvent> inner = Flux.deferContextual(view -> {
            Span batchSpan = Span.fromContext(ContextPropagationOperator
                    .getOpenTelemetryContextFromContextView(view, Context.current()));
            batchSpan.setStatus(StatusCode.ERROR);
            return Flux.<AgentEvent>just(new ToolResultEndEvent("r1", "call-1", "leave_submit",
                    ToolResultState.ERROR));
        });
        body("call-1", 8);

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), inner));

        assertThat(batchSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    /**
     * 取不到事实源时照常放行，批次号缺失也不能把 acting 拦下来
     */
    @Test
    void shouldPassThroughWhenFactsAbsent() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();

        runUnderRoot(acting(ctx, List.of(leaveSubmit("call-1")), Flux.just(
                new ToolResultEndEvent("r1", "call-1", "leave_submit", ToolResultState.SUCCESS))));

        SpanData batch = batchSpan();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_ID)).isNull();
        assertThat(batch.getAttributes().get(RagentAttributes.TOOL_BATCH_OUTCOME))
                .isEqualTo(RagentAttributes.OUTCOME_EXECUTED);
    }

    /**
     * 只接管 acting 一个钩子：多覆写一个就等于把官方那份 span 骨架也接过来自己维护
     * Reactor Hook 的标志位留在父类且是类级的，子类 super() 复用它，全进程仍只装一次
     */
    @Test
    void shouldOverrideActingHookOnly() throws Exception {
        assertThat(Arrays.stream(RagentOtelTracingMiddleware.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsOnly("onActing");
        assertThat(Modifier.isStatic(OtelTracingMiddleware.class.getDeclaredField("hookRegistered").getModifiers()))
                .isTrue();
        assertThat(Arrays.stream(RagentOtelTracingMiddleware.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName()))
                .doesNotContain(boolean.class.getName());
    }

    private Flux<AgentEvent> acting(RuntimeContext ctx, List<ToolUseBlock> calls, Flux<AgentEvent> events) {
        return middleware.onActing(null, ctx, new ActingInput(calls),
                input -> batchMiddleware.onActing(null, ctx, input, ignored -> events));
    }

    private void runUnderRoot(Flux<AgentEvent> flux) {
        Span root = tracerProvider.get("test").spanBuilder(ROOT_SPAN).startSpan();
        try (Scope ignored = root.makeCurrent()) {
            flux.blockLast();
        } finally {
            root.end();
        }
    }

    /**
     * 工具体真跑过：埋点 helper 在真实链路上做的就是这两笔，测试里照做，免得「没跑过」的判定误伤
     */
    private void body(String toolCallId, long millis) {
        facts.markStarted(toolCallId);
        clock.advance(millis);
        facts.markEnded(toolCallId);
    }

    private RuntimeContext context() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u-1001").sessionId("c-2002").build();
        ctx.put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts);
        return ctx;
    }

    private static ToolUseBlock leaveSubmit(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("leave_submit")
                .input(Map.of("employeeName", "张三", "day", "2026-09-14"))
                .build();
    }

    private SpanData batchSpan() {
        return span(TOOL_BATCH_SPAN);
    }

    private SpanData span(String name) {
        return exported.stream().filter(data -> name.equals(data.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("未导出 span: " + name + ", 实际: "
                        + exported.stream().map(SpanData::getName).toList()));
    }

    /**
     * 中断时刻要能被测试摆到已知位置，系统时钟给不出可断言的终点
     */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private record CollectingExporter(List<SpanData> sink) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            sink.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
