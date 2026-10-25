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
    private final BadCaseDetectionCoordinator coordinator;
    private final BadCaseEvalSampleService samples;
    @GetMapping
    public Result<BadCaseRepository.Page> page(@RequestParam(required=false) String reviewStatus,
            @RequestParam(required=false) String rootCause,
            @RequestParam(required=false) String triageStatus,@RequestParam(required=false) String confidence,
            @RequestParam(required=false) String source,@RequestParam(required=false) String severity,
            @RequestParam(required=false) String subjectType,@RequestParam(defaultValue="false") boolean measurement,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        StpUtil.checkRole("admin");
        return Results.success(repository.page(reviewStatus,rootCause,triageStatus,confidence,source,severity,subjectType,measurement,Math.max(1,page),Math.max(1,Math.min(100,size))));
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
    public Result<Map<String,Object>> scan(@RequestParam(defaultValue="true") boolean includeOlder,
            @RequestParam(required=false) String detector,@RequestParam(defaultValue="apply") String mode) {
        StpUtil.checkRole("admin");
        if(!java.util.Set.of("dryRun","apply").contains(mode))throw new com.nageoffer.ai.starlineagent.framework.exception.ClientException("无效扫描模式");
        return Results.success(coordinator.scan(detector,"dryRun".equals(mode),includeOlder));
    }
    @GetMapping("/{id}/signals") public Result<java.util.List<Map<String,Object>>> signals(@PathVariable String id){StpUtil.checkRole("admin");service.detail(id);return Results.success(repository.signals(id));}
    @GetMapping("/{id}/review-history") public Result<java.util.List<Map<String,Object>>> history(@PathVariable String id){StpUtil.checkRole("admin");service.detail(id);return Results.success(repository.reviewHistory(id));}
    @PostMapping("/{id}/promote") public Result<Void> promote(@PathVariable String id,@RequestBody Map<String,Long> body){StpUtil.checkRole("admin");if(body.get("revision")==null||repository.promote(id,body.get("revision"))!=1)throw new com.nageoffer.ai.starlineagent.framework.exception.ClientException("版本冲突或记录已经人工处理");return Results.success();}
    @GetMapping("/eval-samples") public Result<java.util.List<Map<String,Object>>> samples(@RequestParam(required=false)String candidateId){StpUtil.checkRole("admin");return Results.success(samples.list(candidateId));}
    @PostMapping("/{id}/eval-sample") public Result<String> draft(@PathVariable String id,@RequestBody BadCaseEvalSampleService.Draft draft){StpUtil.checkRole("admin");return Results.success(samples.save(id,draft,UserContext.getUserId()));}
    @PostMapping("/eval-samples/{id}/approve") public Result<Void> approve(@PathVariable String id,@RequestBody Map<String,Long> body){StpUtil.checkRole("admin");if(body.get("revision")==null)throw new com.nageoffer.ai.starlineagent.framework.exception.ClientException("缺少版本号");samples.approve(id,body.get("revision"),UserContext.getUserId());return Results.success();}
    @GetMapping("/eval-samples/export") public Result<java.util.List<Map<String,Object>>> export(@RequestParam String datasetName,@RequestParam(defaultValue="CONVERSATION")String testLane){StpUtil.checkRole("admin");return Results.success(samples.export(datasetName,testLane));}
    @GetMapping("/eval-samples/manifest") public Result<java.util.List<Map<String,Object>>> manifest(@RequestParam String datasetName){StpUtil.checkRole("admin");return Results.success(samples.manifest(datasetName));}
    @PostMapping("/eval-samples/import-result") public Result<Map<String,Object>> importResult(@RequestBody BadCaseEvalSampleService.ImportRequest body,jakarta.servlet.http.HttpServletRequest request){
        StpUtil.checkRole("admin");
        String remote=request.getRemoteAddr();
        boolean local=remote.equals("127.0.0.1")||remote.equals("::1")||remote.equals("0:0:0:0:0:0:0:1");
        try{local|=java.net.InetAddress.getByName(remote).isSiteLocalAddress()&&remote.endsWith(".1");}catch(Exception ignored){}
        if(!local||request.getHeader("X-Forwarded-For")!=null||request.getHeader("X-Real-IP")!=null)throw new com.nageoffer.ai.starlineagent.framework.exception.ClientException("结果导入仅允许通过服务器本机/SSH隧道直连API，不能经公网前端代理");
        return Results.success(samples.importResults(body));
    }
}
