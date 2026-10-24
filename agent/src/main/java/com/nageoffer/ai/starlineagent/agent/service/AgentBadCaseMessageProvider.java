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

package com.nageoffer.ai.starlineagent.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.starlineagent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.starlineagent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.starlineagent.agent.dto.AgentBlock;
import com.nageoffer.ai.starlineagent.rag.feedback.badcase.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Agent 现场来自该消息的持久化 blocks，优先关联自建运行审计。 */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentBadCaseMessageProvider implements BadCaseMessageProvider {
    private final AgentMessageMapper messages;
    private final com.nageoffer.ai.starlineagent.agent.audit.AgentAuditRepository audits;
    @Override
    public Optional<BadCaseReport> find(String id,String userId) {
        AgentMessageDO m=messages.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getId,id).eq(AgentMessageDO::getUserId,userId)
                .eq(AgentMessageDO::getRole,"assistant").eq(AgentMessageDO::getDeleted,0));
        if(m==null) return Optional.empty();
        var query=Wrappers.lambdaQuery(AgentMessageDO.class).eq(AgentMessageDO::getUserId,userId)
                .eq(AgentMessageDO::getConversationId,m.getConversationId()).eq(AgentMessageDO::getRole,"user")
                .eq(AgentMessageDO::getDeleted,0);
        AgentMessageDO q=null;
        if(m.getReplyToMessageId()!=null) q=messages.selectOne(query.clone().eq(AgentMessageDO::getId,m.getReplyToMessageId()));
        List<String> notes=new ArrayList<>();
        if(q==null && m.getCreateTime()!=null) {
            q=messages.selectOne(query.and(w->w.lt(AgentMessageDO::getCreateTime,m.getCreateTime())
                    .or(x->x.eq(AgentMessageDO::getCreateTime,m.getCreateTime()).lt(AgentMessageDO::getId,id)))
                    .orderByDesc(AgentMessageDO::getCreateTime).orderByDesc(AgentMessageDO::getId).last("LIMIT 1"));
            notes.add("用户问题由同会话前一条用户消息推定，请人工确认。");
        }
        var audit=audits.findByMessage(id,userId);
        if(audit.isPresent()) {
            var run=audit.get();
            var captured=audits.nodes(run.getRunId());
            var summaries=captured.stream().map(n->new BadCaseReport.TraceNodeSummary(
                    n.getNodeId(),n.getParentNodeId(),"AGENT".equals(n.getNodeType())?0:"TOOL".equals(n.getNodeType())?2:1,
                    n.getNodeType(),n.getNodeName(),"FAILED".equals(n.getStatus())?"ERROR":n.getStatus(),
                    n.getDurationMs(),n.getErrorMessage(),n.getStartedAt(),clip(n.getOutputPayload(),16000),
                    clip(n.getInputPayload(),8000))).toList();
            notes.add("运行通过 assistant_message_id 精确关联；完整 prompt、token 与工具参数可在 Agent 运行审计中查看。采集状态："+run.getCaptureStatus());
            return Optional.of(BadCaseReport.builder().messageId(id).conversationId(m.getConversationId()).userId(userId)
                    .messageType("AGENT").runId(run.getRunId()).traceMatchStatus("AGENT_AUDIT")
                    .captureStatus(run.getCaptureStatus()).roundCount(run.getRoundCount()).toolCallCount(run.getToolCallCount())
                    .userQuestion(q==null?run.getQuestion():q.getContent()).assistantAnswer(m.getContent()).thinkingContent(m.getThinkingContent())
                    .messageStatus(m.getMessageStatus()).sources(List.of()).retrievedChunks(List.of()).traceNodes(summaries)
                    .messageCreateTime(m.getCreateTime()).evidenceNotes(notes).build());
        }
        notes.add("节点来自本条消息的持久化 blocks，与消息精确关联；工具结果可能已截断，文本块耗时不等同于完整模型调用耗时。");
        notes.add("本条历史消息没有关联到 Agent 运行审计，以下节点来自持久化消息块；工具结果可能已截断。");
        List<BadCaseReport.TraceNodeSummary> nodes=new ArrayList<>();
        String root="agent-message:"+id;
        nodes.add(new BadCaseReport.TraceNodeSummary(root,null,0,"AGENT","Agent 回答现场",
                "NORMAL".equals(m.getMessageStatus())?"SUCCESS":m.getMessageStatus(),m.getDurationMs(),null,null,null,null));
        if(m.getBlocks()!=null) {
            int index=0;
            for(AgentBlock block:m.getBlocks()) {
                if(block==null) continue;
                String kind=block.getKind();
                String type="tool".equals(kind)?"TOOL":("error".equals(kind)?"ERROR":("confirm".equals(kind)?"CONFIRM":"LLM"));
                String status="failed".equals(block.getStatus())||"error".equals(kind)?"ERROR":
                        ("interrupted".equals(block.getStatus())?"INTERRUPTED":"SUCCESS");
                String error="ERROR".equals(status)?clip(block.getResult()==null?block.getText():block.getResult(),4000):null;
                nodes.add(new BadCaseReport.TraceNodeSummary(root+":"+index++,root,1,type,
                        block.getDisplayName()!=null?block.getDisplayName():(block.getName()!=null?block.getName():kind),
                        status,block.getDurationMs(),error,
                        block.getStartedAt()==null?null:new Date(block.getStartedAt()),
                        "tool".equals(kind)?clip(block.getResult(),16000):null,null));
            }
        }
        return Optional.of(BadCaseReport.builder().messageId(id).conversationId(m.getConversationId()).userId(userId)
                .messageType("AGENT").runId(null).traceMatchStatus("AGENT_BLOCKS")
                .userQuestion(q==null?null:q.getContent()).assistantAnswer(m.getContent()).thinkingContent(m.getThinkingContent())
                .messageStatus(m.getMessageStatus()).sources(List.of()).retrievedChunks(List.of()).traceNodes(nodes)
                .messageCreateTime(m.getCreateTime()).evidenceNotes(notes).build());
    }
    private String clip(String value,int length) { return value==null?null:(value.length()<=length?value:value.substring(0,length)+"…[截断]"); }
}
