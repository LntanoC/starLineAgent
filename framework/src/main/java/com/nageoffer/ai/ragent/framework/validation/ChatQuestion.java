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

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 对话入口的问题参数约束，RAG 与 Agent 两条链路共用一套口径
 * 上限按 GET 查询串取：中文 URL 编码后约 9 字节一字，再多会先撞容器 8KB 请求头上限
 */
@Documented
@NotBlank(message = "问题不能为空")
@Size(max = ChatQuestion.MAX_LENGTH, message = "问题过长，最多 " + ChatQuestion.MAX_LENGTH + " 字")
@Constraint(validatedBy = {})
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ChatQuestion {

    int MAX_LENGTH = 500;

    String message() default "问题参数不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
