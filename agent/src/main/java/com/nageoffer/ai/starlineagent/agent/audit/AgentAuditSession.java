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

import cn.hutool.core.util.IdUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatUsage;
import com.nageoffer.ai.starlineagent.agent.tool.AgentToolExecutionFacts;
import java.util.*;

/** Per-run state only; callbacks and HTTP cancellation share this monitor. */
public final class AgentAuditSession {
    public static final String CONTEXT_KEY="starline_agent_audit";
    private final AgentAuditProperties properties;
    private final AgentAuditSerializer serializer;
    private final AgentAuditRepository repository;
    private final AgentAuditWriter writer;
    private final AgentToolExecutionFacts facts;
    private final AgentAuditRun run=new AgentAuditRun();
    private final List<AgentAuditNode> nodes=new ArrayList<>();
    private final Map<String,ToolState> tools=new LinkedHashMap<>();
    private final List<ModelState> models=new ArrayList<>();
    private final AgentAuditNode root;
    private int round,modelCalls,toolCalls,batches;
    private long revision,contentChars;
    private boolean partial,closed,failed,interrupted,awaiting;
    private String lastModelId="agent";

    public AgentAuditSession(String taskId,String userId,String conversationId,String userMessageId,
            String resumeMessageId,String parentId,String question,AgentToolExecutionFacts facts,
            AgentAuditProperties properties,AgentAuditSerializer serializer,AgentAuditRepository repository,AgentAuditWriter writer){
        this.properties=properties;this.serializer=serializer;this.repository=repository;this.writer=writer;this.facts=facts;
        Date now=new Date();
        run.setId(IdUtil.getSnowflakeNextIdStr());run.setRunId(taskId);run.setUserId(userId);run.setConversationId(conversationId);
        run.setUserMessageId(userMessageId);run.setResumeMessageId(resumeMessageId);run.setParentRunId(parentId);
        run.setQuestion(serializer.safeText(question,properties.getMaxFieldChars()));run.setRunStatus("RUNNING");
        run.setCreateTime(now);run.setUpdateTime(now);run.setRoundCount(0);run.setModelCallCount(0);run.setToolCallCount(0);
        root=node("agent",null,"AGENT","Agent 运行",0);root.setInputPayload(payload(Collections.singletonMap("question",question)));
    }
    public static AgentAuditSession from(RuntimeContext ctx){Object s=ctx==null?null:ctx.get(CONTEXT_KEY);return s instanceof AgentAuditSession a?a:null;}
    public synchronized void input(Msg input){
        if(input!=null)root.setInputPayload(payload(Map.of("request",serializer.describeMessage(input))));
    }
    public synchronized void begin(){queueRun();queueNode(root);}
    public synchronized void partial(){partial=true;}
    public synchronized void nextRound(){if(!closed)round++;}
    public synchronized void fail(Throwable error){if(!closed){failed=true;run.setErrorMessage(serializer.safeText(error==null?"执行失败":String.valueOf(error.getMessage()),2000));}}
    public synchronized void interrupt(){if(!closed)interrupted=true;}

    private AgentAuditNode node(String id,String parent,String type,String name,int sequence){
        AgentAuditNode n=new AgentAuditNode();n.setId(IdUtil.getSnowflakeNextIdStr());n.setRunId(run.getRunId());
        n.setNodeId(id);n.setParentNodeId(parent);n.setNodeType(type);n.setNodeName(AgentAuditSerializer.clip(name,120));
        n.setStatus("RUNNING");n.setSequence(sequence);n.setRoundIndex(round);n.setStartedAt(new Date());n.setRevision(++revision);
        if(nodes.size()<Math.max(8,properties.getMaxNodes()))nodes.add(n);else partial=true;
        return n;
    }
    public synchronized ModelState model(String name,String input){
        if(closed)return null;
        if(round==0)round=1;
        AgentAuditNode n=node("model-"+(++modelCalls),"agent","LLM",name,modelCalls);
        lastModelId=n.getNodeId();n.setDurationSource("model");n.setInputPayload(budget(input));queueNode(n);
        ModelState state=new ModelState(n);models.add(state);return state;
    }
    public synchronized void modelEvent(ModelState s,AgentEvent event){
        if(closed||s==null)return;
        if(event instanceof TextBlockDeltaEvent e){
            if(s.node.getTtftMs()==null)s.node.setTtftMs(Math.max(0,System.currentTimeMillis()-s.node.getStartedAt().getTime()));
            append(s.text,e.getDelta());
        }else if(event instanceof ThinkingBlockDeltaEvent e){append(s.thinking,e.getDelta());}
        else if(event instanceof ToolCallDeltaEvent e){
            String key=callKey(e.getReplyId(),e.getToolCallId());
            append(s.pending.computeIfAbsent(key,k->new StringBuilder()),e.getDelta());
        }else if(event instanceof ToolCallEndEvent e){
            StringBuilder args=s.pending.remove(callKey(e.getReplyId(),e.getToolCallId()));
            Map<String,Object> call=new LinkedHashMap<>();call.put("id",e.getToolCallId());call.put("name",e.getToolCallName());
            call.put("callIndex",facts.callIndexOf(e.getToolCallId()));
            call.put("input",serializer.parseOrRaw(args==null?null:args.toString()));s.calls.add(call);
        }else if(event instanceof ModelCallEndEvent e && !s.usageApplied){
            ChatUsage u=e.getUsage();
            if(u!=null){
                s.usageApplied=true;s.node.setInputTokens((long)u.getInputTokens());s.node.setOutputTokens((long)u.getOutputTokens());s.node.setCachedTokens((long)u.getCachedTokens());
                run.setInputTokens(sum(run.getInputTokens(),u.getInputTokens()));run.setOutputTokens(sum(run.getOutputTokens(),u.getOutputTokens()));run.setCachedTokens(sum(run.getCachedTokens(),u.getCachedTokens()));
            }
        }
    }
    private String callKey(String replyId,String id){return round+":"+replyId+":"+id+":"+facts.callIndexOf(id);}
    private Long sum(Long old,long n){return(old==null?0:old)+n;}
    private void append(StringBuilder b,String text){
        if(text==null)return;
        int remaining=Math.max(0,properties.getMaxFieldChars()-b.length());
        if(text.length()>remaining)partial=true;
        if(remaining>0)b.append(text,0,Math.min(text.length(),remaining));
    }
    public synchronized void endModel(ModelState s,String status,Throwable error){
        if(closed||s==null||s.finished)return;s.finished=true;
        Map<String,Object> out=new LinkedHashMap<>();out.put("answer",s.text.toString());out.put("thinking",s.thinking.toString());out.put("toolCalls",s.calls);
        if(!s.pending.isEmpty()){out.put("unfinishedToolArguments",s.pending);partial=true;}
        s.node.setOutputPayload(payload(out));end(s.node,status,error);queueNode(s.node);
    }
    public synchronized List<String> beginTools(List<ToolUseBlock> calls){
        if(closed)return List.of();
        String batch=run.getRunId()+"-audit-"+(++batches);List<String> keys=new ArrayList<>();
        for(ToolUseBlock call:calls){
            int index=facts.callIndexOf(call.getId());String key=batch+":"+index;
            AgentAuditNode n=node("tool-"+(++toolCalls),lastModelId,"TOOL",call.getName(),index);
            n.setToolCallId(call.getId());n.setBatchId(batch);n.setStartedAt(null);n.setInputPayload(payload(call.getInput()));
            tools.put(key,new ToolState(n));keys.add(key);queueNode(n);
        }
        return keys;
    }
    public synchronized void toolEvent(List<String> keys,AgentEvent event){
        if(closed)return;
        if(event instanceof RequireUserConfirmEvent e){
            awaiting=true;
            for(String key:keys){ToolState t=tools.get(key);t.node.setStatus("AWAITING");}
            AgentAuditNode confirm=node("confirm-"+batches,lastModelId,"CONFIRM","等待用户确认",batches);
            confirm.setInputPayload(payload(e.getToolCalls().stream().map(c->Map.of("id",String.valueOf(c.getId()),"name",String.valueOf(c.getName()),"input",c.getInput()==null?Map.of():c.getInput())).toList()));
            end(confirm,"AWAITING",null);queueNode(confirm);
        }else if(event instanceof AllToolsDeniedEvent){
            for(String key:keys)tools.get(key).node.setStatus("DENIED");
        }else if(event instanceof ToolResultStartEvent e){
            ToolState t=find(keys,e.getToolCallId());if(t!=null)t.node.setStartedAt(new Date());
        }else if(event instanceof ToolResultTextDeltaEvent e){
            ToolState t=find(keys,e.getToolCallId());if(t!=null)append(t.output,e.getDelta());
        }else if(event instanceof ToolResultEndEvent e){
            ToolState t=find(keys,e.getToolCallId());if(t!=null){t.node.setStatus(toolStatus(e.getState()));settleTool(t);}
        }
    }
    private ToolState find(List<String> keys,String id){
        for(String key:keys){ToolState s=tools.get(key);if(Objects.equals(s.node.getToolCallId(),id))return s;}
        partial=true;return null;
    }
    public synchronized void endTools(List<String> keys,Throwable error,boolean cancelled){
        if(closed)return;
        for(String key:keys){
            ToolState t=tools.get(key);if(t==null||t.settled)continue;
            if("RUNNING".equals(t.node.getStatus()))t.node.setStatus(cancelled?"INTERRUPTED":error!=null?"FAILED":"SUCCESS");
            if(error!=null)t.node.setErrorMessage(serializer.safeText(String.valueOf(error.getMessage()),2000));
            settleTool(t);
        }
    }
    private void settleTool(ToolState t){
        AgentAuditNode n=t.node;
        var fact=facts.toolFact(n.getToolCallId());
        if(fact.batchId()!=null)n.setBatchId(fact.batchId());
        if(fact.startedAt()!=null){n.setStartedAt(new Date(fact.startedAt()));n.setDurationSource("tool");}
        if(fact.endedAt()!=null)n.setEndedAt(new Date(fact.endedAt()));
        if(fact.durationMs()!=null)n.setDurationMs(fact.durationMs());
        if(fact.result()!=null){n.setOutputPayload(budget(serializer.toolResult(fact.result())));n.setStatus(toolStatus(fact.result().getState()));}
        else if(n.getOutputPayload()==null&&!t.output.isEmpty())n.setOutputPayload(payload(Map.of("result",t.output.toString())));
        t.settled=true;n.setRevision(++revision);queueNode(n);
    }
    private String toolStatus(ToolResultState state){
        if(state==null)return "FAILED";
        return switch(state.name()){case "ERROR", "FAILED"->"FAILED";case "DENIED"->"DENIED";case "INTERRUPTED", "RUNNING"->"INTERRUPTED";default->"SUCCESS";};
    }
    private void end(AgentAuditNode n,String status,Throwable error){
        n.setStatus(status);n.setEndedAt(new Date());if(n.getStartedAt()!=null)n.setDurationMs(Math.max(0,n.getEndedAt().getTime()-n.getStartedAt().getTime()));
        if(error!=null)n.setErrorMessage(serializer.safeText(String.valueOf(error.getMessage()),2000));n.setRevision(++revision);
    }
    public synchronized void finish(String messageId,String answer,String status){
        if(closed)return;
        if(messageId!=null)run.setAssistantMessageId(messageId);
        run.setAnswer(serializer.safeText(answer,properties.getMaxFieldChars()));
        String outcome="AWAITING_CONFIRM".equals(status)||awaiting?"AWAITING_CONFIRM":failed?"FAILED":interrupted||"INTERRUPTED".equals(status)?"INTERRUPTED":"NORMAL";
        for(ModelState m:models) if(!m.finished) endModel(m,"NORMAL".equals(outcome)?"SUCCESS":"INTERRUPTED",null);
        for(ToolState t:tools.values()) if(!t.settled){ t.node.setStatus("AWAITING_CONFIRM".equals(outcome)?"AWAITING":"INTERRUPTED");settleTool(t); }
        run.setRunStatus(outcome);run.setRoundCount(round);run.setModelCallCount(modelCalls);run.setToolCallCount(toolCalls);
        run.setDurationMs(facts.settleRun());run.setUpdateTime(new Date());
        for(AgentAuditNode n:nodes)if("RUNNING".equals(n.getStatus()))end(n,"INTERRUPTED",null);
        root.setOutputPayload(payload(Collections.singletonMap("answer",answer)));end(root,"NORMAL".equals(outcome)?"SUCCESS":"AWAITING_CONFIRM".equals(outcome)?"AWAITING":outcome,null);
        closed=true;
        long cost=Math.min(properties.getMaxRunChars()*2L+8192,contentChars*2+8192);
        boolean accepted=writer.offer(cost,true,()->repository.saveBundle(snapshot(),snapshots()),this::partial);
        if(!accepted)writer.offer(2048,true,()->repository.saveRun(snapshot()),this::partial);
    }
    private synchronized AgentAuditRun snapshot(){
        run.setCaptureStatus(!properties.isCaptureContent()?"DISABLED":partial?"PARTIAL":"COMPLETE");
        run.setRevision(++revision);run.setUpdateTime(new Date());return run.copy();
    }
    private synchronized List<AgentAuditNode> snapshots(){return nodes.stream().map(AgentAuditNode::copy).toList();}
    private void queueRun(){AgentAuditRun copy=snapshot();writer.offer(2048,false,()->repository.saveRun(copy),this::partial);}
    private void queueNode(AgentAuditNode node){
        if(!nodes.contains(node)) return;
        AgentAuditNode copy=node.copy();long cost=1024L+(copy.getInputPayload()==null?0:copy.getInputPayload().length()*2L)+(copy.getOutputPayload()==null?0:copy.getOutputPayload().length()*2L);
        writer.offer(cost,false,()->repository.saveNode(copy),this::partial);
    }
    private String payload(Object value){return budget(serializer.payload(value));}
    private String budget(String json){
        if(json==null)return null;
        if(json.contains("\"truncated\":true"))partial=true;
        if(contentChars+json.length()>properties.getMaxRunChars()){partial=true;return "{\"data\":\"[本次运行采集预算已用完]\",\"truncated\":true}";}
        contentChars+=json.length();return json;
    }
    public static final class ModelState {
        private final AgentAuditNode node;
        private final StringBuilder text=new StringBuilder(),thinking=new StringBuilder();
        private final Map<String,StringBuilder> pending=new LinkedHashMap<>();
        private final List<Object> calls=new ArrayList<>();
        private boolean usageApplied,finished;
        private ModelState(AgentAuditNode node){this.node=node;}
    }
    private static final class ToolState {
        private final AgentAuditNode node;private final StringBuilder output=new StringBuilder();private boolean settled;
        private ToolState(AgentAuditNode node){this.node=node;}
    }
}
