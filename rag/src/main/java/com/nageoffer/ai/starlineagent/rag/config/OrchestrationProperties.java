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

package com.nageoffer.ai.starlineagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 执行架构配置
 * 不挂在 rag 下：AGENT 档里 RAG 管线只是主 Agent 的一个 Tool，档位在语义上包含 RAG 而非从属于它
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "starlineagent.engine")
public class OrchestrationProperties {

    /**
     * 唯一运行入口为 agent；旧 workflow 已下线
     */
    private String type = "agent";

    public OrchestrationMode getMode() {
        if (!"agent".equalsIgnoreCase(type == null ? "" : type.trim())) {
            throw new IllegalStateException("starlineagent.engine.type 只支持 agent，workflow 已下线");
        }
        return OrchestrationMode.AGENT;
    }
}
