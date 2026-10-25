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

package com.nageoffer.ai.starlineagent.agent.badcase;
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.util.*;
@Component @RequiredArgsConstructor
public class AgentRuntimeBadCaseDetector implements BadCaseDetector {
 private final AgentBadCaseScanReader reader;
 public String detectorCode(){return "agent-runtime";}
 private static final String EVENTS="""
 SELECT n.id AS scan_id,n.ended_at AS scan_time,r.run_id,r.assistant_message_id,r.conversation_id,r.user_id,r.capture_status,
 n.node_id,n.node_name,n.error_message,n.input_payload,'TOOL' AS kind,
 EXISTS(SELECT 1 FROM t_agent_run_node ok WHERE ok.run_id=n.run_id AND ok.node_type='TOOL' AND ok.node_name=n.node_name AND ok.status='SUCCESS' AND ok.started_at>n.ended_at AND ok.deleted=0) AS recovered
 FROM t_agent_run_node n JOIN t_agent_run r ON r.run_id=n.run_id
 WHERE n.deleted=0 AND r.deleted=0 AND n.node_type='TOOL' AND n.status='FAILED' AND r.run_status IN ('NORMAL','FAILED','INTERRUPTED')
 UNION ALL
 SELECT r.id,r.update_time,r.run_id,r.assistant_message_id,r.conversation_id,r.user_id,r.capture_status,
 NULL,NULL,r.error_message,NULL,'RUN',FALSE FROM t_agent_run r WHERE r.deleted=0 AND r.run_status='FAILED'
 """;
 public DetectorScanResult scan(BadCaseScanContext c){
  List<Map<String,Object>> rows=new ArrayList<>(reader.read(detectorCode(),EVENTS,c));
  // Independent composite cursor sweeps the bounded lookback, including late audit writes.
  rows.addAll(reader.read(detectorCode()+":late",EVENTS+"",c));
  List<BadCaseSignal> signals=new ArrayList<>();for(var row:rows){
   String run=(String)row.get("run_id"),message=(String)row.get("assistant_message_id");boolean tool="TOOL".equals(row.get("kind")),recovered=Boolean.TRUE.equals(row.get("recovered"));
   String rule=tool?(recovered?"TOOL_TRANSIENT_FAILED":"TOOL_FINAL_FAILED"):"AGENT_RUN_FAILED";
   Map<String,Object> e=new LinkedHashMap<>();for(String k:List.of("node_name","node_id","error_message","input_payload","capture_status"))if(row.get(k)!=null)e.put(k,String.valueOf(row.get(k)));e.put("recovered",recovered);
   signals.add(new BadCaseSignal("AGENT_RUNTIME",rule,"HIGH",recovered?"S4":"S2",message==null?"AGENT_RUN":"AGENT_MESSAGE",message==null?run:message,message,(String)row.get("conversation_id"),(String)row.get("user_id"),run,tool?"tool:"+run+":"+row.get("node_id"):"run:"+run,tool?"工具调用失败："+row.get("node_name"):"Agent 运行失败",((Timestamp)row.get("scan_time")).toInstant(),"runtime-v1",e));
  }return new DetectorScanResult(signals,rows.size());
 }
 public void checkpoint(BadCaseScanContext c){reader.checkpoint(detectorCode(),c);reader.checkpoint(detectorCode()+":late",c);}
}
