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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
@Service @RequiredArgsConstructor
public class BadCaseEvidenceEnricher {
 private final BadCaseIntakeService intake;private final BadCaseAnalyzer analyzer;private final JdbcTemplate jdbc;private final ObjectMapper json;
 public int enrich(int limit){int count=0;for(var b:jdbc.queryForList("SELECT * FROM t_bad_case WHERE deleted=0 AND snapshot_status IN ('PENDING','PARTIAL') AND snapshot_attempts<3 ORDER BY create_time LIMIT ?",limit)){
  String id=(String)b.get("id");try{
   if(b.get("message_id")==null){String status="AGENT_RUN".equals(b.get("subject_type"))?"PARTIAL":"COMPLETE";jdbc.update("UPDATE t_bad_case SET snapshot_status=?,snapshot_attempts=snapshot_attempts+1 WHERE id=?",status,id);continue;}
   var r=analyzer.analyze(new BadCaseRepository.Feedback((String)b.get("message_id"),(String)b.get("conversation_id"),(String)b.get("user_id"),((Number)b.get("vote")).intValue(),(String)b.get("reason"),(String)b.get("comment")),3000);
   boolean bounded=bounded(r.getTraceNodes())||bounded(r.getSources())||bounded(r.getRetrievedChunks())||Objects.toString(r.getAssistantAnswer(),"").length()>32768||Objects.toString(r.getUserQuestion(),"").length()>32768;
   String status="MISSING".equals(r.getMessageStatus())?"MISSING":("COMPLETE".equals(r.getCaptureStatus())&&!bounded?"COMPLETE":"PARTIAL");
   jdbc.update("""
    UPDATE t_bad_case SET message_type=?,run_id=COALESCE(?,run_id),trace_match_status=?,user_question=?,assistant_answer=?,thinking_content=?,message_status=?,
     sources=?::jsonb,retrieved_chunks=?::jsonb,trace_nodes=?::jsonb,suspicions=?::jsonb,evidence_notes=?::jsonb,message_create_time=?,
     capture_status=?,round_count=?,tool_call_count=?,snapshot_status=?,snapshot_attempts=snapshot_attempts+1,revision=revision+1 WHERE id=? AND deleted=0
    """,r.getMessageType(),r.getRunId(),r.getTraceMatchStatus(),BadCaseSanitizer.text(r.getUserQuestion(),32768),BadCaseSanitizer.text(r.getAssistantAnswer(),32768),BadCaseSanitizer.text(r.getThinkingContent(),32768),r.getMessageStatus(),encode(r.getSources()),encode(r.getRetrievedChunks()),encode(r.getTraceNodes()),encode(r.getSuspicions()),encode(r.getEvidenceNotes()),r.getMessageCreateTime(),r.getCaptureStatus(),r.getRoundCount(),r.getToolCallCount(),status,id);count++;if("MISSING".equals(status))intake.metric("snapshot","snapshot_missing_total");
   if(r.getSuspicions()!=null&&r.getSuspicions().stream().anyMatch(x->x.startsWith("A4")))intake.submit(new com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseSignal("AGENT_RUNTIME","EVIDENCE_ANSWER_MISMATCH","LOW","S3","AGENT_MESSAGE",r.getMessageId(),r.getMessageId(),r.getConversationId(),r.getUserId(),r.getRunId(),"mismatch:"+r.getMessageId(),"来源与回答疑似不一致",java.time.Instant.now(),"evidence-v1",Map.of("rule","A4","interpretation","词面线索不能证明幻觉","captureStatus",Objects.toString(r.getCaptureStatus(),"MISSING"))));
  }catch(Exception e){jdbc.update("UPDATE t_bad_case SET snapshot_attempts=snapshot_attempts+1,snapshot_status=CASE WHEN snapshot_attempts>=2 THEN 'MISSING' ELSE 'PENDING' END WHERE id=?",id);}
 }return count;}
 private boolean bounded(Object value){return deepBounded(json.convertValue(value,Object.class));}
 private boolean deepBounded(Object value){if(value instanceof Map<?,?>m)return m.values().stream().anyMatch(this::deepBounded);if(value instanceof Collection<?>c)return c.size()>64||c.stream().anyMatch(this::deepBounded);return value instanceof String s&&s.length()>4000;}
 private String encode(Object v)throws Exception{Object generic=json.readValue(json.writeValueAsString(v==null?List.of():v),Object.class);Object safe=BadCaseSanitizer.value(generic);
  if(safe instanceof List<?> list){List<Object>bounded=new ArrayList<>();for(Object item:list){bounded.add(item);if(json.writeValueAsString(bounded).length()>131000){bounded.remove(bounded.size()-1);break;}}safe=bounded;}
  return json.writeValueAsString(safe);}
}
