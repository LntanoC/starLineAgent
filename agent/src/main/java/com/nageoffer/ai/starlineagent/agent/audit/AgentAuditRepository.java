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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class AgentAuditRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AgentAuditProperties properties;
    private static final BeanPropertyRowMapper<AgentAuditRun> RUN = BeanPropertyRowMapper.newInstance(AgentAuditRun.class);
    private static final BeanPropertyRowMapper<AgentAuditNode> NODE = BeanPropertyRowMapper.newInstance(AgentAuditNode.class);
    private static final String SAVE_RUN = "INSERT INTO t_agent_run (id,run_id,parent_run_id,resume_message_id,conversation_id,user_id,user_message_id,assistant_message_id,question,answer,run_status,capture_status,round_count,model_call_count,tool_call_count,input_tokens,output_tokens,cached_tokens,duration_ms,error_message,create_time,update_time,revision) VALUES (:id,:runId,:parentRunId,:resumeMessageId,:conversationId,:userId,:userMessageId,:assistantMessageId,:question,:answer,:runStatus,:captureStatus,:roundCount,:modelCallCount,:toolCallCount,:inputTokens,:outputTokens,:cachedTokens,:durationMs,:errorMessage,:createTime,:updateTime,:revision) ON CONFLICT (run_id) DO UPDATE SET parent_run_id=COALESCE(EXCLUDED.parent_run_id,t_agent_run.parent_run_id),resume_message_id=EXCLUDED.resume_message_id,conversation_id=EXCLUDED.conversation_id,user_id=EXCLUDED.user_id,user_message_id=EXCLUDED.user_message_id,assistant_message_id=EXCLUDED.assistant_message_id,question=COALESCE(NULLIF(EXCLUDED.question,''),t_agent_run.question),answer=EXCLUDED.answer,run_status=EXCLUDED.run_status,capture_status=EXCLUDED.capture_status,round_count=EXCLUDED.round_count,model_call_count=EXCLUDED.model_call_count,tool_call_count=EXCLUDED.tool_call_count,input_tokens=EXCLUDED.input_tokens,output_tokens=EXCLUDED.output_tokens,cached_tokens=EXCLUDED.cached_tokens,duration_ms=EXCLUDED.duration_ms,error_message=EXCLUDED.error_message,update_time=EXCLUDED.update_time,revision=EXCLUDED.revision WHERE EXCLUDED.revision >= t_agent_run.revision";
    private static final String SAVE_NODE = "INSERT INTO t_agent_run_node (id,run_id,node_id,parent_node_id,round_index,node_type,node_name,status,sequence,batch_id,tool_call_id,duration_source,input_payload,output_payload,input_tokens,output_tokens,cached_tokens,ttft_ms,started_at,ended_at,duration_ms,error_message,revision) VALUES (:id,:runId,:nodeId,:parentNodeId,:roundIndex,:nodeType,:nodeName,:status,:sequence,:batchId,:toolCallId,:durationSource,CAST(:inputPayload AS jsonb),CAST(:outputPayload AS jsonb),:inputTokens,:outputTokens,:cachedTokens,:ttftMs,:startedAt,:endedAt,:durationMs,:errorMessage,:revision) ON CONFLICT (run_id,node_id) DO UPDATE SET parent_node_id=EXCLUDED.parent_node_id,round_index=EXCLUDED.round_index,node_type=EXCLUDED.node_type,node_name=EXCLUDED.node_name,status=EXCLUDED.status,sequence=EXCLUDED.sequence,batch_id=EXCLUDED.batch_id,tool_call_id=EXCLUDED.tool_call_id,duration_source=EXCLUDED.duration_source,input_payload=EXCLUDED.input_payload,output_payload=EXCLUDED.output_payload,input_tokens=EXCLUDED.input_tokens,output_tokens=EXCLUDED.output_tokens,cached_tokens=EXCLUDED.cached_tokens,ttft_ms=EXCLUDED.ttft_ms,started_at=EXCLUDED.started_at,ended_at=EXCLUDED.ended_at,duration_ms=EXCLUDED.duration_ms,error_message=EXCLUDED.error_message,revision=EXCLUDED.revision WHERE EXCLUDED.revision >= t_agent_run_node.revision";

    public void saveRun(AgentAuditRun run) {
        jdbc.update(SAVE_RUN, new BeanPropertySqlParameterSource(run));
        if(run.getAssistantMessageId()!=null) {
            jdbc.update("UPDATE t_agent_run SET parent_run_id=:runId, question=CASE WHEN question IS NULL OR question='' THEN :question ELSE question END WHERE resume_message_id=:assistantMessageId AND user_id=:userId AND parent_run_id IS NULL AND run_id<>:runId",
                    new BeanPropertySqlParameterSource(run));
        }
    }
    @Transactional
    public void reconcileStale() {
        jdbc.update("UPDATE t_agent_run SET run_status='INTERRUPTED',capture_status='PARTIAL',error_message='进程退出后发现超过 24 小时未收口的运行',update_time=CURRENT_TIMESTAMP,revision=revision+1 WHERE deleted=0 AND run_status='RUNNING' AND update_time<CURRENT_TIMESTAMP-INTERVAL '24 hours'",Map.of());
        jdbc.update("UPDATE t_agent_run_node SET status='INTERRUPTED',revision=revision+1 WHERE status='RUNNING' AND run_id IN (SELECT run_id FROM t_agent_run WHERE run_status='INTERRUPTED' AND capture_status='PARTIAL')",Map.of());
    }
    public void saveNode(AgentAuditNode node) { jdbc.update(SAVE_NODE, new BeanPropertySqlParameterSource(node)); }
    @Transactional
    public void saveBundle(AgentAuditRun run, List<AgentAuditNode> nodes) {
        if (!nodes.isEmpty()) jdbc.batchUpdate(SAVE_NODE, nodes.stream().map(BeanPropertySqlParameterSource::new).toArray(SqlParameterSource[]::new));
        saveRun(run);
    }
    public Optional<AgentAuditRun> find(String runId) {
        return jdbc.query("SELECT * FROM t_agent_run WHERE run_id=:id AND deleted=0", Map.of("id",runId), RUN).stream().findFirst();
    }
    public Optional<AgentAuditRun> findByMessage(String id, String userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id",id);
        String owner = "";
        if (userId != null) { owner = " AND user_id=:user"; params.addValue("user",userId); }
        return jdbc.query("SELECT * FROM t_agent_run WHERE assistant_message_id=:id AND deleted=0" + owner + " ORDER BY create_time DESC LIMIT 1", params, RUN).stream().findFirst();
    }
    public List<AgentAuditNode> nodes(String runId) {
        return jdbc.query("SELECT * FROM t_agent_run_node WHERE run_id=:id AND deleted=0 ORDER BY started_at,sequence,node_id", Map.of("id",runId), NODE);
    }
    public IPage<AgentAuditRun> page(AgentAuditPageRequest req) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE deleted=0");
        eq(where,params,"run_id","runId",req.getRunId());
        eq(where,params,"conversation_id","conversationId",req.getConversationId());
        eq(where,params,"assistant_message_id","messageId",req.getMessageId());
        eq(where,params,"run_status","runStatus",req.getRunStatus());
        if(req.getBeginTime()!=null){where.append(" AND create_time>=:begin");params.addValue("begin",req.getBeginTime());}
        if(req.getEndTime()!=null){where.append(" AND create_time<=:end");params.addValue("end",req.getEndTime());}
        if(Boolean.TRUE.equals(req.getSlowOnly())){where.append(" AND duration_ms>=:slow");params.addValue("slow",properties.getSlowThresholdMs());}
        if(req.getMinInputTokens()!=null){where.append(" AND input_tokens>=:tokens");params.addValue("tokens",Math.max(0,req.getMinInputTokens()));}
        long current=Math.max(1,req.getCurrent()), size=Math.max(1,Math.min(100,req.getSize()));
        Long total=jdbc.queryForObject("SELECT count(*) FROM t_agent_run"+where,params,Long.class);
        params.addValue("limit",size).addValue("offset",(current-1)*size);
        String columns="id,run_id,parent_run_id,resume_message_id,conversation_id,user_id,user_message_id,assistant_message_id,left(question,300) AS question,NULL::text AS answer,run_status,capture_status,round_count,model_call_count,tool_call_count,input_tokens,output_tokens,cached_tokens,duration_ms,error_message,create_time,update_time,revision";
        List<AgentAuditRun> rows=jdbc.query("SELECT "+columns+" FROM t_agent_run"+where+" ORDER BY create_time DESC,run_id DESC LIMIT :limit OFFSET :offset",params,RUN);
        return new Page<AgentAuditRun>(current,size,total==null?0:total).setRecords(rows);
    }
    private void eq(StringBuilder sql,MapSqlParameterSource params,String column,String key,String value){
        if(value!=null&&!value.isBlank()){sql.append(" AND ").append(column).append("=:").append(key);params.addValue(key,value.trim());}
    }
}
