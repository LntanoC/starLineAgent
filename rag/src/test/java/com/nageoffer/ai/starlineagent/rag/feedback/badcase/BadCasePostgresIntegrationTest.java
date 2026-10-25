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
import com.fasterxml.jackson.databind.*;
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
/** Real PostgreSQL, isolated schema. Never reads or alters production application rows. */
@EnabledIfEnvironmentVariable(named="STARLINE_BADCASE_TEST_URL",matches=".+")
class BadCasePostgresIntegrationTest {
 private JdbcTemplate jdbc,admin;private DataSourceTransactionManager tx;private BadCaseIntakeService intake;private BadCaseProperties props;private BadCaseRepository repo;private final ObjectMapper json=new ObjectMapper();private String schema;
 private static Path projectRoot(){Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.isRegularFile(p.resolve("resources/database/schema_pg.sql")))p=p.getParent();if(p==null)throw new IllegalStateException("Project root not found");return p;}
 @BeforeEach void setup()throws Exception{
  String url=System.getenv("STARLINE_BADCASE_TEST_URL"),user=System.getenv("STARLINE_BADCASE_TEST_USER"),password=System.getenv("STARLINE_BADCASE_TEST_PASSWORD");
  admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="bc_pool_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);
  Path root=projectRoot();String ddl=Files.readString(root.resolve("resources/database/schema_pg.sql"));
  for(String table:List.of("t_user","t_message_feedback","t_agent_message","t_agent_run","t_agent_run_node","t_bad_case","t_starline_event_reminder")){
   var matcher=Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?"+table+" \\([\\s\\S]*?\\n\\);",Pattern.CASE_INSENSITIVE).matcher(ddl);assertThat(matcher.find()).as(table).isTrue();jdbc.execute(matcher.group());
  }
  jdbc.update("INSERT INTO t_bad_case(id,message_id,conversation_id,user_id,message_type,vote) VALUES('legacy','legacy-message','c','u','AGENT',-1)");
  String migration=Files.readString(root.resolve("resources/database/migrations/20261025_bad_case_candidate_pool.sql"));jdbc.execute(migration);jdbc.execute(migration);
  jdbc.execute(Files.readString(root.resolve("resources/database/migrations/20261025_starline_delivery_failure.sql")));
  props=new BadCaseProperties();for(String code:List.of("agent-runtime","user-correction","behavior","sampling","evaluation")){var d=new BadCaseProperties.Detector();d.setMode("ON");props.getDetectors().put(code,d);}
  props.getIntake().setPerConversationHourlyLimit(100);
  intake=new BadCaseIntakeService(jdbc,json,tx,new BadCaseDedupPolicy(),props);repo=new BadCaseRepository(jdbc,json);
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null&&schema.matches("bc_pool_it_[a-f0-9]{32}"))admin.execute("DROP SCHEMA "+schema+" CASCADE");}
 private BadCaseSignal signal(String rule,String confidence,String occurrence,String message,Instant at){String source="USER_DOWNVOTE".equals(rule)?"USER_FEEDBACK":("REPEAT_QUESTION".equals(rule)||"NO_FOLLOWUP_AFTER_ANSWER".equals(rule)?"CONVERSATION_BEHAVIOR":"AGENT_RUNTIME");return new BadCaseSignal(source,rule,confidence,"S3","AGENT_MESSAGE",message,message,"c","u",null,occurrence,"candidate",at,"it-v1",Map.of());}
 private long count(String table,String condition){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+condition,Long.class);}
 @Test void legacyMigrationIsReplayableAndDoesNotFabricateCompleteSnapshot(){assertThat(count("t_bad_case_signal","bad_case_id='legacy'")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT snapshot_status FROM t_bad_case WHERE id='legacy'",String.class)).isEqualTo("PARTIAL");}
 @Test void twentyConcurrentIdenticalSignalsCreateOneCandidateAndOneSignal()throws Exception{
  var s=signal("TOOL_FINAL_FAILED","HIGH","tool:run:node","m1",Instant.now());var pool=Executors.newFixedThreadPool(8);try{List<Callable<BadCaseIntake.IntakeResult>>tasks=new ArrayList<>();for(int i=0;i<20;i++)tasks.add(()->intake.submit(s));var results=pool.invokeAll(tasks);for(var future:results)future.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertThat(count("t_bad_case","dedup_key='MSG:m1'")).isEqualTo(1);assertThat(count("t_bad_case_signal","occurrence_key='tool:run:node'")).isEqualTo(1);
 }
 @Test void differentEntrancesMergeWithoutAddingWeakConfidence(){String id=intake.submit(signal("REPEAT_QUESTION","LOW","repeat:1","m2",Instant.now())).candidateId();intake.submit(signal("EVIDENCE_ANSWER_MISMATCH","LOW","mismatch:1","m2",Instant.now()));assertThat(jdbc.queryForObject("SELECT confidence FROM t_bad_case WHERE id=?",String.class,id)).isEqualTo("LOW");intake.submit(signal("TOOL_FINAL_FAILED","HIGH","tool:2","m2",Instant.now()));intake.submit(signal("USER_DOWNVOTE","HIGH","feedback:m2:u","m2",Instant.now()));assertThat(repo.find(id).orElseThrow().candidate()).containsEntry("confidence","HIGH").containsEntry("signal_count",4);}
 @Test void reviewedCandidateKeepsItsHumanDiagnosis(){String id=intake.submit(signal("REPEAT_QUESTION","LOW","repeat:3","m3",Instant.now())).candidateId();jdbc.update("UPDATE t_bad_case SET review_status='REVIEWED',root_cause='OTHER',root_cause_detail='human diagnosis' WHERE id=?",id);intake.submit(signal("TOOL_FINAL_FAILED","HIGH","tool:3","m3",Instant.now()));var item=repo.find(id).orElseThrow();assertThat(item.reviewStatus()).isEqualTo("REVIEWED");assertThat(item.rootCauseDetail()).isEqualTo("human diagnosis");}
 @Test void archivedFixedOnlyReopensForNewHighOccurrenceAndPreservesHistory(){String id=intake.submit(signal("TOOL_FINAL_FAILED","HIGH","tool:old","m4",Instant.now().minusSeconds(180))).candidateId();Instant archived=Instant.now().minusSeconds(60);jdbc.update("UPDATE t_bad_case SET review_status='FIXED',root_cause='TOOL_EXECUTION',fix_note='old fix',review_time=?,deleted=1,archived_at=? WHERE id=?",Timestamp.from(archived),Timestamp.from(archived),id);
  intake.submit(signal("TOOL_FINAL_FAILED","HIGH","tool:old-scan","m4",archived.minusSeconds(30)));assertThat(count("t_bad_case","id='"+id+"' AND deleted=1")).isEqualTo(1);
  intake.submit(signal("TOOL_FINAL_FAILED","HIGH","tool:new","m4",Instant.now()));var item=repo.find(id).orElseThrow();assertThat(item.reviewStatus()).isEqualTo("PENDING");assertThat(item.rootCause()).isNull();assertThat(item.fixNote()).isNull();assertThat(item.candidate()).containsEntry("snapshot_status","PENDING");assertThat(count("t_bad_case_review_log","action='REOPEN'")).isEqualTo(1);
 }
 @Test void invalidatingQuietSignalDoesNotSuppressValidDownvote(){String id=intake.submit(signal("NO_FOLLOWUP_AFTER_ANSWER","LOW","quiet:5","m5",Instant.now().minusSeconds(100))).candidateId();intake.submit(signal("USER_DOWNVOTE","HIGH","feedback:m5:u","m5",Instant.now().minusSeconds(90)));jdbc.update("INSERT INTO t_agent_message(id,conversation_id,user_id,role,content) VALUES('user-later','c','u','user','谢谢')");intake.invalidateNoFollowup();assertThat(repo.find(id).orElseThrow().candidate()).containsEntry("signal_count",1).containsEntry("triage_status","READY_FOR_HUMAN");}
 @Test void runCandidateMergesIntoAnAlreadyExistingMessageCandidate(){var run=new BadCaseSignal("AGENT_RUNTIME","AGENT_RUN_FAILED","HIGH","S2","AGENT_RUN","run6",null,"c","u","run6","run:6","failure",Instant.now(),"it",Map.of());String old=intake.submit(run).candidateId();String target=intake.submit(signal("USER_DOWNVOTE","HIGH","feedback:m6:u","m6",Instant.now())).candidateId();jdbc.update("INSERT INTO t_agent_run(id,run_id,conversation_id,user_id,assistant_message_id,run_status) VALUES('r6','run6','c','u','m6','FAILED')");intake.reconcileRunSubjects();assertThat(repo.find(old)).isEmpty();assertThat(repo.find(target).orElseThrow().candidate()).containsEntry("signal_count",2);}
 @Test void reviewUsesOptimisticLockAndAuditHistory(){String id=intake.submit(signal("USER_DOWNVOTE","HIGH","feedback:m7:u","m7",Instant.now())).candidateId();long rev=repo.find(id).orElseThrow().revision();var req=new BadCaseService.ReviewRequest("OTHER","human","NONE","","","REVIEWED",rev);assertThat(repo.review(id,req,"admin")).isEqualTo(1);assertThat(repo.review(id,req,"other-admin")).isZero();assertThat(repo.reviewHistory(id)).hasSize(1);}
 @Test void allRepetitionsPassBeforePassedAndOutOfOrderImportsDoNotInventRecurrence()throws Exception{
  String origin=intake.submit(signal("USER_DOWNVOTE","HIGH","feedback:m8:u","m8",Instant.now().minusSeconds(600))).candidateId();jdbc.update("UPDATE t_bad_case SET review_status='REVIEWED',root_cause='OTHER' WHERE id=?",origin);
  var svc=new BadCaseEvalSampleService(jdbc,json,repo,intake);String sample=svc.save(origin,new BadCaseEvalSampleService.Draft(null,"bc8","bad-case-regression",List.of("bad-case"),"stable fixture",json.readTree("[{\"id\":\"t1\",\"question\":\"信息未知应显示什么状态？\",\"expectAll\":[\"待公布\"],\"phase\":\"measure\"}]"),"CONVERSATION",null),"admin");svc.approve(sample,0,"admin");String fp=(String)((Map<?,?>)svc.manifest("bad-case-regression").get(0).get("turnFingerprints")).get("t1");Instant base=Instant.now().minusSeconds(300);
  var failed=importRequest("run1",fp,base,true,false);new TransactionTemplate(tx).executeWithoutResult(t->svc.importResults(failed));assertThat(jdbc.queryForObject("SELECT last_result FROM t_bad_case_eval_sample WHERE id=?",String.class,sample)).isEqualTo("FAILED");
  var passed=importRequest("run2",fp,base.plusSeconds(60),true,true);new TransactionTemplate(tx).executeWithoutResult(t->svc.importResults(passed));assertThat(jdbc.queryForObject("SELECT last_result FROM t_bad_case_eval_sample WHERE id=?",String.class,sample)).isEqualTo("PASSED");
  var oldRun=importRequest("oldrun",fp,base.minusSeconds(30),false,false);new TransactionTemplate(tx).executeWithoutResult(t->svc.importResults(oldRun));assertThat(jdbc.queryForObject("SELECT recurrence_count FROM t_bad_case_eval_sample WHERE id=?",Integer.class,sample)).isZero();assertThat(jdbc.queryForObject("SELECT last_result FROM t_bad_case_eval_sample WHERE id=?",String.class,sample)).isEqualTo("PASSED");
  jdbc.update("UPDATE t_bad_case SET review_status='FIXED',review_time=? WHERE id=?",Timestamp.from(base.plusSeconds(75)),origin);
  var recurrence=importRequest("run3",fp,base.plusSeconds(120),true,false);new TransactionTemplate(tx).executeWithoutResult(t->svc.importResults(recurrence));new TransactionTemplate(tx).executeWithoutResult(t->svc.importResults(recurrence));assertThat(jdbc.queryForObject("SELECT recurrence_count FROM t_bad_case_eval_sample WHERE id=?",Integer.class,sample)).isEqualTo(1);assertThat(repo.find(origin).orElseThrow().reviewStatus()).isEqualTo("PENDING");assertThat(repo.find(origin).orElseThrow().rootCause()).isNull();
 }

 @Test void frozenSamplingUsesActualProbabilityAfterDailyCapAndExcludesAdmin(){
  jdbc.update("INSERT INTO t_user(id,username,password,role) VALUES('u','normal-user','synthetic','user'),('admin','internal-admin','synthetic','admin')");
  java.time.LocalDateTime date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1).atTime(12,0);
  for(int i=0;i<11;i++){String user=i==10?"admin":"u",id="sample"+i;jdbc.update("INSERT INTO t_agent_message(id,conversation_id,user_id,role,content,create_time) VALUES(?,'sample-conv',?,'assistant','synthetic',?)",id,user,date);jdbc.update("INSERT INTO t_agent_run(id,run_id,conversation_id,user_id,assistant_message_id,run_status) VALUES(?,?,'sample-conv',?,?,'NORMAL')",id,id,user,id);}
  props.getSampling().setRateBp(2000);props.getSampling().setDailyLimit(1);
  var detector=new UnvotedSampleDetector(jdbc,json,props,tx);var context=new BadCaseDetector.BadCaseScanContext(Instant.now().minusSeconds(86400*7),Instant.now(),50,false);
  var first=detector.scan(context);assertThat(first.signals()).hasSize(1);assertThat(first.signals().get(0).evidence()).containsEntry("inclusionProbability",.1d);
  props.getSampling().setSeed("changed-after-freeze");assertThat(detector.scan(context).signals().get(0).messageId()).isEqualTo(first.signals().get(0).messageId());
  intake.submit(first.signals().get(0));detector.checkpoint(context);assertThat(detector.scan(context).signals()).isEmpty();assertThat(repo.stats()).containsKeys("quality","measurement","autoReview");
 }
 @Test void incompleteEvidenceAndJudgeErrorsNeverWriteHumanStatus()throws Exception{
  String id=intake.submit(signal("REPEAT_QUESTION","LOW","repeat:judge","judge-message",Instant.now())).candidateId();jdbc.update("UPDATE t_bad_case SET snapshot_status='PARTIAL',capture_status='PARTIAL' WHERE id=?",id);
  var selector=org.mockito.Mockito.mock(com.nageoffer.ai.starlineagent.infra.model.ModelSelector.class);
  var judge=new BadCaseAutoReviewService(jdbc,json,props,repo,tx,selector,List.of(),intake);
  try{judge.review(id);var item=repo.find(id).orElseThrow();assertThat(item.reviewStatus()).isEqualTo("PENDING");assertThat(item.rootCause()).isNull();assertThat(((Map<?,?>)item.candidate().get("auto_review_result")).get("verdict")).isEqualTo("UNCERTAIN");org.mockito.Mockito.verifyNoInteractions(selector);
   jdbc.update("UPDATE t_bad_case SET snapshot_status='COMPLETE',capture_status='COMPLETE',revision=revision+1 WHERE id=?",id);judge.review(id);assertThat(repo.find(id).orElseThrow().reviewStatus()).isEqualTo("PENDING");assertThat(count("t_bad_case_auto_review_attempt","error_code IS NOT NULL")).isEqualTo(1);
  }finally{judge.close();}
 }
 @Test void measurementQueueIncludesMachineSuppressedNormalSamples(){var signal=new BadCaseSignal("UNVOTED_SAMPLE","UNVOTED_SAMPLE_SELECTED","LOW","S4","AGENT_MESSAGE","measured","measured","c","u",null,"sample:batch:measured","measurement",Instant.now(),"it",Map.of("purpose","MEASUREMENT"));String id=intake.submit(signal).candidateId();jdbc.update("UPDATE t_bad_case SET triage_status='SUPPRESSED',auto_review_result='{\"verdict\":\"LIKELY_OK\"}'::jsonb WHERE id=?",id);assertThat(repo.page(null,null,null,null,null,null,null,true,1,10).records()).extracting(BadCaseRecord::id).contains(id);assertThat(repo.page("PENDING",null,"READY_FOR_HUMAN",null,null,null,null,false,1,10).records()).extracting(BadCaseRecord::id).doesNotContain(id);}
 private BadCaseEvalSampleService.ImportRequest importRequest(String run,String fp,Instant at,boolean first,boolean second)throws Exception{List<JsonNode>rows=new ArrayList<>();for(int i=1;i<=2;i++){boolean passed=i==1?first:second;var row=json.createObjectNode().put("caseId","bc8").put("turnId","t1").put("repetition",i).put("datasetTurnFingerprint",fp).put("status","OK").put("scored",true).put("passed",passed);row.set("checks",json.createArrayNode().add(json.createObjectNode().put("passed",passed)));rows.add(row);}return new BadCaseEvalSampleService.ImportRequest(run,"bad-case-regression","COMPLETE",true,2,at,rows);}
}
