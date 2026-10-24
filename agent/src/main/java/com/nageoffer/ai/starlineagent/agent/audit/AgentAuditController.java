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

package com.nageoffer.ai.starlineagent.agent.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/agent/runs")
@RequiredArgsConstructor
public class AgentAuditController {
    private final AgentAuditRepository repository;
    @GetMapping
    public Result<IPage<AgentAuditRun>> page(AgentAuditPageRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(repository.page(request));
    }
    @GetMapping("/by-message/{messageId}")
    public Result<Detail> byMessage(@PathVariable String messageId) {
        StpUtil.checkRole("admin");
        AgentAuditRun run=repository.findByMessage(messageId,null).orElseThrow(()->new ClientException("该历史消息尚无运行审计数据"));
        return Results.success(new Detail(run,repository.nodes(run.getRunId())));
    }
    @GetMapping("/{runId}")
    public Result<Detail> detail(@PathVariable String runId) {
        StpUtil.checkRole("admin");
        AgentAuditRun run=repository.find(runId).orElseThrow(()->new ClientException("运行记录不存在"));
        return Results.success(new Detail(run,repository.nodes(runId)));
    }
    @GetMapping("/{runId}/nodes")
    public Result<List<AgentAuditNode>> nodes(@PathVariable String runId) {
        StpUtil.checkRole("admin");
        if(repository.find(runId).isEmpty()) throw new ClientException("运行记录不存在");
        return Results.success(repository.nodes(runId));
    }
    public record Detail(AgentAuditRun run,List<AgentAuditNode> nodes) {}
}
