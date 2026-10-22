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

import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineEventDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starline.email", name = "enabled", havingValue = "true")
public class StarlineReminderScheduler {
    private final StarlineEventService service;
    private final JavaMailSender mailSender;
    private final StarlineMailProperties properties;

    @Scheduled(fixedDelayString = "${starline.reminder.poll-interval-ms:15000}")
    public void dispatchDueReminders() {
        for (StarlineEventDO event : service.findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) {
            if (!service.claim(event.getId())) continue;
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(properties.from());
                message.setTo(event.getEmail());
                message.setSubject("星序提醒：" + event.getArtistName() + " · " + event.getEventName());
                message.setText(("你设置的演出/开票提醒时间已到。\n\n艺人：%s\n活动：%s\n开票时间：%s"
                        + "\n信息来源：%s\n来源链接：%s\n\n请以官方来源的最新信息为准。")
                        .formatted(event.getArtistName(), event.getEventName(), event.getTicketTime(),
                                event.getSourceName(), event.getSourceUrl()));
                mailSender.send(message);
                service.mark(event.getId(), "SENT", LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            } catch (Exception exception) {
                log.error("Starline reminder delivery failed, eventId={}", event.getId(), exception);
                service.mark(event.getId(), "FAILED", null);
            }
        }
    }
}