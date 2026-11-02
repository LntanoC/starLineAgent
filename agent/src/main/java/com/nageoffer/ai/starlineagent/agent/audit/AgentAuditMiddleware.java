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

package com.nageoffer.ai.starlineagent.agent.audit;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.*;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.function.Function;

/**
 * AgentScope 中间件,透明地采集每次 Agent 运行的审计轨迹。
 * <p>
 * 纯观察者设计:审计绝不能改变或破坏 Agent 主流程,因此所有采集调用都通过
 * {@link #observe} 包装,采集失败时只会将会话降级为 partial,而不会向上抛异常。
 * 所有下游 Flux 都包在 {@code defer} 中,保证每次订阅时才延迟解析会话(链路可能
 * 在其他线程执行),而不是在拦截时解析。
 * <p>
 * 生命周期覆盖:{@link #onReasoning} 推进轮次计数,{@link #onModelCall} 记录模型名称、
 * 输入、流式输出与 token 用量,{@link #onActing} 记录工具调用批次及其结果,
 * {@link #onAgent} 捕获达到最大迭代次数等终态事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAuditMiddleware implements MiddlewareBase {
    private final AgentAuditSerializer serializer;

    /**
     * 所有采集操作的防御性包装:审计失败只会降级轨迹(标记会话 partial),
     * 绝不能失败或阻塞 Agent 主流程。
     */
    private void observe(AgentAuditSession s,Runnable body){
        try{body.run();}catch(Exception e){s.partial();log.warn("Agent audit capture failed, type={}",e.getClass().getSimpleName());}
    }
    /**
     * Agent 级事件:检测 {@link ExceedMaxItersEvent} 并将本次运行标记为失败,
     * 因为 Agent 放弃后不会再触发后续的 reasoning/model/tool 钩子。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext ctx,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next){
        return Flux.defer(()->next.apply(input)).doOnNext(e->{
            var s=AgentAuditSession.from(ctx);
            if(s!=null && e instanceof ExceedMaxItersEvent) observe(s,()->s.fail(new IllegalStateException("Agent 达到最大迭代次数")));
        });
    }


    /**
     * 推理阶段:一次钩子调用对应一轮 ReAct,在委托下游前先推进轮次计数。
     * 会话查找刻意放在 {@code defer} 内延迟执行;会话为 null 表示本次运行未启用审计。
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent,RuntimeContext ctx,ReasoningInput input,Function<ReasoningInput,Flux<AgentEvent>> next){
        return Flux.defer(()->{var s=AgentAuditSession.from(ctx);if(s!=null)observe(s,s::nextRound);return next.apply(input);});
    }
    /**
     * 模型调用阶段:调用前先打开模型节点,随后将每个流式事件(文本/思考增量、
     * 工具调用增量、token 用量)喂入该节点,并在完成/出错/取消时以终态关闭它。
     * <p>
     * 模型名称解析顺序:本次调用 options &gt; 模型名称 &gt; 兜底标签。
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent,RuntimeContext ctx,ModelCallInput input,Function<ModelCallInput,Flux<AgentEvent>> next){
        return Flux.defer(()->{
            var s=AgentAuditSession.from(ctx);if(s==null)return next.apply(input);
            AgentAuditSession.ModelState state;
            try{state=s.model(input.options()==null?"模型调用":(input.options().getModelName()==null?input.model().getModelName():input.options().getModelName()),serializer.modelInput(input));}
            catch(Exception e){s.partial();return next.apply(input);}
            return Flux.defer(()->next.apply(input))
                    .doOnNext(e->observe(s,()->s.modelEvent(state,e)))
                    .doOnComplete(()->observe(s,()->s.endModel(state,"SUCCESS",null)))
                    .doOnError(e->observe(s,()->{s.fail(e);s.endModel(state,"FAILED",e);}))
                    .doOnCancel(()->observe(s,()->s.endModel(state,"INTERRUPTED",null)));
        });
    }
    /**
     * 行动阶段:执行前注册工具调用批次,随后将工具结果事件(开始/增量/结束、
     * 用户确认、全部拒绝)路由到对应的工具节点。取消记为中断而非失败。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent,RuntimeContext ctx,ActingInput input,Function<ActingInput,Flux<AgentEvent>> next){
        return Flux.defer(()->{
            var s=AgentAuditSession.from(ctx);if(s==null)return next.apply(input);
            List<String> keys;
            try{keys=s.beginTools(input.toolCalls()==null?List.of():input.toolCalls());}
            catch(Exception e){s.partial();return next.apply(input);}
            return Flux.defer(()->next.apply(input))
                    .doOnNext(e->observe(s,()->s.toolEvent(keys,e)))
                    .doOnComplete(()->observe(s,()->s.endTools(keys,null,false)))
                    .doOnError(e->observe(s,()->{s.fail(e);s.endTools(keys,e,false);}))
                    .doOnCancel(()->observe(s,()->s.endTools(keys,null,true)));
        });
    }
}
