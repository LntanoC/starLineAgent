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

/**
 * 业务 ID 在 RuntimeContext 里的键名，服务层写、追踪中间件读
 * 追踪关掉时这几个值只是躺在上下文里没人取，不影响执行
 */
public final class AgentTraceContextKeys {

    /**
     * 本次流式任务号，前端停止按钮与服务端任务表用的也是它
     */
    public static final String TASK_ID = "ragent_task_id";

    /**
     * 本轮答复挂在哪条用户消息下，凭它从 PG 的消息记录反查到这条链路
     */
    public static final String REPLY_TO_MESSAGE_ID = "ragent_reply_to_message_id";

    /**
     * 确认续跑时被结算的那张确认卡片所在消息，首问为空
     */
    public static final String CONFIRM_MESSAGE_ID = "ragent_confirm_message_id";

    private AgentTraceContextKeys() {
    }
}
