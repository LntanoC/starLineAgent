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

package com.nageoffer.ai.ragent.starline.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_starline_event_reminder")
public class StarlineEventDO {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String userId;
    private String artistName;
    private String eventName;
    private String infoStatus;
    private String sourceName;
    private String sourceUrl;
    private LocalDateTime ticketTime;
    private String email;
    private LocalDateTime remindAt;
    private String reminderStatus;
    private LocalDateTime sentAt;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
}