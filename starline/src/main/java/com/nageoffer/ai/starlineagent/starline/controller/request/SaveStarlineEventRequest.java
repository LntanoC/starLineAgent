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

package com.nageoffer.ai.starlineagent.starline.controller.request;

import lombok.Data;

/**
 * 活动记录。ticketTime 为空时仅表示待公布，不创建日历提醒。
 * 有开票时间时必须提交核验来源、确认标记和准确时间。
 */
@Data
public class SaveStarlineEventRequest {
    private String artistName;
    private String eventName;
    private String sourceName;
    private String sourceUrl;
    private Boolean sourceConfirmed;
    private String ticketTime;
}
