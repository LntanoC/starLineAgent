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

package com.nageoffer.ai.ragent.agent.enums;

import io.agentscope.core.message.ToolResultState;
import lombok.AllArgsConstructor;

/**
 * 工具调用状态，SSE、落库块与 LangFuse trace 共用这一套取值
 * 框架状态到它的映射只此一处，前端与追踪各映一遍迟早会漂成两种解释
 */
@AllArgsConstructor
public enum AgentToolStatus {

    /**
     * 模型已决定调用，尚未开跑
     */
    PENDING("pending"),

    /**
     * 批已进入执行
     */
    RUNNING("running"),

    /**
     * 整批推迟，等人点确认
     */
    AWAITING("awaiting"),

    /**
     * 执行成功
     */
    DONE("done"),

    /**
     * 执行报错
     */
    FAILED("failed"),

    /**
     * 被拒绝，框架直接合成结果，工具本体没被调用
     */
    DENIED("denied"),

    /**
     * 跑到一半断了，写操作可能已经发出去
     */
    INTERRUPTED("interrupted");

    private final String value;

    public String value() {
        return value;
    }

    /**
     * 未定终局的两档，收尾时要判成 interrupted，挂起时要判成 awaiting
     */
    public boolean isOpen() {
        return this == PENDING || this == RUNNING;
    }

    /**
     * 框架结果状态到本状态，RUNNING 出现在结束事件里说明执行被打断了
     */
    public static AgentToolStatus of(ToolResultState state) {
        if (state == null) {
            return FAILED;
        }
        return switch (state) {
            case SUCCESS -> DONE;
            case DENIED -> DENIED;
            case INTERRUPTED, RUNNING -> INTERRUPTED;
            case ERROR -> FAILED;
        };
    }
}
