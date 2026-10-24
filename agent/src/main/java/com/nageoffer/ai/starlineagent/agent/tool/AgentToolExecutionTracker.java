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

package com.nageoffer.ai.starlineagent.agent.tool;

import com.nageoffer.ai.starlineagent.agent.enums.AgentToolStatus;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Records tool-body timing and results for the self-hosted Agent run audit. */
public final class AgentToolExecutionTracker {

    private AgentToolExecutionTracker() {
    }

    public static Mono<ToolResultBlock> track(ToolCallParam param, Supplier<Mono<ToolResultBlock>> body) {
        return Mono.defer(() -> {
            RuntimeContext runtimeContext = param == null ? null : param.getRuntimeContext();
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(runtimeContext);
            if (facts == null) {
                return Mono.defer(body::get);
            }

            String toolCallId = param == null || param.getToolUseBlock() == null
                    ? null : param.getToolUseBlock().getId();
            facts.markStarted(toolCallId);
            AtomicBoolean settled = new AtomicBoolean();
            return Mono.defer(body::get)
                    .doOnSuccess(result -> {
                        if (settled.compareAndSet(false, true)) {
                            AgentToolStatus status = AgentToolStatus.of(result == null ? null : result.getState());
                            if (status == AgentToolStatus.INTERRUPTED) {
                                facts.markTerminated(toolCallId);
                            } else {
                                facts.markEnded(toolCallId);
                            }
                            facts.markReturned(toolCallId, result);
                        }
                    })
                    .doOnError(error -> {
                        if (settled.compareAndSet(false, true)) {
                            facts.markEnded(toolCallId);
                            facts.markReturned(toolCallId, null);
                        }
                    })
                    .doOnCancel(() -> {
                        if (settled.compareAndSet(false, true)) {
                            facts.markTerminated(toolCallId);
                        }
                    });
        });
    }
}
