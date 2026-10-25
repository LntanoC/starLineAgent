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
import com.nageoffer.ai.starlineagent.rag.feedback.badcase.BadCaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
@Component @RequiredArgsConstructor
public class ConversationBehaviorDetector implements BadCaseDetector {
 private final AgentBadCaseScanReader reader;private final UserCorrectionDetector correction;private final JdbcTemplate jdbc;private final BadCaseProperties properties;
 public String detectorCode(){return "behavior";}
 public static double similarity(String a,String b){Set<String>x=bigrams(a),y=bigrams(b);if(x.isEmpty()||y.isEmpty())return 0;Set<String>i=new HashSet<>(x);i.retainAll(y);Set<String>u=new HashSet<>(x);u.addAll(y);return (double)i.size()/u.size();}
 private static Set<String>bigrams(String s){String v=s==null?"":s.replaceAll("[\\p{P}\\p{Z}\\s]","").toLowerCase(Locale.ROOT);Set<String>set=new HashSet<>();for(int i=0;i+1<v.length();i++)set.add(v.substring(i,i+2));return set;}
 public static boolean stalledClarification(List<String> questions){return questions.size()>=3&&questions.stream().allMatch(q->similarity(q,questions.get(0))>=.82);}
 public DetectorScanResult scan(BadCaseScanContext c){List<BadCaseSignal>out=new ArrayList<>();var rows=reader.read(detectorCode(),UserCorrectionDetector.USERS,c);
  for(var u:rows){String text=(String)u.get("content");if(UserCorrectionDetector.explicitCorrection(text)||text==null||text.matches("(继续|然后呢|下一步|再详细[一点些]*|展开)[。？！?！]*"))continue;
   var prev=correction.previous(u);if(prev.isEmpty()||!"NORMAL".equals(prev.get().get("message_status")))continue;
   var p=prev.get();if(jdbc.queryForObject("SELECT count(*) FROM t_agent_run WHERE assistant_message_id=? AND run_status='AWAITING_CONFIRM'",Long.class,p.get("id"))>0)continue;
   var old=jdbc.queryForList("SELECT content,create_time FROM t_agent_message WHERE id=? AND user_id=? AND role='user' AND deleted=0",p.get("reply_to_message_id"),u.get("user_id"));
   if(!old.isEmpty()){double score=similarity(text,(String)old.get(0).get("content"));long seconds=Duration.between(((Timestamp)old.get(0).get("create_time")).toInstant(),((Timestamp)u.get("scan_time")).toInstant()).getSeconds();if(seconds>=0&&seconds<=properties.getBehavior().getRepeatWindowMinutes()*60L&&score>=properties.getBehavior().getRepeatSimilarityThreshold())out.add(signal(u,p,"REPEAT_QUESTION","repeat:"+u.get("id"),Map.of("firstQuestion",old.get(0).get("content"),"repeatQuestion",text,"similarity",score,"intervalSeconds",seconds)));}
   int n=Math.max(3,properties.getBehavior().getClarificationMinRounds());var turns=jdbc.queryForList("SELECT * FROM t_agent_message WHERE conversation_id=? AND user_id=? AND deleted=0 AND (create_time<? OR (create_time=? AND id<?)) ORDER BY create_time DESC,id DESC LIMIT ?",u.get("conversation_id"),u.get("user_id"),u.get("scan_time"),u.get("scan_time"),u.get("scan_id"),2*n+2);
   var assistants=turns.stream().filter(x->"assistant".equals(x.get("role"))).limit(n).toList();
   boolean requested=turns.stream().anyMatch(x->String.valueOf(x.get("content")).contains("一次只问一个"));
   if(!requested&&assistants.size()==n&&stalledClarification(assistants.stream().map(x->String.valueOf(x.get("content"))).toList())&&assistants.stream().allMatch(x->String.valueOf(x.get("content")).matches("(?s).*(请问|你的意思是|请提供|请补充).*[？?].*")&&"NORMAL".equals(x.get("message_status")))&&jdbc.queryForObject("SELECT count(*) FROM t_agent_run r JOIN t_agent_run_node t ON r.run_id=t.run_id WHERE r.conversation_id=? AND r.user_id=? AND r.create_time>=? AND (r.run_status='AWAITING_CONFIRM' OR (t.node_type='TOOL' AND t.status='SUCCESS'))",Long.class,u.get("conversation_id"),u.get("user_id"),assistants.get(n-1).get("create_time"))==0){String start=(String)assistants.get(n-1).get("id");out.add(new BadCaseSignal("CONVERSATION_BEHAVIOR","CONSECUTIVE_CLARIFICATION","LOW","S4","CONVERSATION_EPISODE",start,null,(String)u.get("conversation_id"),(String)u.get("user_id"),null,"clarify:"+u.get("id"),"连续澄清且无可见工具进展",((Timestamp)u.get("scan_time")).toInstant(),"behavior-v1",Map.of("episodeStartMessageId",start,"rounds",n,"slotTrackingAvailable",false)));}
  }
  String nofollow="""
   SELECT m.id AS scan_id,m.create_time AS scan_time,m.* FROM t_agent_message m
   WHERE m.role='assistant' AND m.message_status='NORMAL' AND m.deleted=0
    AND m.create_time<CURRENT_TIMESTAMP-(%d*INTERVAL '1 hour')
    AND NOT EXISTS(SELECT 1 FROM t_agent_message u WHERE u.conversation_id=m.conversation_id AND u.user_id=m.user_id AND u.role='user' AND u.deleted=0 AND (u.create_time>m.create_time OR (u.create_time=m.create_time AND u.id>m.id)))
    AND NOT EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=m.id AND f.deleted=0)
    AND EXISTS(SELECT 1 FROM t_agent_run r WHERE r.assistant_message_id=m.id AND r.user_id=m.user_id AND r.run_status='NORMAL' AND r.deleted=0)
   """.formatted(Math.max(1,properties.getBehavior().getNoFollowupAfterHours()));
  var quiet=reader.read("behavior:nofollow",nofollow,new BadCaseScanContext(c.until().minus(Duration.ofDays(7)),c.until(),c.limit(),c.dryRun()));
  for(var m:quiet){if(Integer.parseUnsignedInt(com.nageoffer.ai.starlineagent.rag.feedback.badcase.BadCaseDedupPolicy.hash((String)m.get("id")).substring(0,4),16)%100>=2)continue;out.add(signal(m,m,"NO_FOLLOWUP_AFTER_ANSWER","nofollow:"+m.get("id"),Map.of("observationHours",properties.getBehavior().getNoFollowupAfterHours(),"interpretation","仅表示没有后续消息或反馈，不能推断不满意")));}
  return new DetectorScanResult(out,rows.size()+quiet.size());
 }
 private BadCaseSignal signal(Map<String,Object>u,Map<String,Object>p,String rule,String occurrence,Map<String,Object>e){String id=(String)p.get("id");return new BadCaseSignal("CONVERSATION_BEHAVIOR",rule,"LOW","S4","AGENT_MESSAGE",id,id,(String)u.get("conversation_id"),(String)u.get("user_id"),null,occurrence,rule,((Timestamp)u.get("scan_time")).toInstant(),"behavior-v1",e);}
 public void checkpoint(BadCaseScanContext c){reader.checkpoint(detectorCode(),c);reader.checkpoint("behavior:nofollow",c);}
}
