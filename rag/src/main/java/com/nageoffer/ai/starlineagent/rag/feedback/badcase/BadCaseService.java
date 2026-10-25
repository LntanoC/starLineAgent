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

package com.nageoffer.ai.starlineagent.rag.feedback.badcase;

import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadCaseService {
    public static final Set<String> ROOT_CAUSES=Set.of("RETRIEVAL_MISS","RETRIEVAL_NOISE","INTENT","HALLUCINATION","FORMAT","PERF","OTHER","TOOL_EXECUTION","BUSINESS_RULE","DELIVERY");
    public static final Set<String> FIX_ACTIONS=Set.of("MAPPING","INTENT_NODE","KNOWLEDGE_BASE","PROMPT","RERANK","NONE","TOOL","BUSINESS_RULE","DELIVERY","EVAL_DATASET");
    public static final Set<String> STATUSES=Set.of("PENDING","REVIEWED","IGNORED","FIXED");
    private final BadCaseRepository repository;
    private final BadCaseDetectionCoordinator coordinator;
    private final BadCaseProperties properties;
    private final AtomicBoolean scanning=new AtomicBoolean();

    public record ScanResult(int created,int failed,int scanned,boolean busy) {}
    public record ReviewRequest(String rootCause,String rootCauseDetail,String fixAction,String fixRef,String fixNote,
                                String reviewStatus,Long revision) {}

    public ScanResult scan(boolean includeOlder) {
        var r=coordinator.scan(null,false,includeOlder);
        return new ScanResult(((Number)r.getOrDefault("created",0)).intValue(),((Number)r.getOrDefault("failed",0)).intValue(),((Number)r.getOrDefault("scanned",0)).intValue(),Boolean.TRUE.equals(r.get("busy")));
    }
    public BadCaseRepository.Page page(String status,String cause,int page,int size) {
        if(status!=null&&!status.isBlank()&&!STATUSES.contains(status)) throw new ClientException("无效的判定状态");
        if(cause!=null&&!cause.isBlank()&&!ROOT_CAUSES.contains(cause)) throw new ClientException("无效的根因");
        return repository.page(status,cause,Math.max(1,page),Math.max(1,Math.min(size,100)));
    }
    public BadCaseRecord detail(String id) {
        return repository.find(id).orElseThrow(()->new ClientException("bad case 不存在或已归档"));
    }
    public void review(String id,ReviewRequest r,String reviewer) {
        if(r==null || r.revision()==null || r.reviewStatus()==null || !STATUSES.contains(r.reviewStatus()))
            throw new ClientException("请提供有效的状态和版本号");
        if(r.rootCause()!=null&&!r.rootCause().isBlank()&&!ROOT_CAUSES.contains(r.rootCause())) throw new ClientException("无效的根因");
        if(r.fixAction()==null||!FIX_ACTIONS.contains(r.fixAction())) throw new ClientException("无效的修复动作");
        if(Set.of("REVIEWED","FIXED").contains(r.reviewStatus())&&(r.rootCause()==null||!ROOT_CAUSES.contains(r.rootCause())))
            throw new ClientException("已判定或已修复记录必须选择根因");
        checkLength(r.rootCauseDetail(),1024);checkLength(r.fixNote(),1024);checkLength(r.fixRef(),128);
        detail(id);
        if(repository.review(id,r,reviewer)==0) throw new ClientException("该记录已被更新，请刷新后重新判定");
    }
    private void checkLength(String value,int max) {
        if(value!=null&&value.length()>max) throw new ClientException("判定内容超过长度限制 "+max);
    }
}
