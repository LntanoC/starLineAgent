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

package com.nageoffer.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.knowledge.dao.handler.StringListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 智能体技能实体
 * 技能是写给模型看的操作手册，正文纯 Markdown
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_agent_skill", autoResultMap = true)
public class AgentSkillDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 技能标识，模型加载正文时报的名字，如 leave_apply
     */
    private String skillCode;

    /**
     * 技能展示名
     */
    private String name;

    /**
     * 适用场景，随技能清单一起交给模型判断要不要加载正文
     */
    private String description;

    /**
     * 技能正文 Markdown
     */
    private String content;

    /**
     * 加载本技能后才解锁的 MCP 工具 ID，取值只能来自意图树的 MCP 节点
     */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> toolIds;

    /**
     * 排序，越小越靠前
     */
    private Integer sortOrder;

    /**
     * 是否启用
     */
    private Integer enabled;

    private String createBy;
    private String updateBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
