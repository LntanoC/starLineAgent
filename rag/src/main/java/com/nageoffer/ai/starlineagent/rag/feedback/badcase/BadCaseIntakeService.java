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
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
@Slf4j @Service @RequiredArgsConstructor
public class BadCaseIntakeService implements BadCaseIntake {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final PlatformTransactionManager transactions;
 private final BadCaseDedupPolicy dedup;private final BadCaseProperties properties;
 private static final Map<String,Integer> PRIORITY=Map.of("USER_FEEDBACK",0,"USER_CORRECTION",1,"STARLINE_DELIVERY",2,"STARLINE_BUSINESS",3,"AGENT_RUNTIME",4,"EVALUATION",5,"CONVERSATION_BEHAVIOR",6,"UNVOTED_SAMPLE",7);
 public String detector(BadCaseSignal s){return switch(s.source()) {
 case "USER_FEEDBACK"->"user-feedback";case "USER_CORRECTION"->"user-correction";
 case "STARLINE_DELIVERY"->"starline-delivery";case "STARLINE_BUSINESS"->"starline-business";
 case "CONVERSATION_BEHAVIOR"->"behavior";case "UNVOTED_SAMPLE"->"sampling";
 case "EVALUATION"->"evaluation";default->"agent-runtime";};}
 public IntakeResult submit(BadCaseSignal s) {
  validate(s);String detector=detector(s);metric(detector,"detected_total");
  String mode=properties.detectorMode(detector);
  if(!properties.isEnabled()||"OFF".equals(mode)||(!"user-feedback".equals(detector)&&"OFF".equals(properties.getIntake().getMode())))return new IntakeResult(null,false,false,"OFF");
  if("SHADOW".equals(mode)||(!"user-feedback".equals(detector)&&"SHADOW".equals(properties.getIntake().getMode()))) {
   metric(detector,"shadow_total");return new IntakeResult(null,false,false,"SHADOW");
  }
  return new TransactionTemplate(transactions).execute(status->persist(s,detector));
 }
 public void submitSafely(BadCaseSignal s) {try{var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.executeWithoutResult(status->submit(s));}catch(Exception e){log.warn("Bad case intake failed source={} rule={} type={}",s.source(),s.ruleCode(),e.getClass().getSimpleName());}}
 @Override public void withdrawUserFeedback(String messageId,String userId) {
  if(messageId==null||messageId.isBlank()||userId==null||userId.isBlank())return;
  new TransactionTemplate(transactions).executeWithoutResult(status->{
   jdbc.execute("SELECT pg_advisory_xact_lock(20261025)");
   String legacy="feedback:"+messageId+":"+userId;
   String prefix=legacy+":";
   List<String> ids=jdbc.queryForList("""
    UPDATE t_bad_case_signal SET invalidated_at=CURRENT_TIMESTAMP,invalid_reason='用户撤销点踩',evidence='{}'::jsonb
    WHERE rule_code='USER_DOWNVOTE' AND invalidated_at IS NULL
      AND (occurrence_key=? OR left(occurrence_key,char_length(?))=?)
    RETURNING bad_case_id
    """,String.class,legacy,prefix,prefix);
   for(String id:new LinkedHashSet<>(ids)) {
    jdbc.update("""
     UPDATE t_bad_case b SET
      vote=CASE WHEN EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=b.message_id AND f.user_id=b.user_id AND f.vote=-1 AND f.deleted=0) THEN -1 ELSE 0 END,
      reason=CASE WHEN EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=b.message_id AND f.user_id=b.user_id AND f.vote=-1 AND f.deleted=0) THEN b.reason ELSE NULL END,
      comment=CASE WHEN EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=b.message_id AND f.user_id=b.user_id AND f.vote=-1 AND f.deleted=0) THEN b.comment ELSE NULL END
     WHERE b.id=?
     """,id);
    refresh(id);
   }
  });
 }
 private IntakeResult persist(BadCaseSignal s,String detector) {
  // Low-volume management stream: serialize intake across instances, including RUN -> MSG merges.
  jdbc.execute("SELECT pg_advisory_xact_lock(20261025)");
  if("USER_DOWNVOTE".equals(s.ruleCode())) {
   Long active=jdbc.queryForObject("SELECT count(*) FROM t_message_feedback WHERE message_id=? AND user_id=? AND vote=-1 AND deleted=0",Long.class,s.messageId(),s.userId());
   if(active==null||active==0){metric(detector,"withdrawn_event_dropped_total");return new IntakeResult(null,false,false,"WITHDRAWN");}
  }
  var existing=jdbc.queryForList("SELECT bad_case_id FROM t_bad_case_signal WHERE rule_code=? AND occurrence_key=?",s.ruleCode(),s.occurrenceKey());
  if(!existing.isEmpty()){metric(detector,"duplicate_suppressed_total");return new IntakeResult((String)existing.get(0).get("bad_case_id"),false,false,"DUPLICATE");}
  if("LOW".equals(s.confidence())&&quotaExceeded(s)){metric(detector,"quota_dropped_total");return new IntakeResult(null,false,false,"QUOTA");}
  String key=dedup.key(s);reconcile(s.runId(),s.messageId(),s.userId());
  var rows=jdbc.queryForList("SELECT * FROM t_bad_case WHERE dedup_key=? FOR UPDATE",key);
  if(!rows.isEmpty()&&!owner(s.userId()).equals(rows.get(0).get("user_id")))throw new IllegalArgumentException("Candidate owner mismatch");
  boolean created=rows.isEmpty();String id=created?IdWorker.getIdStr():(String)rows.get(0).get("id");
  if(created)jdbc.update("""
   INSERT INTO t_bad_case(id,message_id,conversation_id,user_id,message_type,run_id,vote,subject_type,subject_id,dedup_key,title,
    primary_source,confidence,severity,triage_status,first_detected_at,last_detected_at,snapshot_status)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')
   """,id,s.messageId(),s.conversationId(),owner(s.userId()),s.subjectType().startsWith("AGENT")?"AGENT":("RAG_MESSAGE".equals(s.subjectType())?"RAG":"UNKNOWN"),
    s.runId(),"USER_DOWNVOTE".equals(s.ruleCode())?-1:0,s.messageId()!=null?("RAG_MESSAGE".equals(s.subjectType())?"RAG_MESSAGE":"AGENT_MESSAGE"):s.subjectType(),
    s.messageId()!=null?s.messageId():s.subjectId(),key,BadCaseSanitizer.text(s.title(),255),s.source(),s.confidence(),s.severity(),
    "HIGH".equals(s.confidence())?"READY_FOR_HUMAN":("NO_FOLLOWUP_AFTER_ANSWER".equals(s.ruleCode())?"OBSERVING":"AUTO_REVIEW_PENDING"),Timestamp.from(s.occurredAt()),Timestamp.from(s.occurredAt()));
  if(!created){Map<String,Object> old=rows.get(0);Timestamp done=(Timestamp)old.get(old.get("archived_at")!=null?"archived_at":"review_time");
   if(BadCaseDedupPolicy.shouldReopen((String)old.get("review_status"),old.get("archived_at")!=null,done==null?null:done.toInstant(),s.occurredAt(),s.confidence()))reopen(id);
  }
  jdbc.update("""
   INSERT INTO t_bad_case_signal(id,bad_case_id,source,rule_code,confidence,severity,occurrence_key,evidence,detector_version,occurred_at)
   VALUES(?,?,?,?,?,?,?,?::jsonb,?,?) ON CONFLICT(rule_code,occurrence_key) DO NOTHING
   """,IdWorker.getIdStr(),id,s.source(),s.ruleCode(),s.confidence(),s.severity(),s.occurrenceKey(),evidence(s.evidence()),s.detectorVersion(),Timestamp.from(s.occurredAt()));
  if("USER_DOWNVOTE".equals(s.ruleCode()))jdbc.update("UPDATE t_bad_case SET vote=-1,reason=?,comment=? WHERE id=?",BadCaseSanitizer.text(String.valueOf(s.evidence().getOrDefault("reason","")),255),BadCaseSanitizer.text(String.valueOf(s.evidence().getOrDefault("comment","")),1024),id);
  if("TOOL_TRANSIENT_FAILED".equals(s.ruleCode()))jdbc.update("UPDATE t_bad_case_signal SET invalidated_at=CURRENT_TIMESTAMP,invalid_reason='同运行后续工具调用已恢复' WHERE occurrence_key=? AND rule_code='TOOL_FINAL_FAILED' AND invalidated_at IS NULL",s.occurrenceKey());
  refresh(id);metric(detector,created?"candidate_created_total":"signal_merged_total");return new IntakeResult(id,created,true,"APPLIED");
 }
 private boolean quotaExceeded(BadCaseSignal s){
  Long daily=jdbc.queryForObject("SELECT count(*) FROM t_bad_case_signal WHERE create_time>=CURRENT_DATE AND confidence='LOW'",Long.class);
  Long rule=jdbc.queryForObject("SELECT count(*) FROM t_bad_case_signal WHERE create_time>=CURRENT_DATE AND rule_code=?",Long.class,s.ruleCode());
  Long conv=s.conversationId()==null?0L:jdbc.queryForObject("SELECT count(*) FROM t_bad_case_signal x JOIN t_bad_case b ON b.id=x.bad_case_id WHERE b.conversation_id=? AND x.create_time>CURRENT_TIMESTAMP-INTERVAL '1 hour' AND x.confidence='LOW'",Long.class,s.conversationId());
  return daily>=Math.max(0,properties.getIntake().getDailyLimit())||("CONVERSATION_BEHAVIOR".equals(s.source())&&rule>=Math.max(0,properties.getBehavior().getDailyLimitPerRule()))||conv>=Math.max(0,properties.getIntake().getPerConversationHourlyLimit());
 }
 public void refresh(String id){
  var signals=jdbc.queryForList("SELECT * FROM t_bad_case_signal WHERE bad_case_id=? AND invalidated_at IS NULL",id);
  if(signals.isEmpty()){jdbc.update("UPDATE t_bad_case SET signal_count=0,triage_status=CASE WHEN review_status='PENDING' THEN 'SUPPRESSED' ELSE triage_status END,suppressed_reason='全部检测信号已失效',revision=revision+1 WHERE id=?",id);return;}
  boolean high=signals.stream().anyMatch(x->"HIGH".equals(x.get("confidence")));
  String severity=signals.stream().map(x->String.valueOf(x.get("severity"))).min(String::compareTo).orElse("S4");
  String source=signals.stream().map(x->String.valueOf(x.get("source"))).min(Comparator.comparingInt(x->PRIORITY.getOrDefault(x,99))).orElse("AGENT_RUNTIME");
  Timestamp first=signals.stream().map(x->(Timestamp)x.get("occurred_at")).min(Timestamp::compareTo).orElseThrow();
  Timestamp last=signals.stream().map(x->(Timestamp)x.get("occurred_at")).max(Timestamp::compareTo).orElseThrow();
  jdbc.update("""
   UPDATE t_bad_case SET signal_count=?,confidence=?,severity=?,primary_source=?,first_detected_at=?,last_detected_at=?,
    triage_status=CASE WHEN review_status='PENDING' AND ? THEN 'READY_FOR_HUMAN' ELSE triage_status END,
    suppressed_reason=CASE WHEN ? THEN NULL ELSE suppressed_reason END,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?
   """,signals.size(),high?"HIGH":"LOW",severity,source,first,last,high,high,id);
 }
 public void reconcileRunSubjects(){new TransactionTemplate(transactions).executeWithoutResult(tx->{jdbc.execute("SELECT pg_advisory_xact_lock(20261025)");for(var row:jdbc.queryForList("SELECT b.run_id,r.assistant_message_id,b.user_id FROM t_bad_case b JOIN t_agent_run r ON r.run_id=b.run_id AND r.user_id=b.user_id WHERE b.subject_type='AGENT_RUN' AND r.assistant_message_id IS NOT NULL LIMIT 100"))reconcile((String)row.get("run_id"),(String)row.get("assistant_message_id"),(String)row.get("user_id"));});}
 private void reconcile(String run,String message,String user){
  if(run==null||message==null)return;
  var old=jdbc.queryForList("SELECT id FROM t_bad_case WHERE dedup_key=? AND user_id=? FOR UPDATE","RUN:"+run,owner(user));if(old.isEmpty())return;
  String from=(String)old.get(0).get("id");var target=jdbc.queryForList("SELECT id FROM t_bad_case WHERE dedup_key=? AND user_id=? FOR UPDATE","MSG:"+message,owner(user));
  if(target.isEmpty())jdbc.update("UPDATE t_bad_case SET dedup_key=?,subject_type='AGENT_MESSAGE',subject_id=?,message_id=?,snapshot_status='PENDING',snapshot_attempts=0,revision=revision+1 WHERE id=?","MSG:"+message,message,message,from);
  else {String to=(String)target.get(0).get("id");saveReviewHistory(from,"MERGE");for(String table:List.of("t_bad_case_signal","t_bad_case_review_log","t_bad_case_eval_sample","t_bad_case_auto_review_attempt"))jdbc.update("UPDATE "+table+" SET bad_case_id=? WHERE bad_case_id=?",to,from);jdbc.update("DELETE FROM t_bad_case WHERE id=?",from);refresh(to);}
 }
 public void reopen(String id){saveReviewHistory(id,"REOPEN");jdbc.update("""
  UPDATE t_bad_case SET review_status='PENDING',triage_status='READY_FOR_HUMAN',root_cause=NULL,root_cause_detail=NULL,
   fix_action=NULL,fix_ref=NULL,fix_note=NULL,reviewer=NULL,review_time=NULL,deleted=0,archived_at=NULL,
   snapshot_status='PENDING',snapshot_attempts=0,auto_review_result=NULL,suppressed_reason=NULL,revision=revision+1 WHERE id=?
  """,id);}
 public void saveReviewHistory(String id,String action){jdbc.update("""
 INSERT INTO t_bad_case_review_log(id,bad_case_id,action,root_cause,root_cause_detail,fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision)
 SELECT ?,id,?,root_cause,root_cause_detail,fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision FROM t_bad_case WHERE id=?
 """,IdWorker.getIdStr(),action,id);}
 public void invalidateNoFollowup(){new TransactionTemplate(transactions).executeWithoutResult(tx->{for(var row:jdbc.queryForList("""
 SELECT DISTINCT s.bad_case_id FROM t_bad_case_signal s JOIN t_bad_case b ON b.id=s.bad_case_id
 WHERE s.rule_code='NO_FOLLOWUP_AFTER_ANSWER' AND s.invalidated_at IS NULL AND (
 EXISTS(SELECT 1 FROM t_agent_message m WHERE m.conversation_id=b.conversation_id AND m.user_id=b.user_id AND m.role='user' AND m.deleted=0 AND m.create_time>s.occurred_at)
 OR EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=b.message_id AND f.deleted=0))
 """)){String id=(String)row.get("bad_case_id");jdbc.update("UPDATE t_bad_case_signal SET invalidated_at=CURRENT_TIMESTAMP,invalid_reason='后续用户消息或反馈已出现' WHERE bad_case_id=? AND rule_code='NO_FOLLOWUP_AFTER_ANSWER' AND invalidated_at IS NULL",id);refresh(id);}});}
 public void metric(String detector,String name){try{jdbc.update("INSERT INTO t_bad_case_metric(metric_date,detector,metric,value) VALUES(CURRENT_DATE,?,?,1) ON CONFLICT(metric_date,detector,metric) DO UPDATE SET value=t_bad_case_metric.value+1",detector,name);}catch(Exception e){log.debug("Bad case metric unavailable");}}
 String evidence(Map<String,Object> input){
  try {
   String encoded=json.writeValueAsString(BadCaseSanitizer.value(input));
   int max=Math.max(512,Math.min(32000,properties.getIntake().getEvidenceMaxChars()));
   if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=max)return encoded;
   String summary=BadCaseSanitizer.text(encoded,Math.max(64,(max-128)/6));
   String bounded=json.writeValueAsString(Map.of("truncated",true,"summary",summary));
   while(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>max){summary=summary.substring(0,summary.length()/2);bounded=json.writeValueAsString(Map.of("truncated",true,"summary",summary));}
   return bounded;
  }catch(Exception e){throw new IllegalArgumentException("Invalid evidence",e);}
 }
 private static String owner(String s){return s==null?"SYSTEM":s;}
 private void validate(BadCaseSignal s){
  if(s==null||s.source()==null||!PRIORITY.containsKey(s.source())||s.ruleCode()==null||!s.ruleCode().matches("[A-Z0-9_]{1,64}")||!Set.of("HIGH","LOW").contains(s.confidence())||!Set.of("S1","S2","S3","S4").contains(s.severity())||s.subjectId()==null||s.subjectId().length()>64||s.occurrenceKey()==null||s.occurrenceKey().length()>160||s.occurredAt()==null||s.evidence()==null||s.detectorVersion()==null||s.detectorVersion().length()>32)throw new IllegalArgumentException("Invalid bad case signal");
  if(dedup.key(s).length()>160)throw new IllegalArgumentException("Candidate key too long");
 }
}
