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

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAuditMiddleware implements MiddlewareBase {
    private final AgentAuditSerializer serializer;
    private void observe(AgentAuditSession s,Runnable body){
        try{body.run();}catch(Exception e){s.partial();log.warn("Agent audit capture failed, type={}",e.getClass().getSimpleName());}
    }
    @Override
    public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext ctx,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next){
        return Flux.defer(()->next.apply(input)).doOnNext(e->{
            var s=AgentAuditSession.from(ctx);
            if(s!=null && e instanceof ExceedMaxItersEvent) observe(s,()->s.fail(new IllegalStateException("Agent 达到最大迭代次数")));
        });
    }
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent,RuntimeContext ctx,ReasoningInput input,Function<ReasoningInput,Flux<AgentEvent>> next){
        return Flux.defer(()->{var s=AgentAuditSession.from(ctx);if(s!=null)observe(s,s::nextRound);return next.apply(input);});
    }
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
