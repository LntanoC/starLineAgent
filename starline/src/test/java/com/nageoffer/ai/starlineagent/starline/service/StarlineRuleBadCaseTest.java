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

import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseIntake;
import com.nageoffer.ai.starlineagent.framework.context.LoginUser;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.starline.controller.request.SaveStarlineEventRequest;
import com.nageoffer.ai.starlineagent.starline.dao.mapper.StarlineEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StarlineRuleBadCaseTest {
    private final StarlineEventMapper mapper = mock(StarlineEventMapper.class);
    private final BadCaseIntake intake = mock(BadCaseIntake.class);

    @SuppressWarnings("unchecked")
    private StarlineEventService service() {
        ObjectProvider<BadCaseIntake> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(intake);
        return new StarlineEventService(mapper, provider);
    }

    @BeforeEach
    void user() {
        UserContext.set(LoginUser.builder().userId("u1").username("test").build());
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private SaveStarlineEventRequest request() {
        SaveStarlineEventRequest request = new SaveStarlineEventRequest();
        request.setArtistName("测试艺人");
        request.setEventName("测试活动");
        request.setSourceName("测试官方");
        request.setSourceUrl("https://example.com/test");
        request.setSourceConfirmed(true);
        request.setTicketTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
        return request;
    }

    @Test
    void rejectedUserFormIsNotBadCase() {
        SaveStarlineEventRequest request = request();
        request.setSourceConfirmed(false);
        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(StarlineRuleViolationException.class)
                .extracting("ruleCode").isEqualTo("SOURCE_NOT_CONFIRMED");
        verifyNoInteractions(intake);
        verifyNoInteractions(mapper);
    }

    @Test
    void agentViolationHasStableCodeAndRunAttribution() {
        SaveStarlineEventRequest request = request();
        request.setTicketTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(5).toString());
        assertThatThrownBy(() -> service().create(request,
                new StarlineInvocationContext(StarlineInvocationContext.Origin.AGENT_TOOL, "run", "m", "c")))
                .isInstanceOf(StarlineRuleViolationException.class);
        verify(intake).submitSafely(org.mockito.ArgumentMatchers.argThat(signal ->
                signal.messageId().equals("m")
                        && signal.runId().equals("run")
                        && signal.evidence().get("ruleCode").equals("TOO_CLOSE_TO_NOTIFY")));
    }

    @Test
    void unconfirmedSourceFromAgentIsRecordedWithoutMatchingChineseText() {
        SaveStarlineEventRequest request = request();
        request.setSourceConfirmed(false);
        assertThatThrownBy(() -> service().create(request,
                new StarlineInvocationContext(StarlineInvocationContext.Origin.AGENT_TOOL, "run", "m", "c")))
                .isInstanceOf(StarlineRuleViolationException.class);
        verify(intake).submitSafely(org.mockito.ArgumentMatchers.argThat(signal ->
                signal.evidence().get("ruleCode").equals("SOURCE_NOT_CONFIRMED")));
    }
}
