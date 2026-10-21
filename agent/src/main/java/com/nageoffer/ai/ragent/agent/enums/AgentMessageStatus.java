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

/**
 * Agent 消息状态，与 rag 侧 MessageStatus 分立
 */
public enum AgentMessageStatus {

    /**
     * 正常完成
     */
    NORMAL,

    /**
     * 用户中断，内容为已生成的部分
     */
    INTERRUPTED,

    /**
     * 挂起在写操作确认上，唯一的非终态；用户点头或拒绝后续跑并改回 NORMAL
     */
    AWAITING_CONFIRM
}
