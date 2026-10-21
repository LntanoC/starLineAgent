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
 * 长期记忆条目，纯追加表：失效走 invalidAt 不删行，审计链完整
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_agent_memory")
public class AgentMemoryDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    /**
     * 自足陈述句，脱离上下文也读得懂
     */
    private String content;

    /**
     * 见 AgentMemorySourceType
     */
    private String sourceType;

    /**
     * 为空即 ACTIVE，注入与仲裁都只看这一列
     */
    private Date invalidAt;

    /**
     * 取代者ID；撤回的行留空
     */
    private String supersededBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
