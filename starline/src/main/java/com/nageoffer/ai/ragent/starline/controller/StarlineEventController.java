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

package com.nageoffer.ai.ragent.starline.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.starline.controller.request.SaveStarlineEventRequest;
import com.nageoffer.ai.ragent.starline.controller.vo.StarlineEventVO;
import com.nageoffer.ai.ragent.starline.service.StarlineEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/starline/events")
@RequiredArgsConstructor
public class StarlineEventController {
    private final StarlineEventService service;

    @GetMapping
    public Result<List<StarlineEventVO>> listMine() {
        return Results.success(service.listMine());
    }

    @PostMapping
    public Result<String> create(@RequestBody SaveStarlineEventRequest request) {
        return Results.success(service.create(request));
    }

    @DeleteMapping("/{id}/reminder")
    public Result<Void> cancelReminder(@PathVariable String id) {
        service.cancelReminder(id);
        return Results.success();
    }
}