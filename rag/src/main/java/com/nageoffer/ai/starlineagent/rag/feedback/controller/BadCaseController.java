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

package com.nageoffer.ai.starlineagent.rag.feedback.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.rag.feedback.badcase.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/rag/bad-cases")
@RequiredArgsConstructor
public class BadCaseController {
    private final BadCaseService service;
    private final BadCaseRepository repository;
    private final BadCaseRepairService repairService;
    @GetMapping
    public Result<BadCaseRepository.Page> page(@RequestParam(required=false) String reviewStatus,
            @RequestParam(required=false) String rootCause,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        StpUtil.checkRole("admin");
        return Results.success(service.page(reviewStatus,rootCause,page,size));
    }
    @GetMapping("/stats")
    public Result<Map<String,Object>> stats() { StpUtil.checkRole("admin"); return Results.success(repository.stats()); }
    @GetMapping("/{id}")
    public Result<BadCaseRecord> detail(@PathVariable String id) { StpUtil.checkRole("admin"); return Results.success(service.detail(id)); }
    @PutMapping("/{id}/review")
    public Result<Void> review(@PathVariable String id,@RequestBody BadCaseService.ReviewRequest request) {
        StpUtil.checkRole("admin");service.review(id,request,UserContext.getUserId());return Results.success();
    }
    @PostMapping("/{id}/apply-mapping")
    public Result<String> applyMapping(@PathVariable String id,@RequestBody BadCaseRepairService.MappingRequest request) {
        StpUtil.checkRole("admin");return Results.success(repairService.applyMapping(id,request,UserContext.getUserId()));
    }
    @PostMapping("/scan")
    public Result<BadCaseService.ScanResult> scan(@RequestParam(defaultValue="true") boolean includeOlder) {
        StpUtil.checkRole("admin");return Results.success(service.scan(includeOlder));
    }
}
