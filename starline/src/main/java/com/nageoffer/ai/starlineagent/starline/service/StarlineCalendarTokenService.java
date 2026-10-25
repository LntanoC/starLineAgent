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

package com.nageoffer.ai.starlineagent.starline.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineCalendarTokenDO;
import com.nageoffer.ai.starlineagent.starline.dao.mapper.StarlineCalendarTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class StarlineCalendarTokenService {
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StarlineCalendarTokenMapper mapper;

    @Transactional
    public String ensureToken(String userId) {
        StarlineCalendarTokenDO existing = findActiveByUser(userId);
        if (existing != null) {
            return existing.getToken();
        }
        return issueOrReadActive(userId);
    }

    @Transactional
    public String reset(String userId) {
        mapper.update(null, Wrappers.<StarlineCalendarTokenDO>lambdaUpdate()
                .eq(StarlineCalendarTokenDO::getUserId, userId)
                .eq(StarlineCalendarTokenDO::getRevoked, 0)
                .set(StarlineCalendarTokenDO::getRevoked, 1));
        return issueOrReadActive(userId);
    }

    /** Returns null for invalid credentials; the token grants read-only access to one calendar. */
    @Transactional
    public String resolveUserId(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        StarlineCalendarTokenDO row = mapper.selectOne(Wrappers.<StarlineCalendarTokenDO>lambdaQuery()
                .eq(StarlineCalendarTokenDO::getToken, token)
                .eq(StarlineCalendarTokenDO::getRevoked, 0)
                .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        mapper.update(null, Wrappers.<StarlineCalendarTokenDO>lambdaUpdate()
                .eq(StarlineCalendarTokenDO::getId, row.getId())
                .eq(StarlineCalendarTokenDO::getRevoked, 0)
                .set(StarlineCalendarTokenDO::getLastAccessTime, LocalDateTime.now(ZONE)));
        return row.getUserId();
    }

    private String issueOrReadActive(String userId) {
        StarlineCalendarTokenDO existing = findActiveByUser(userId);
        if (existing != null) {
            return existing.getToken();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            SECURE_RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            StarlineCalendarTokenDO row = new StarlineCalendarTokenDO();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setUserId(userId);
            row.setToken(token);
            row.setCreatedTime(LocalDateTime.now(ZONE));
            row.setRevoked(0);
            mapper.insertIfUserHasNoActiveToken(row);
            existing = findActiveByUser(userId);
            if (existing != null) {
                return existing.getToken();
            }
        }
        throw new IllegalStateException("Unable to create calendar subscription token");
    }

    private StarlineCalendarTokenDO findActiveByUser(String userId) {
        return mapper.selectOne(Wrappers.<StarlineCalendarTokenDO>lambdaQuery()
                .eq(StarlineCalendarTokenDO::getUserId, userId)
                .eq(StarlineCalendarTokenDO::getRevoked, 0)
                .orderByDesc(StarlineCalendarTokenDO::getCreatedTime)
                .last("LIMIT 1"));
    }
}
