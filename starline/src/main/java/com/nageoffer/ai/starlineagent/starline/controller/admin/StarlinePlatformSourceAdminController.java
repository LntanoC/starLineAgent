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

package com.nageoffer.ai.starlineagent.starline.controller.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.starline.controller.admin.request.ReviewStarlinePlatformSourceRequest;
import com.nageoffer.ai.starlineagent.starline.controller.admin.request.SaveStarlinePlatformSourceRequest;
import com.nageoffer.ai.starlineagent.starline.controller.admin.vo.StarlinePlatformSourceVO;
import com.nageoffer.ai.starlineagent.starline.service.StarlinePlatformSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/starline/admin/platform-sources")
@RequiredArgsConstructor
public class StarlinePlatformSourceAdminController {
    private final StarlinePlatformSourceService service;

    @GetMapping
    public Result<List<StarlinePlatformSourceVO>> list() {
        StpUtil.checkRole("admin");
        return Results.success(service.listCandidates());
    }

    @PostMapping
    public Result<String> create(@RequestBody SaveStarlinePlatformSourceRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SaveStarlinePlatformSourceRequest request) {
        StpUtil.checkRole("admin");
        service.update(id, request);
        return Results.success();
    }

    @PostMapping("/{id}/reviews")
    public Result<Void> review(@PathVariable String id, @RequestBody ReviewStarlinePlatformSourceRequest request) {
        StpUtil.checkRole("admin");
        service.review(id, request);
        return Results.success();
    }
}
