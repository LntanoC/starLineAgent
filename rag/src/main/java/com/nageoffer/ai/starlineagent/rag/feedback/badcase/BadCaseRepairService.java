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
import com.nageoffer.ai.starlineagent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.starlineagent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.starlineagent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.starlineagent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅在管理员点击“应用映射”时落配置；配置和台账判定处于同一事务。 */
@Service
@RequiredArgsConstructor
public class BadCaseRepairService {
    private final BadCaseService cases;
    private final QueryTermMappingAdminService mappings;
    private final QueryTermMappingCacheManager cache;
    public record MappingRequest(String sourceTerm,String targetTerm,String mappingId,
                                 String rootCause,String rootCauseDetail,String fixNote,Long revision) {}
    @Transactional
    public String applyMapping(String id,MappingRequest request,String reviewer) {
        if(request==null || request.revision()==null || request.sourceTerm()==null || request.targetTerm()==null
                || request.sourceTerm().isBlank() || request.targetTerm().isBlank()
                || request.sourceTerm().length()>128 || request.targetTerm().length()>128)
            throw new ClientException("请输入 1 至 128 字的原始词和目标词，并提供台账版本");
        BadCaseRecord item=cases.detail(id);
        if(item.revision()!=request.revision()) throw new ClientException("台账已更新，请刷新后再应用");
        String mappingId=request.mappingId();
        if(mappingId==null || mappingId.isBlank()) {
            QueryTermMappingCreateRequest create=new QueryTermMappingCreateRequest();
            create.setSourceTerm(request.sourceTerm());create.setTargetTerm(request.targetTerm());
            create.setMatchType(1);create.setPriority(100);create.setEnabled(true);
            create.setRemark("由 bad case "+id+" 人工创建");
            mappingId=mappings.create(create);
        } else {
            if(mappingId.length()>20) throw new ClientException("映射 ID 过长");
            QueryTermMappingUpdateRequest update=new QueryTermMappingUpdateRequest();
            update.setSourceTerm(request.sourceTerm());update.setTargetTerm(request.targetTerm());update.setEnabled(true);
            mappings.update(mappingId,update);
        }
        cases.review(id,new BadCaseService.ReviewRequest(request.rootCause(),request.rootCauseDetail(),"MAPPING",
                mappingId,request.fixNote(),"REVIEWED",request.revision()),reviewer);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.clearCache(); }
        });
        return mappingId;
    }
}
