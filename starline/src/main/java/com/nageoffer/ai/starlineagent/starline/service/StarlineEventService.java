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
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseIntake;
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseSignal;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.starline.controller.request.SaveStarlineEventRequest;
import com.nageoffer.ai.starlineagent.starline.controller.vo.StarlineEventVO;
import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineEventDO;
import com.nageoffer.ai.starlineagent.starline.dao.mapper.StarlineEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StarlineEventService {
    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELLED = "CANCELLED";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StarlineEventMapper mapper;
    private final ObjectProvider<BadCaseIntake> intake;

    public List<StarlineEventVO> listMine() {
        String userId = UserContext.requireUser().getUserId();
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                        .eq(StarlineEventDO::getUserId, userId)
                        .orderByDesc(StarlineEventDO::getCreatedTime))
                .stream().map(StarlineEventService::toVO).toList();
    }

    /** 日历订阅只读取当前用户仍关注且具有开票时间的活动。 */
    public List<StarlineEventDO> findCalendarEvents(String userId) {
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getUserId, userId)
                .in(StarlineEventDO::getReminderStatus, ACTIVE, CANCELLED)
                .isNotNull(StarlineEventDO::getTicketTime)
                .ge(StarlineEventDO::getTicketTime, LocalDateTime.now(ZONE))
                .orderByAsc(StarlineEventDO::getTicketTime));
    }

    @Transactional
    public String create(SaveStarlineEventRequest request) {
        return create(request, StarlineInvocationContext.userForm());
    }

    /** 保留业务规则 bad case 检测与 Agent 调用归因。 */
    @Transactional
    public String create(SaveStarlineEventRequest request, StarlineInvocationContext context) {
        try {
            return doCreate(request);
        } catch (StarlineRuleViolationException failure) {
            if (context != null && context.systemGenerated()) {
                reportRuleViolation(request, context, failure);
            }
            throw failure;
        }
    }

    private String doCreate(SaveStarlineEventRequest request) {
        String artistName = required(request.getArtistName(), "ARTIST_REQUIRED", "请填写艺人名称");
        String eventName = trim(request.getEventName());
        LocalDateTime now = LocalDateTime.now(ZONE);
        StarlineEventDO event = new StarlineEventDO();
        event.setUserId(UserContext.requireUser().getUserId());
        event.setArtistName(artistName);
        event.setEventName(eventName);
        event.setReminderStatus(ACTIVE);
        event.setCreatedTime(now);
        event.setUpdateTime(now);

        String rawTicketTime = trim(request.getTicketTime());
        if (rawTicketTime == null) {
            mapper.insert(event);
            return event.getId();
        }

        event.setEventName(required(eventName, "EVENT_NAME_REQUIRED", "已公布信息请填写活动名称"));
        event.setSourceName(required(request.getSourceName(), "SOURCE_REQUIRED", "已公布信息请填写来源名称"));
        event.setSourceUrl(validateUrl(request.getSourceUrl()));
        if (!Boolean.TRUE.equals(request.getSourceConfirmed())) {
            throw new StarlineRuleViolationException("SOURCE_NOT_CONFIRMED", "请先确认来源内容与活动信息一致");
        }

        LocalDateTime ticketTime = parseTime(rawTicketTime, "TICKET_TIME_REQUIRED", "请填写准确的开票时间");
        if (!ticketTime.isAfter(now)) {
            throw new StarlineRuleViolationException("TICKET_TIME_NOT_FUTURE", "开票时间必须晚于当前时间");
        }
        if (ticketTime.isBefore(now.plusMinutes(10))) {
            throw new StarlineRuleViolationException("TOO_CLOSE_TO_NOTIFY", "开票时间距今不足 10 分钟，赶不上提前提醒了");
        }

        event.setTicketTime(ticketTime);
        StarlineEventDO existing = mapper.selectOne(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getUserId, event.getUserId())
                .eq(StarlineEventDO::getArtistName, artistName)
                .eq(StarlineEventDO::getEventName, eventName)
                .eq(StarlineEventDO::getTicketTime, ticketTime)
                .eq(StarlineEventDO::getReminderStatus, ACTIVE)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        mapper.insert(event);
        return event.getId();
    }

    @Transactional
    public void cancelReminder(String id) {
        int updated = mapper.update(null, Wrappers.<StarlineEventDO>lambdaUpdate()
                .eq(StarlineEventDO::getId, id)
                .eq(StarlineEventDO::getUserId, UserContext.requireUser().getUserId())
                .eq(StarlineEventDO::getReminderStatus, ACTIVE)
                .set(StarlineEventDO::getReminderStatus, CANCELLED)
                .set(StarlineEventDO::getUpdateTime, LocalDateTime.now(ZONE)));
        if (updated == 0) {
            throw new ClientException("活动记录不存在或已取消");
        }
    }

    private void reportRuleViolation(SaveStarlineEventRequest request,
                                     StarlineInvocationContext context,
                                     StarlineRuleViolationException failure) {
        try {
            String user = UserContext.requireUser().getUserId();
            String normalized = String.valueOf(request.getArtistName()) + "|"
                    + request.getTicketTime() + "|" + request.getSourceConfirmed();
            String subject = sha256(user + "|" + failure.getRuleCode() + "|" + normalized
                    + "|" + (System.currentTimeMillis() / 3600000));
            BadCaseIntake sink = intake.getIfAvailable();
            if (sink == null) {
                return;
            }
            sink.submitSafely(new BadCaseSignal("STARLINE_BUSINESS", "STARLINE_RULE_VIOLATION",
                    "HIGH", "S3",
                    context.messageId() == null ? "STARLINE_RULE" : "AGENT_MESSAGE",
                    context.messageId() == null ? subject : context.messageId(),
                    context.messageId(), context.conversationId(), user, context.runId(),
                    "rule:" + subject,
                    "星序业务规则违例：" + failure.getRuleCode(), Instant.now(), "starline-rule-v1",
                    Map.of("ruleCode", failure.getRuleCode(),
                            "origin", context.origin().name(),
                            "requestSummary", normalized)));
        } catch (Exception ignored) {
            // 检测失败不能改变原始业务异常。
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StarlineEventVO toVO(StarlineEventDO event) {
        return new StarlineEventVO(event.getId(), event.getArtistName(), event.getEventName(),
                event.getSourceName(), event.getSourceUrl(), event.getTicketTime(),
                event.getReminderStatus(), event.getCreatedTime());
    }

    private static String required(String value, String message) {
        return required(value, "REQUIRED_FIELD_MISSING", message);
    }

    private static String required(String value, String ruleCode, String message) {
        String result = trim(value);
        if (result == null) {
            throw new StarlineRuleViolationException(ruleCode, message);
        }
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
                throw new StarlineRuleViolationException("INVALID_SOURCE_URL",
                        "来源链接必须是有效的 HTTP 或 HTTPS 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new StarlineRuleViolationException("INVALID_SOURCE_URL", "来源链接格式不正确");
        }
        return url;
    }

    private static LocalDateTime parseTime(String value, String ruleCode, String message) {
        try {
            return LocalDateTime.parse(required(value, message));
        } catch (DateTimeParseException exception) {
            throw new StarlineRuleViolationException(ruleCode, message);
        }
    }
}
