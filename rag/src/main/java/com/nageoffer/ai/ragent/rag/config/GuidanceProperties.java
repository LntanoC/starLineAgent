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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.guidance")
public class GuidanceProperties {

    /**
     * 是否启用引导式问答
     */
    private Boolean enabled = true;

    /**
     * 歧义阈值
     * 歧义判定按候选意图路径重名触发，该值当前不参与判定，保留以兼容既有 yaml
     */
    private Double ambiguityScoreRatio = 0.8D;

    /**
     * 歧义阈值缓冲区宽度
     * 同 {@link #ambiguityScoreRatio}，当前不参与判定
     */
    private Double ambiguityMargin = 0.15D;

    /**
     * 单次最多展示的选项数量
     */
    private Integer maxOptions = 6;
}
