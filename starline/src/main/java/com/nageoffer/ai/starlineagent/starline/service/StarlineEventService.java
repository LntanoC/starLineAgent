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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.starline.controller.request.SaveStarlineEventRequest;
import com.nageoffer.ai.starlineagent.starline.controller.vo.StarlineEventVO;
import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineEventDO;
import com.nageoffer.ai.starlineagent.starline.dao.mapper.StarlineEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class StarlineEventService {
    private static final String ANNOUNCED = "ANNOUNCED";
    private static final String TO_BE_ANNOUNCED = "TO_BE_ANNOUNCED";
    private static final String PENDING = "PENDING";
    private final StarlineEventMapper mapper;

    public List<StarlineEventVO> listMine() {
        String userId = UserContext.requireUser().getUserId();
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getUserId, userId)
                .orderByDesc(StarlineEventDO::getCreatedTime))
                .stream().map(StarlineEventService::toVO).toList();
    }

    @Transactional
    public String create(SaveStarlineEventRequest request) {
        String status = required(request.getInfoStatus(), "请选择信息状态").toUpperCase(Locale.ROOT);
        if (!ANNOUNCED.equals(status) && !TO_BE_ANNOUNCED.equals(status)) throw new ClientException("信息状态不合法");
        StarlineEventDO event = new StarlineEventDO();
        event.setUserId(UserContext.requireUser().getUserId());
        event.setArtistName(required(request.getArtistName(), "请填写艺人名称"));
        event.setEventName(trim(request.getEventName()));
        event.setInfoStatus(status);
        event.setCreatedTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        event.setUpdateTime(event.getCreatedTime());

        if (TO_BE_ANNOUNCED.equals(status)) {
            event.setReminderStatus("NOT_CREATED");
            mapper.insert(event);
            return event.getId();
        }

        event.setEventName(required(request.getEventName(), "已公布信息请填写活动名称"));
        event.setSourceName(required(request.getSourceName(), "请填写信息来源"));
        event.setSourceUrl(validateUrl(request.getSourceUrl()));
        if (!Boolean.TRUE.equals(request.getSourceConfirmed())) throw new ClientException("请先确认来源内容与活动信息一致");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime ticketTime = parseTime(request.getTicketTime(), "请填写准确的开票时间");
        LocalDateTime remindAt = parseTime(request.getRemindAt(), "请填写提醒时间");
        if (!ticketTime.isAfter(now)) throw new ClientException("开票时间必须晚于当前时间");
        if (!remindAt.isAfter(now)) throw new ClientException("提醒时间必须晚于当前时间");
        if (remindAt.isAfter(ticketTime)) throw new ClientException("提醒时间不能晚于开票时间");
        String email = required(request.getEmail(), "请填写接收提醒的邮箱");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ClientException("邮箱格式不正确");
        event.setTicketTime(ticketTime);
        event.setRemindAt(remindAt);
        event.setEmail(email);
        event.setReminderStatus(PENDING);
        mapper.insert(event);
        return event.getId();
    }

    @Transactional
    public void cancelReminder(String id) {
        int updated = mapper.update(null, Wrappers.<StarlineEventDO>lambdaUpdate()
                .eq(StarlineEventDO::getId, id)
                .eq(StarlineEventDO::getUserId, UserContext.requireUser().getUserId())
                .eq(StarlineEventDO::getReminderStatus, PENDING)
                .set(StarlineEventDO::getReminderStatus, "CANCELLED")
                .set(StarlineEventDO::getUpdateTime, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        if (updated == 0) throw new ClientException("提醒不存在、已开始发送或已不能取消");
    }

    public List<StarlineEventDO> findDue(LocalDateTime now) {
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getReminderStatus, PENDING)
                .le(StarlineEventDO::getRemindAt, now)
                .orderByAsc(StarlineEventDO::getRemindAt).last("LIMIT 50"));
    }

    public boolean claim(String id) {
        return mapper.claimForSending(id, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))) == 1;
    }

    @Transactional
    public void mark(String id, String status, LocalDateTime sentAt) {
        StarlineEventDO event = new StarlineEventDO();
        event.setId(id);
        event.setReminderStatus(status);
        event.setSentAt(sentAt);
        event.setUpdateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        mapper.updateById(event);
    }

    private static String required(String value, String message) {
        String result = trim(value);
        if (result == null) throw new ClientException(message);
        return result;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validateUrl(String value) {
        String url = required(value, "请填写来源链接");
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ClientException("来源链接必须是有效的 HTTP 或 HTTPS 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new ClientException("来源链接格式不正确");
        }
        return url;
    }

    private static LocalDateTime parseTime(String value, String message) {
        try {
            return LocalDateTime.parse(required(value, message));
        } catch (DateTimeParseException exception) {
            throw new ClientException(message);
        }
    }

    private static StarlineEventVO toVO(StarlineEventDO e) {
        return new StarlineEventVO(e.getId(), e.getArtistName(), e.getEventName(), e.getInfoStatus(),
                e.getSourceName(), e.getSourceUrl(), e.getTicketTime(), e.getEmail(), e.getRemindAt(),
                e.getReminderStatus(), e.getSentAt(), e.getCreatedTime());
    }
}