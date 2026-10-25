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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
@Component @RequiredArgsConstructor
public class UnvotedSampleDetector implements BadCaseDetector {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final BadCaseProperties p;private final PlatformTransactionManager transactions;
 public String detectorCode(){return "sampling";}
 public static List<String> select(List<String> ids,String seed,int n){return ids.stream().sorted(Comparator.comparing((String id)->BadCaseDedupPolicy.hash(seed+":"+id)).thenComparing(id->id)).limit(Math.max(0,n)).toList();}
 public DetectorScanResult scan(BadCaseScanContext c){
  if(c.dryRun())return new DetectorScanResult(List.of(),0);
  LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
  freeze(day,"DISCOVERY",1,p.getSampling().getDailyLimit());
  freeze(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),"MEASUREMENT",7,p.getSampling().getMeasurementWeeklyLimit());
  var rows=jdbc.queryForList("""
   SELECT m.*,b.purpose,b.inclusion_probability,b.seed,a.conversation_id,a.create_time
   FROM t_bad_case_sample_member m JOIN t_bad_case_sample_batch b ON b.id=m.batch_id
    JOIN t_agent_message a ON a.id=m.message_id AND a.user_id=m.user_id
   WHERE m.selected=TRUE AND m.processed=FALSE ORDER BY b.sample_date,m.score LIMIT ?
   """,c.limit());List<BadCaseSignal>signals=new ArrayList<>();for(var row:rows){String id=(String)row.get("message_id");double probability=((Number)row.get("inclusion_probability")).doubleValue();signals.add(new BadCaseSignal("UNVOTED_SAMPLE","UNVOTED_SAMPLE_SELECTED","LOW","S4","AGENT_MESSAGE",id,id,(String)row.get("conversation_id"),(String)row.get("user_id"),null,"sample:"+row.get("batch_id")+":"+id,"未点踩会话抽样",((Timestamp)row.get("create_time")).toInstant(),"sampling-v1",Map.of("batchId",row.get("batch_id"),"purpose",row.get("purpose"),"stratumKey","ALL","inclusionProbability",probability,"sampleWeight",1/probability)));}return new DetectorScanResult(signals,rows.size());
 }
 public void checkpoint(BadCaseScanContext c){if(!c.dryRun())jdbc.update("UPDATE t_bad_case_sample_member m SET processed=TRUE WHERE m.selected=TRUE AND m.processed=FALSE AND EXISTS(SELECT 1 FROM t_bad_case_signal s WHERE s.occurrence_key='sample:'||m.batch_id||':'||m.message_id)");}
 private void freeze(LocalDate date,String purpose,int days,int cap){new TransactionTemplate(transactions).executeWithoutResult(tx->{
  jdbc.execute("SELECT pg_advisory_xact_lock(20261026)");
  if(jdbc.queryForObject("SELECT count(*) FROM t_bad_case_sample_batch WHERE sample_date=? AND engine='AGENT' AND purpose=? AND stratum_key='ALL'",Long.class,date,purpose)>0)return;
  LocalDateTime end=date.atStartOfDay(),start=date.minusDays(days).atStartOfDay();
  var rows=jdbc.queryForList("""
   SELECT m.id,m.user_id,m.create_time FROM t_agent_message m JOIN t_user u ON u.id=m.user_id
   WHERE m.role='assistant' AND m.deleted=0 AND m.message_status='NORMAL' AND u.deleted=0 AND u.role<>'admin'
    AND lower(u.username) NOT LIKE 'test%%' AND lower(u.username) NOT LIKE 'eval%%'
    AND m.create_time>=? AND m.create_time<? AND m.create_time<CURRENT_TIMESTAMP-(?*INTERVAL '1 minute')
    AND EXISTS(SELECT 1 FROM t_agent_run r WHERE r.assistant_message_id=m.id AND r.user_id=m.user_id AND r.deleted=0 AND r.run_status IN ('NORMAL','FAILED','INTERRUPTED') AND (r.capture_status<>'DISABLED' OR m.content IS NOT NULL))
    AND NOT EXISTS(SELECT 1 FROM t_message_feedback f WHERE f.message_id=m.id AND f.vote=-1 AND f.deleted=0)
    AND NOT EXISTS(SELECT 1 FROM t_bad_case b WHERE b.message_id=m.id AND (b.review_status<>'PENDING' OR b.triage_status='READY_FOR_HUMAN' OR b.confidence='HIGH'))
   ORDER BY m.create_time,m.id LIMIT 50000
   """,start,end,Math.max(1,p.getSampling().getMinMessageAgeMinutes()));
  String seed=p.getSampling().getSeed()+":"+date+":"+purpose,batch=IdWorker.getIdStr();
  int n="MEASUREMENT".equals(purpose)?Math.min(rows.size(),Math.max(0,cap)):Math.min(Math.max(0,cap),(int)Math.ceil(rows.size()*Math.max(0,Math.min(10000,p.getSampling().getRateBp()))/10000d));
  List<String>ids=rows.stream().map(x->(String)x.get("id")).toList();Set<String>chosen=new HashSet<>(select(ids,seed,n));
  double probability=rows.isEmpty()?0:(double)n/rows.size();String frameHash=BadCaseDedupPolicy.hash(String.join(",",ids));
  jdbc.update("""
   INSERT INTO t_bad_case_sample_batch(id,sample_date,engine,purpose,stratum_key,rate_bp,seed,frame_count,selected_count,inclusion_probability,frame_high_water,frame_definition_hash,status)
   VALUES(?,?,'AGENT',?,'ALL',?,?,?,?,?,?::jsonb,?,'FROZEN')
   """,batch,date,purpose,p.getSampling().getRateBp(),BadCaseDedupPolicy.hash(seed),rows.size(),n,probability,encode(Map.of("start",start.toString(),"endExclusive",end.toString(),"limit",50000,"order","create_time,id","seed",seed)),frameHash);
  for(var row:rows)jdbc.update("INSERT INTO t_bad_case_sample_member(batch_id,message_id,user_id,score,selected) VALUES(?,?,?,?,?)",batch,row.get("id"),row.get("user_id"),BadCaseDedupPolicy.hash(seed+":"+row.get("id")),chosen.contains(row.get("id")));
 });}
 private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
}
