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

package com.nageoffer.ai.ragent.agent.dao.entity;

import lombok.Data;

import java.util.Date;

/**
 * 每用户一行的长期记忆控制面，读写全走注解 Mapper（要 ON CONFLICT 与 FOR UPDATE）
 */
@Data
public class AgentMemoryControlDO {

    private String userId;

    /**
     * 记忆集版本号，提交期与水位一同双校验
     */
    private Long revision;

    /**
     * 建行时刻兼抽取下界，接入之前的历史消息永不倒灌
     */
    private Date createTime;

    private Date updateTime;
}
