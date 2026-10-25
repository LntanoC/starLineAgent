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
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.util.*;
@Component @RequiredArgsConstructor
public class UserFeedbackBadCaseDetector implements BadCaseDetector {
 private final JdbcTemplate jdbc;
 public String detectorCode(){return "user-feedback";}
 public DetectorScanResult scan(BadCaseScanContext c){var rows=jdbc.queryForList("""
 SELECT f.*,EXISTS(SELECT 1 FROM t_agent_message m WHERE m.id=f.message_id AND m.user_id=f.user_id) AS agent
 FROM t_message_feedback f WHERE f.vote=-1 AND f.deleted=0
 AND NOT EXISTS(SELECT 1 FROM t_bad_case_signal s WHERE s.rule_code='USER_DOWNVOTE' AND (
   s.occurrence_key='feedback:'||f.message_id||':'||f.user_id||':'||floor(extract(epoch FROM f.update_time)*1000)::bigint::text
   OR (s.occurrence_key='feedback:'||f.message_id||':'||f.user_id AND s.invalidated_at IS NULL)))
 ORDER BY f.update_time,f.id LIMIT ?
 """,c.limit());List<BadCaseSignal>out=new ArrayList<>();for(var f:rows){String id=(String)f.get("message_id");Map<String,Object>e=new LinkedHashMap<>();for(String k:List.of("reason","comment"))if(f.get(k)!=null)e.put(k,f.get(k));out.add(new BadCaseSignal("USER_FEEDBACK","USER_DOWNVOTE","HIGH","S3",Boolean.TRUE.equals(f.get("agent"))?"AGENT_MESSAGE":"RAG_MESSAGE",id,id,(String)f.get("conversation_id"),(String)f.get("user_id"),null,"feedback:"+id+":"+f.get("user_id")+":"+((Timestamp)f.get("update_time")).toInstant().toEpochMilli(),"用户点踩",((Timestamp)f.get("update_time")).toInstant(),"feedback-v1",e));}return new DetectorScanResult(out,rows.size());}
}
