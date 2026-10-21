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

package com.nageoffer.ai.ragent.framework.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatQuestionTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldRejectBlankQuestion() {
        // 空问题一路进到 Agent 才被发现，那时已经开了会话、落了库
        assertThat(violations("  ")).singleElement()
                .satisfies(message -> assertThat(message).contains("不能为空"));
    }

    @Test
    void shouldRejectQuestionBeyondLimit() {
        // 超长的 GET 查询串会先撞容器请求头上限，换来一个说不清缘由的 400
        assertThat(violations("问".repeat(ChatQuestion.MAX_LENGTH + 1))).singleElement()
                .satisfies(message -> assertThat(message).contains("过长"));
    }

    @Test
    void shouldAcceptOrdinaryQuestion() {
        assertThat(violations("Ragent 的检索链路是怎么排的")).isEmpty();
    }

    private List<String> violations(String question) {
        return VALIDATOR.validate(new Holder(question)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    private static final class Holder {

        @ChatQuestion
        private final String question;

        private Holder(String question) {
            this.question = question;
        }
    }
}
