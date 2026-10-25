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
import com.fasterxml.jackson.databind.*;
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
@Service @RequiredArgsConstructor
public class BadCaseEvalSampleService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final BadCaseRepository repository;private final BadCaseIntakeService intake;
 public record Draft(String id,String caseId,String datasetName,List<String> tags,String description,JsonNode turns,String testLane,Long revision) {}
 public record ImportRequest(String runId,String datasetName,String runStatus,boolean complete,int repetitions,Instant finishedAt,List<JsonNode> results) {}
 private static final List<String> ASSERTIONS=List.of("expectAll","expectAny","forbidAny","expectTools","forbidTools");
 private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new ClientException("样本 JSON 无法序列化");}}
 private JsonNode parse(Object o){try{return json.readTree(o.toString());}catch(Exception e){throw new ClientException("样本 JSON 损坏");}}
 public static void identifier(String v){if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new ClientException("ID 仅允许字母、数字、下划线和短横线，最多128字");}
 public static void validateTurns(JsonNode turns,String lane,boolean approving){
  if(turns==null||!turns.isArray()||turns.isEmpty()||turns.size()>50)throw new ClientException("样本应有 1 至 50 轮");
  Set<String>ids=new HashSet<>();boolean measured=false;
  for(JsonNode t:turns){identifier(t.path("id").asText());if(!ids.add(t.path("id").asText()))throw new ClientException("turn ID 重复");
   if("CONVERSATION".equals(lane)){
    String question=t.path("question").asText();if(question.isBlank()||question.length()>500)throw new ClientException("问题必须为1至500字符，展开后也不能超过限制");
    String phase=t.path("phase").asText("measure");if(!Set.of("warmup","measure").contains(phase))throw new ClientException("无效 phase");
    boolean has=false;for(String field:ASSERTIONS)if(t.has(field)){if(!t.get(field).isArray())throw new ClientException("断言必须为字符串数组");for(JsonNode v:t.get(field)){if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>500)throw new ClientException("断言内容不合法");has=true;}}
    if("measure".equals(phase)){measured=true;if(approving&&!has)throw new ClientException("每个 measure 轮必须包含有效断言");}
    var fields=t.fieldNames();while(fields.hasNext())if(!Set.of("id","question","phase","expectAll","expectAny","forbidAny","expectTools","forbidTools").contains(fields.next()))throw new ClientException("会话 turn 包含 runner 不支持的字段");
   }else {measured=true;if(approving&&(t.path("testRef").asText().isBlank()||!t.has("fixture")||!t.path("assertions").isArray()||t.path("assertions").isEmpty()))throw new ClientException("业务测试须提供 testRef、fixture 和 assertions");}
   if(approving&&!t.toString().equals(BadCaseSanitizer.text(t.toString(),100000)))throw new ClientException("样本含邮箱、令牌或敏感字段，请先脱敏");
  }
  if(approving&&!measured)throw new ClientException("至少需要一个 measure 轮");
 }
 @Transactional public String save(String candidateId,Draft d,String user){
  var candidate=repository.find(candidateId).orElseThrow(()->new ClientException("候选不存在"));
  if(!Set.of("REVIEWED","FIXED").contains(candidate.reviewStatus())||candidate.rootCause()==null)throw new ClientException("先人工确认根因，才能创建回归样本");
  identifier(d.caseId());identifier(d.datasetName());String lane=d.testLane()==null?"CONVERSATION":d.testLane();
  if(!Set.of("CONVERSATION","SERVICE_TEST","INTEGRATION_TEST").contains(lane))throw new ClientException("无效回归通道");validateTurns(d.turns(),lane,false);
  if(encode(d).length()>100000)throw new ClientException("样本过大");String id=d.id()==null?IdWorker.getIdStr():d.id();
  if(d.id()==null)jdbc.update("INSERT INTO t_bad_case_eval_sample(id,bad_case_id,case_id,dataset_name,tags,description,turns,test_lane,created_by) VALUES(?,?,?,?,?::jsonb,?,?::jsonb,?,?)",id,candidateId,d.caseId(),d.datasetName(),encode(d.tags()==null?List.of():d.tags()),BadCaseSanitizer.text(d.description(),2000),encode(d.turns()),lane,user);
  else if(d.revision()==null||jdbc.update("UPDATE t_bad_case_eval_sample SET case_id=?,dataset_name=?,tags=?::jsonb,description=?,turns=?::jsonb,test_lane=?,status='DRAFT',dataset_fingerprint=NULL,last_run_id=NULL,last_result=NULL,last_run_finished_at=NULL,last_scored_result=NULL,last_scored_finished_at=NULL,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND bad_case_id=? AND revision=?",d.caseId(),d.datasetName(),encode(d.tags()),BadCaseSanitizer.text(d.description(),2000),encode(d.turns()),lane,id,candidateId,d.revision())!=1)throw new ClientException("样本版本冲突，请刷新");
  intake.metric("evaluation","eval_sample_total");return id;
 }
 public List<Map<String,Object>> list(String candidateId){
  var rows=candidateId==null?jdbc.queryForList("SELECT * FROM t_bad_case_eval_sample ORDER BY update_time DESC LIMIT 200"):jdbc.queryForList("SELECT * FROM t_bad_case_eval_sample WHERE bad_case_id=? ORDER BY update_time DESC",candidateId);
  for(var row:rows){row.put("turns",parse(row.get("turns")));row.put("tags",parse(row.get("tags")));}return rows;
 }
 @Transactional public void approve(String id,long revision,String user){var row=sample(id);validateTurns(parse(row.get("turns")),(String)row.get("test_lane"),true);
  String fingerprint=sampleFingerprint(row);
  if(jdbc.update("UPDATE t_bad_case_eval_sample SET status='APPROVED',reviewer=?,dataset_fingerprint=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND status='DRAFT'",user,fingerprint,id,revision)!=1)throw new ClientException("样本版本已变化或不是草稿");
 }
 public List<Map<String,Object>> export(String dataset,String lane){identifier(dataset);List<Map<String,Object>>out=new ArrayList<>();
  for(var row:jdbc.queryForList("SELECT * FROM t_bad_case_eval_sample WHERE dataset_name=? AND test_lane=? AND status IN ('APPROVED','EXPORTED') ORDER BY case_id",dataset,lane)){
   JsonNode turns=parse(row.get("turns"));validateTurns(turns,lane,true);
   if(!sampleFingerprint(row).equals(row.get("dataset_fingerprint")))throw new ClientException("批准后的样本指纹不一致");
   Map<String,Object>c=new LinkedHashMap<>();c.put("id",row.get("case_id"));c.put("tags",parse(row.get("tags")));c.put("description",Objects.toString(row.get("description"),""));c.put("turns",turns);out.add(c);
   jdbc.update("UPDATE t_bad_case_eval_sample SET status='EXPORTED' WHERE id=? AND status='APPROVED'",row.get("id"));
  }return out;
 }
 public List<Map<String,Object>> manifest(String dataset){identifier(dataset);List<Map<String,Object>>out=new ArrayList<>();for(var row:jdbc.queryForList("SELECT * FROM t_bad_case_eval_sample WHERE dataset_name=? AND status IN ('APPROVED','EXPORTED') ORDER BY case_id",dataset)){
  Map<String,String>fingerprints=new LinkedHashMap<>();for(JsonNode t:parse(row.get("turns")))fingerprints.put(t.path("id").asText(),turnFingerprint(row,t.path("id").asText()));
  out.add(Map.of("sampleId",row.get("id"),"caseId",row.get("case_id"),"datasetFingerprint",row.get("dataset_fingerprint"),"testLane",row.get("test_lane"),"turnFingerprints",fingerprints));}return out;}
 private Map<String,Object>sample(String id){return jdbc.queryForList("SELECT * FROM t_bad_case_eval_sample WHERE id=? FOR UPDATE",id).stream().findFirst().orElseThrow(()->new ClientException("样本不存在"));}
 private Object canonical(Object value){if(value instanceof Map<?,?>m){Map<String,Object>sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}if(value instanceof List<?>l)return l.stream().map(this::canonical).toList();return value;}
 private String sampleFingerprint(Map<String,Object>row){return BadCaseDedupPolicy.hash(encode(canonical(Map.of("turns",json.convertValue(parse(row.get("turns")),Object.class),"lane",row.get("test_lane")))));}
 public String turnFingerprint(Map<String,Object>row,String turnId){List<Object>context=new ArrayList<>();boolean found=false;for(JsonNode t:parse(row.get("turns"))){context.add(json.convertValue(t,Object.class));if(t.path("id").asText().equals(turnId)){found=true;break;}}if(!found)throw new ClientException("未知 turnId");return BadCaseDedupPolicy.hash(encode(canonical(Map.of("caseId",row.get("case_id"),"turnId",turnId,"context",context,"lane",row.get("test_lane")))));}
 @Transactional public Map<String,Object> importResults(ImportRequest request){
  if(request==null||!request.complete()||!"COMPLETE".equals(request.runStatus())||request.finishedAt()==null||request.finishedAt().isAfter(Instant.now().plusSeconds(300))||request.repetitions()<1||request.repetitions()>100||request.results()==null||request.results().isEmpty()||request.results().size()>5000)throw new ClientException("必须导入完整运行，提供完成时间、重复次数和逐轮结果");
  identifier(request.runId());if(request.runId().length()>64)throw new ClientException("runId 最多64字");identifier(request.datasetName());
  Map<String,List<JsonNode>>groups=new LinkedHashMap<>();Set<String>keys=new HashSet<>();
  for(JsonNode row:request.results()){String caseId=row.path("caseId").asText(),turnId=row.path("turnId").asText();identifier(caseId);identifier(turnId);int rep=row.path("repetition").asInt();if(rep<1||rep>request.repetitions()||!keys.add(caseId+":"+turnId+":"+rep))throw new ClientException("重复或越界的 repetition");groups.computeIfAbsent(caseId,k->new ArrayList<>()).add(row);}
  int imported=0,recurrences=0;
  for(var group:groups.entrySet()){
   var found=jdbc.queryForList("SELECT id FROM t_bad_case_eval_sample WHERE dataset_name=? AND case_id=? AND status IN ('APPROVED','EXPORTED')",request.datasetName(),group.getKey());if(found.isEmpty())throw new ClientException("样本尚未批准："+group.getKey());
   var sample=sample((String)found.get(0).get("id"));JsonNode turns=parse(sample.get("turns"));
   List<String>measure=new ArrayList<>();for(JsonNode turn:turns)if(!"warmup".equals(turn.path("phase").asText("measure")))measure.add(turn.path("id").asText());
   Set<String>expected=new HashSet<>();for(String t:measure)for(int n=1;n<=request.repetitions();n++)expected.add(t+":"+n);
   for(JsonNode row:group.getValue())if(!expected.remove(row.path("turnId").asText()+":"+row.path("repetition").asInt()))throw new ClientException("结果包含未知或 warmup 轮");
   if(!expected.isEmpty())throw new ClientException("结果缺少 measure turn 或 repetition，不能部分导入");
   boolean valid=true,passed=true;List<JsonNode>failures=new ArrayList<>();int insertedForSample=0;
   for(JsonNode row:group.getValue()){
    String fp=turnFingerprint(sample,row.path("turnId").asText());if(!fp.equals(row.path("datasetTurnFingerprint").asText()))throw new ClientException("数据/断言指纹不匹配，请重新导出 manifest");
    boolean ok="OK".equals(row.path("status").asText())&&row.path("scored").isBoolean()&&row.path("scored").asBoolean()&&row.path("passed").isBoolean();
    if(ok&&(!row.path("checks").isArray()||row.path("checks").isEmpty()))throw new ClientException("scored 结果必须包含逐条 checks");
    if(ok){boolean checkPass=true;for(JsonNode check:row.path("checks")){if(!check.path("passed").isBoolean())throw new ClientException("每条 check 必须记录 passed");checkPass&=check.path("passed").asBoolean();}if(checkPass!=row.path("passed").asBoolean())throw new ClientException("passed 与逐条 checks 不一致");}
    valid&=ok;passed&=ok&&row.path("passed").asBoolean();if(ok&&!row.path("passed").asBoolean())failures.add(row);
    var prior=jdbc.queryForList("SELECT raw_result::text AS raw FROM t_bad_case_eval_result WHERE run_id=? AND case_id=? AND turn_id=? AND repetition=?",request.runId(),group.getKey(),row.path("turnId").asText(),row.path("repetition").asInt());
    if(!prior.isEmpty()&&!parse(prior.get(0).get("raw")).equals(row))throw new ClientException("同一运行结果重复导入时内容不一致");
    insertedForSample+=jdbc.update("""
     INSERT INTO t_bad_case_eval_result(id,sample_id,run_id,case_id,turn_id,repetition,dataset_turn_fingerprint,turn_status,scored,passed,failure_class,checks,run_finished_at,raw_result)
     VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) ON CONFLICT(run_id,case_id,turn_id,repetition) DO NOTHING
     """,IdWorker.getIdStr(),sample.get("id"),request.runId(),group.getKey(),row.path("turnId").asText(),row.path("repetition").asInt(),fp,row.path("status").asText(),row.path("scored").asBoolean()?1:0,row.path("passed").isBoolean()?(row.path("passed").asBoolean()?1:0):null,row.path("failureClass").asText(null),encode(row.path("checks")),Timestamp.from(request.finishedAt()),encode(row));
   }
   imported+=insertedForSample;if(insertedForSample==0)continue;
   Timestamp previous=(Timestamp)sample.get("last_run_finished_at"),scoredAt=(Timestamp)sample.get("last_scored_finished_at");
   boolean newer=previous==null||request.finishedAt().isAfter(previous.toInstant());
   boolean recurrence=valid&&!passed&&failures.stream().anyMatch(x->!"JUDGE".equals(x.path("failureClass").asText()))&&"PASSED".equals(sample.get("last_scored_result"))&&scoredAt!=null&&request.finishedAt().isAfter(scoredAt.toInstant());
   String outcome=valid?(passed?"PASSED":"FAILED"):"ERROR";
   if(newer)jdbc.update("UPDATE t_bad_case_eval_sample SET last_result=?,last_run_id=?,last_run_finished_at=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",outcome,request.runId(),Timestamp.from(request.finishedAt()),sample.get("id"));
   if(valid&&(scoredAt==null||request.finishedAt().isAfter(scoredAt.toInstant())))jdbc.update("UPDATE t_bad_case_eval_sample SET last_scored_result=?,last_scored_finished_at=? WHERE id=?",outcome,Timestamp.from(request.finishedAt()),sample.get("id"));
   for(JsonNode row:failures){String fp=row.path("datasetTurnFingerprint").asText();boolean judge="JUDGE".equals(row.path("failureClass").asText());String rule=recurrence?"REGRESSION_RECURRENCE":(judge?"EVAL_JUDGE_FAILED":"EVAL_ASSERTION_FAILED");
    String subject=BadCaseDedupPolicy.hash(fp+"|"+group.getKey()+"|"+row.path("turnId").asText());
    Map<String,Object>e=new LinkedHashMap<>();e.put("datasetName",request.datasetName());e.put("sampleId",sample.get("id"));e.put("originalBadCaseId",sample.get("bad_case_id"));e.put("runId",request.runId());e.put("caseId",group.getKey());e.put("turnId",row.path("turnId").asText());e.put("checks",json.convertValue(row.path("checks"),Object.class));
    intake.submit(new BadCaseSignal("EVALUATION",rule,judge?"LOW":"HIGH",recurrence?"S2":"S3","EVAL_TURN",subject,null,null,null,request.runId(),"eval:"+request.runId()+":"+group.getKey()+":"+row.path("turnId").asText()+":"+row.path("repetition").asInt(),recurrence?"回归复发":"评测断言未通过",request.finishedAt(),"eval-v1",e));
   }
   if(recurrence){
    var origin=jdbc.queryForList("SELECT review_status,review_time,archived_at FROM t_bad_case WHERE id=? FOR UPDATE",sample.get("bad_case_id"));
    if(!origin.isEmpty()){var old=origin.get(0);Timestamp done=(Timestamp)old.get(old.get("archived_at")!=null?"archived_at":"review_time");if(BadCaseDedupPolicy.shouldReopen((String)old.get("review_status"),old.get("archived_at")!=null,done==null?null:done.toInstant(),request.finishedAt(),"HIGH"))intake.reopen((String)sample.get("bad_case_id"));}
    intake.metric("evaluation","regression_recurrence_total");recurrences++;jdbc.update("UPDATE t_bad_case_eval_sample SET recurrence_count=recurrence_count+1 WHERE id=?",sample.get("id"));}
  }
  return Map.of("imported",imported,"recurrences",recurrences);
 }
}
