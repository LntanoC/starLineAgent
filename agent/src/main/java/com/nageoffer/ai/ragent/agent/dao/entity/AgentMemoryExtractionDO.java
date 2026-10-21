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

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 抽取台账：既是审计日志，也是水位游标本身
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_agent_memory_extraction")
public class AgentMemoryExtractionDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String conversationId;

    /**
     * 本批首条用户消息ID，雪花自带时序
     */
    private String fromMessageId;

    /**
     * 本批末条用户消息ID，抽取结束后即新水位候选
     */
    private String toMessageId;

    /**
     * 见 AgentMemoryExtractionStatus
     */
    private String status;

    /**
     * 见 AgentMemoryTriggerType
     */
    private String triggerType;

    private Integer decisionCount;

    /**
     * 快照失配不计入，那不是这批内容的失败
     */
    private Integer attemptCount;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 非终态为空，僵尸抽取靠 createTime 判超时
     */
    private Date settleTime;
}
