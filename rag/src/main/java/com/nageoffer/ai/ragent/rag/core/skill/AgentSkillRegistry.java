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

package com.nageoffer.ai.ragent.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能注册表
 * 运行期只读，Agent 侧从这里取已启用技能
 */
@Component
@RequiredArgsConstructor
public class AgentSkillRegistry {

    private final AgentSkillMapper agentSkillMapper;
    private final AgentSkillCacheManager cacheManager;

    /**
     * @return 已启用技能，按 sortOrder 升序；正文一并带出，加载时不必再查库
     */
    public List<AgentSkill> listEnabled() {
        List<AgentSkill> cached = cacheManager.getFromCache();
        if (cached != null) {
            return cached;
        }
        List<AgentSkill> skills = agentSkillMapper.selectList(
                        Wrappers.lambdaQuery(AgentSkillDO.class)
                                .eq(AgentSkillDO::getEnabled, 1)
                                .orderByAsc(AgentSkillDO::getSortOrder)
                                .orderByAsc(AgentSkillDO::getId))
                .stream()
                .map(AgentSkillRegistry::toSkill)
                .toList();
        cacheManager.saveToCache(skills);
        return skills;
    }

    /**
     * @return 指定技能，停用或不存在都返回 null
     */
    public AgentSkill findByCode(String skillCode) {
        if (StrUtil.isBlank(skillCode)) {
            return null;
        }
        String target = skillCode.trim();
        return listEnabled().stream()
                .filter(skill -> target.equals(skill.skillCode()))
                .findFirst()
                .orElse(null);
    }

    private static AgentSkill toSkill(AgentSkillDO record) {
        return new AgentSkill(record.getSkillCode(), record.getName(), record.getDescription(),
                record.getContent(), record.getToolIds());
    }
}
