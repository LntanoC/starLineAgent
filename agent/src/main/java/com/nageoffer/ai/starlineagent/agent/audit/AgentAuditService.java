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

import com.nageoffer.ai.starlineagent.agent.tool.AgentToolExecutionFacts;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditService {
    private final AgentAuditProperties properties;
    private final AgentAuditRepository repository;
    private final AgentAuditWriter writer;
    private final AgentAuditSerializer serializer;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStale() {
        if(properties.isEnabled())writer.offer(1024,true,repository::reconcileStale,()->log.warn("Agent audit stale-run recovery deferred"));
    }

    public AgentAuditSession open(String taskId,String userId,String conversationId,String userMessageId,
                                  String resumeMessageId,Msg input,AgentToolExecutionFacts facts){
        if(!properties.isEnabled())return null;
        String parentId=null,question=input==null?null:input.getTextContent();
        boolean partial=false;
        if(resumeMessageId!=null){
            try{
                var parent=repository.findByMessage(resumeMessageId,userId);
                if(parent.isPresent()){parentId=parent.get().getRunId();question=parent.get().getQuestion();}
            }catch(Exception error){partial=true;log.warn("Agent audit parent lookup failed, type={}",error.getClass().getSimpleName());}
        }
        AgentAuditSession session=new AgentAuditSession(taskId,userId,conversationId,userMessageId,resumeMessageId,
                parentId,question,facts,properties,serializer,repository,writer);
        if(partial)session.partial();
        session.input(input);
        session.begin();
        return session;
    }
}
