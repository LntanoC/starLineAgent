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
 * 抽取的触发方，两条入口共用同一条管道，只在门槛与失败反馈上分叉
 */
public enum AgentMemoryTriggerType {

    /**
     * 模型调工具，同步等结果，待处理一条即执行
     */
    FLUSH,

    /**
     * 轮次释放后异步，待处理满门槛才执行
     */
    BACKGROUND
}
