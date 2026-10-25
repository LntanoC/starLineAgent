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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.util.*;
@Component @RequiredArgsConstructor
public class UserCorrectionDetector implements BadCaseDetector {
 private final AgentBadCaseScanReader reader;private final JdbcTemplate jdbc;
 public String detectorCode(){return "user-correction";}
 static final String USERS="SELECT id AS scan_id,create_time AS scan_time,id,content,conversation_id,user_id FROM t_agent_message WHERE role='user' AND deleted=0";
 public static boolean explicitCorrection(String text){
  if(text==null||text.matches("(?s).*(不是说你错了|没错|不用纠正|换个话题|换个人|我想问|继续|再详细).*"))return false;
  return text.matches("(?s).*(不对[，,：:].*(应该|官方|时间|价格|金额|日期|地点|写的是)|你(刚才|上面|之前)?(说|答|写).*(错|不对)|更正一下刚才|不是.{1,30}[，,]是.{1,30}).*");
 }
 public Optional<Map<String,Object>> previous(Map<String,Object> user){return jdbc.queryForList("SELECT * FROM t_agent_message WHERE conversation_id=? AND user_id=? AND role='assistant' AND deleted=0 AND (create_time<? OR (create_time=? AND id<?)) ORDER BY create_time DESC,id DESC LIMIT 1",user.get("conversation_id"),user.get("user_id"),user.get("scan_time"),user.get("scan_time"),user.get("scan_id")).stream().findFirst();}
 public DetectorScanResult scan(BadCaseScanContext c){List<BadCaseSignal> out=new ArrayList<>();var rows=reader.read(detectorCode(),USERS,c);for(var u:rows){if(!explicitCorrection((String)u.get("content")))continue;previous(u).ifPresent(prev->{String id=(String)prev.get("id");out.add(new BadCaseSignal("USER_CORRECTION","EXPLICIT_USER_CORRECTION","HIGH","S3","AGENT_MESSAGE",id,id,(String)u.get("conversation_id"),(String)u.get("user_id"),null,"correction:"+u.get("id"),"用户明确纠正上一回答",((Timestamp)u.get("scan_time")).toInstant(),"correction-v1",Map.of("correctionMessageId",u.get("id"),"correctionText",u.get("content"),"targetAssistantMessageId",id)));});}return new DetectorScanResult(out,rows.size());}
 public void checkpoint(BadCaseScanContext c){reader.checkpoint(detectorCode(),c);}
}
