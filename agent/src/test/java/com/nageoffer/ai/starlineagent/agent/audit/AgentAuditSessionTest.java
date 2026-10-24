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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.agent.tool.AgentToolExecutionFacts;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentAuditSessionTest {
    private AgentAuditProperties properties;
    private AgentAuditSerializer serializer;
    private AgentAuditRepository repository;
    private AgentAuditWriter writer;
    private AgentAuditSession session;
    private AgentAuditRun saved;
    private final Map<String,AgentAuditNode> nodes=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup(){
        properties=new AgentAuditProperties();
        serializer=new AgentAuditSerializer(json,properties);
        repository=mock(AgentAuditRepository.class);writer=mock(AgentAuditWriter.class);
        doAnswer(i->{i.getArgument(2,Runnable.class).run();return true;}).when(writer).offer(anyLong(),anyBoolean(),any(),any());
        doAnswer(i->{saved=i.getArgument(0,AgentAuditRun.class).copy();return null;}).when(repository).saveRun(any());
        doAnswer(i->{AgentAuditNode n=i.getArgument(0);nodes.put(n.getNodeId(),n.copy());return null;}).when(repository).saveNode(any());
        doAnswer(i->{
            saved=i.getArgument(0,AgentAuditRun.class).copy();
            List<AgentAuditNode> all=i.getArgument(1);all.forEach(n->nodes.put(n.getNodeId(),n.copy()));return null;
        }).when(repository).saveBundle(any(),anyList());
        session=new AgentAuditSession("task-1","user-1","conversation-1","question-1",null,null,"查询活动",
                new AgentToolExecutionFacts("task-1",Clock.systemUTC()),properties,serializer,repository,writer);
        session.begin();
    }
    private AgentAuditSession.ModelState model(){
        session.nextRound();
        String input=serializer.modelInput(new ModelCallInput(List.of(new UserMessage("完整上下文中的用户问题")),List.of(),null,null));
        return session.model("deepseek",input);
    }
    @Test void storesActualInputUsageAndMessageAssociation(){
        var m=model();
        session.modelEvent(m,new TextBlockDeltaEvent("reply","text","答案"));
        session.modelEvent(m,new ModelCallEndEvent("reply",new ChatUsage(120,20,30,1.0)));
        session.endModel(m,"SUCCESS",null);
        session.finish("assistant-1","答案","NORMAL");
        assertThat(saved.getRunId()).isEqualTo("task-1");
        assertThat(saved.getAssistantMessageId()).isEqualTo("assistant-1");
        assertThat(saved.getRunStatus()).isEqualTo("NORMAL");
        assertThat(saved.getInputTokens()).isEqualTo(120L);
        assertThat(saved.getCachedTokens()).isEqualTo(30L);
        assertThat(nodes.get("model-1").getInputPayload()).contains("完整上下文中的用户问题");
        assertThat(nodes.get("model-1").getTtftMs()).isNotNull();
    }
    @Test void streamedArgumentsAppendWithoutCrossingParallelCalls() throws Exception{
        var m=model();
        session.modelEvent(m,new ToolCallDeltaEvent("r","a","search","{\"city\":"));
        session.modelEvent(m,new ToolCallDeltaEvent("r","b","search","{\"city\":\"北京\"}"));
        session.modelEvent(m,new ToolCallDeltaEvent("r","a","search","\"上海\"}"));
        session.modelEvent(m,new ToolCallEndEvent("r","a","search"));
        session.modelEvent(m,new ToolCallEndEvent("r","b","search"));
        session.endModel(m,"SUCCESS",null);
        session.beginTools(List.of(new ToolUseBlock("a","search",Map.of("city","上海")),new ToolUseBlock("b","search",Map.of("city","北京"))));
        session.finish("assistant-1","","NORMAL");
        var calls=json.readTree(nodes.get("model-1").getOutputPayload()).path("data").path("toolCalls");
        assertThat(calls.get(0).path("input").path("city").asText()).isEqualTo("上海");
        assertThat(calls.get(1).path("input").path("city").asText()).isEqualTo("北京");
        assertThat(nodes.get("tool-1").getInputPayload()).contains("上海").doesNotContain("北京");
        assertThat(nodes.get("tool-2").getInputPayload()).contains("北京").doesNotContain("上海");
    }
    @Test void cancellationFlushesInFlightOutputAndKeepsUnknownUsageUnknown(){
        var m=model();session.modelEvent(m,new TextBlockDeltaEvent("r","t","已经生成的内容"));
        session.interrupt();session.finish(null,"已经生成的内容","INTERRUPTED");
        assertThat(saved.getRunStatus()).isEqualTo("INTERRUPTED");
        assertThat(saved.getInputTokens()).isNull();
        assertThat(nodes.get("model-1").getOutputPayload()).contains("已经生成的内容");
        assertThat(nodes.get("model-1").getStatus()).isEqualTo("INTERRUPTED");
    }
    @Test void executionFailureIsNotDowngradedToMessageInterruption(){
        session.fail(new IllegalStateException("模型失败"));session.finish("m","","INTERRUPTED");
        assertThat(saved.getRunStatus()).isEqualTo("FAILED");
    }
    @Test void fieldTruncationMarksWholeRunPartial(){
        session.nextRound();
        var m=session.model("deepseek",serializer.payload(Map.of("prompt","字".repeat(200000))));
        session.endModel(m,"SUCCESS",null);session.finish("m","","NORMAL");
        assertThat(saved.getCaptureStatus()).isEqualTo("PARTIAL");
    }
    @Test void boundedSerializationRedactsSecretsAndPreservesConfirmMetadata() throws Exception{
        String output=serializer.payload(Map.of("api_key","sensitive","description","sk-1234567890abcdefghijklmnop","long","字".repeat(200000)));
        assertThat(output.length()).isLessThanOrEqualTo(properties.getMaxPayloadChars());
        assertThat(output).doesNotContain("sensitive","sk-1234567890abcdefghijklmnop").contains("REDACTED");
        assertThat(json.readTree(output).path("truncated").asBoolean()).isTrue();
        var msg=UserMessage.builder().metadata(Map.of("confirmation","DENIED")).build();
        assertThat(serializer.describeMessage(msg)).containsKey("metadata");
    }
}
