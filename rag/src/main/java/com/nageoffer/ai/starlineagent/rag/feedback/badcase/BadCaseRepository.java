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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.convention.GroundingChunk;
import com.nageoffer.ai.starlineagent.framework.convention.SourceRef;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/** 台账与快照持久化。所有筛选条件均使用绑定参数。 */
@Repository
@RequiredArgsConstructor
public class BadCaseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Feedback(String messageId, String conversationId, String userId, int vote, String reason, String comment) {}
    public record Page(List<BadCaseRecord> records, long total, int current, int size, long pages) {}

    public List<Feedback> pendingFeedback(Date since, int limit) {
        return jdbc.query("""
                SELECT f.message_id,f.conversation_id,f.user_id,f.vote,f.reason,f.comment
                FROM t_message_feedback f
                WHERE f.vote=-1 AND f.deleted=0 AND (?::timestamp IS NULL OR f.update_time>=?)
                  AND NOT EXISTS (SELECT 1 FROM t_bad_case_signal s WHERE s.rule_code='USER_DOWNVOTE' AND s.occurrence_key='feedback:'||f.message_id||':'||f.user_id)
                ORDER BY f.update_time,f.id LIMIT ?
                """, (rs, n) -> new Feedback(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getString(6)),
                since == null ? null : new Timestamp(since.getTime()), since, limit);
    }

    public Page page(String status,String cause,int current,int size) {return page(status,cause,null,null,null,null,null,false,current,size);}
    public Page page(String status,String cause,String triage,String confidence,String source,String severity,String subject,boolean measurement,int current,int size) {
        String where=" WHERE b.deleted=0";List<Object> args=new ArrayList<>();
        String[] columns={"b.review_status","b.root_cause","b.triage_status","b.confidence","b.severity","b.subject_type"};
        String[] values={status,cause,triage,confidence,severity,subject};
        for(int i=0;i<columns.length;i++)if(values[i]!=null&&!values[i].isBlank()){where+=" AND "+columns[i]+"=?";args.add(values[i]);}
        if(source!=null&&!source.isBlank()){where+=" AND EXISTS(SELECT 1 FROM t_bad_case_signal s WHERE s.bad_case_id=b.id AND s.invalidated_at IS NULL AND s.source=?)";args.add(source);}
        if(measurement)where+=" AND EXISTS(SELECT 1 FROM t_bad_case_signal s WHERE s.bad_case_id=b.id AND s.evidence->>'purpose'='MEASUREMENT' AND s.invalidated_at IS NULL)";
        long total=jdbc.queryForObject("SELECT count(*) FROM t_bad_case b"+where,Long.class,args.toArray());
        String select="id,message_id,conversation_id,user_id,message_type,run_id,trace_matched_by_fallback,trace_match_status,vote,reason,comment,left(user_question,300) user_question,left(assistant_answer,300) assistant_answer,NULL::text thinking_content,message_status,NULL::jsonb sources,NULL::jsonb retrieved_chunks,NULL::jsonb trace_nodes,suspicions,evidence_notes,message_create_time,root_cause,root_cause_detail,fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision,create_time,update_time,subject_type,subject_id,dedup_key,title,primary_source,confidence,severity,triage_status,signal_count,first_detected_at,last_detected_at,snapshot_status,auto_review_result,auto_review_model,auto_reviewed_at,suppressed_reason,capture_status,round_count,tool_call_count";
        args.add(size);args.add((current-1)*size);
        return new Page(jdbc.query("SELECT "+select+" FROM t_bad_case b"+where+" ORDER BY severity,CASE WHEN primary_source IN ('USER_FEEDBACK','USER_CORRECTION') THEN 0 ELSE 1 END,last_detected_at DESC,id DESC LIMIT ? OFFSET ?",this::map,args.toArray()),total,current,size,(total+size-1)/size);
    }

    public Optional<BadCaseRecord> find(String id) {
        return jdbc.query("SELECT * FROM t_bad_case WHERE id=? AND deleted=0",this::map,id).stream().findFirst();
    }

    @org.springframework.transaction.annotation.Transactional
    public int review(String id,BadCaseService.ReviewRequest r,String reviewer) {
        var rows=jdbc.queryForList("SELECT revision FROM t_bad_case WHERE id=? AND deleted=0 FOR UPDATE",id);
        if(rows.isEmpty()||((Number)rows.get(0).get("revision")).longValue()!=r.revision())return 0;
        if("FIXED".equals(r.reviewStatus())&&jdbc.queryForObject("SELECT count(*) FROM t_bad_case_eval_sample WHERE bad_case_id=? AND status<>'RETIRED' AND last_result IS DISTINCT FROM 'PASSED'",Long.class,id)>0)
            throw new com.nageoffer.ai.starlineagent.framework.exception.ClientException("关联评测尚未全部通过，不能标记已修复");
        int updated=jdbc.update("UPDATE t_bad_case SET root_cause=?,root_cause_detail=?,fix_action=?,fix_ref=?,fix_note=?,review_status=?,reviewer=?,review_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP,revision=revision+1 WHERE id=? AND revision=? AND deleted=0",r.rootCause(),r.rootCauseDetail(),r.fixAction(),r.fixRef(),r.fixNote(),r.reviewStatus(),reviewer,id,r.revision());
        jdbc.update("INSERT INTO t_bad_case_review_log(id,bad_case_id,action,root_cause,root_cause_detail,fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision) SELECT ?,id,'REVIEW',root_cause,root_cause_detail,fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision FROM t_bad_case WHERE id=?",IdWorker.getIdStr(),id);
        String metric=java.util.Set.of("REVIEWED","FIXED").contains(r.reviewStatus())?"human_confirmed_total":("IGNORED".equals(r.reviewStatus())?"human_ignored_total":null);
        if(metric!=null)jdbc.update("INSERT INTO t_bad_case_metric(metric_date,detector,metric,value) VALUES(CURRENT_DATE,'human',?,1) ON CONFLICT(metric_date,detector,metric) DO UPDATE SET value=t_bad_case_metric.value+1",metric);
        return updated;
    }
    public List<Map<String,Object>> signals(String id){return jdbc.queryForList("SELECT * FROM t_bad_case_signal WHERE bad_case_id=? ORDER BY occurred_at,id",id).stream().map(this::jsonMap).toList();}
    public List<Map<String,Object>> reviewHistory(String id){return jdbc.queryForList("SELECT * FROM t_bad_case_review_log WHERE bad_case_id=? ORDER BY create_time,id",id);}
    public int promote(String id,long revision){return jdbc.update("UPDATE t_bad_case SET triage_status='READY_FOR_HUMAN',suppressed_reason=NULL,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND deleted=0 AND review_status='PENDING'",id,revision);}
    private Map<String,Object> jsonMap(Map<String,Object> row){Object v=row.get("evidence");if(v!=null)try{row.put("evidence",json.readValue(v.toString(),Map.class));}catch(Exception e){row.put("evidence",Map.of("status","unavailable"));}return row;}

    public Map<String,Object> stats() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total",jdbc.queryForObject("SELECT count(*) FROM t_bad_case WHERE deleted=0",Long.class));
        result.put("byStatus",jdbc.queryForList("SELECT review_status AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY review_status ORDER BY review_status"));
        result.put("byRootCause",jdbc.queryForList("SELECT coalesce(root_cause,'UNREVIEWED') AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY root_cause ORDER BY count(*) DESC"));
        result.put("bySource",jdbc.queryForList("SELECT source AS value,count(*) AS count FROM t_bad_case_signal WHERE invalidated_at IS NULL GROUP BY source"));
        result.put("byRule",jdbc.queryForList("SELECT rule_code AS value,count(*) AS count FROM t_bad_case_signal WHERE invalidated_at IS NULL GROUP BY rule_code"));
        result.put("byTriage",jdbc.queryForList("SELECT triage_status AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY triage_status"));
        result.put("byConfidence",jdbc.queryForList("SELECT confidence AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY confidence"));
        result.put("highPending",jdbc.queryForObject("SELECT count(*) FROM t_bad_case WHERE deleted=0 AND review_status='PENDING' AND triage_status='READY_FOR_HUMAN' AND confidence='HIGH'",Long.class));
        result.put("lowPending",jdbc.queryForObject("SELECT count(*) FROM t_bad_case WHERE deleted=0 AND review_status='PENDING' AND confidence='LOW' AND triage_status IN ('OBSERVING','AUTO_REVIEW_PENDING')",Long.class));
        result.put("todaySignals",jdbc.queryForObject("SELECT count(*) FROM t_bad_case_signal WHERE create_time>=CURRENT_DATE AND invalidated_at IS NULL",Long.class));
        result.put("metrics",jdbc.queryForList("SELECT * FROM t_bad_case_metric WHERE metric_date=CURRENT_DATE ORDER BY detector,metric"));
        result.put("quality",jdbc.queryForMap("SELECT count(*) FILTER(WHERE confidence='HIGH' AND review_status IN ('REVIEWED','FIXED')) AS high_confirmed,count(*) FILTER(WHERE confidence='HIGH' AND review_status IN ('REVIEWED','FIXED','IGNORED')) AS high_decided,count(*) FILTER(WHERE confidence='LOW' AND auto_review_result->>'verdict'='LIKELY_BAD' AND review_status IN ('REVIEWED','FIXED')) AS auto_promoted_confirmed,count(*) FILTER(WHERE confidence='LOW' AND auto_review_result->>'verdict'='LIKELY_BAD' AND review_status IN ('REVIEWED','FIXED','IGNORED')) AS auto_promoted_decided FROM t_bad_case"));
        result.put("measurement",jdbc.queryForList("SELECT s.id,s.frame_count,s.selected_count,s.inclusion_probability,count(c.id) FILTER(WHERE c.review_status IN ('REVIEWED','FIXED','IGNORED')) AS reviewed,count(c.id) FILTER(WHERE c.review_status IN ('REVIEWED','FIXED')) AS confirmed,CASE WHEN count(c.id) FILTER(WHERE c.review_status IN ('REVIEWED','FIXED','IGNORED'))=s.selected_count AND s.selected_count>0 THEN count(c.id) FILTER(WHERE c.review_status IN ('REVIEWED','FIXED'))::numeric/s.selected_count ELSE NULL END AS weighted_problem_rate FROM t_bad_case_sample_batch s LEFT JOIN t_bad_case_sample_member m ON m.batch_id=s.id AND m.selected=TRUE LEFT JOIN t_bad_case c ON c.message_id=m.message_id AND c.user_id=m.user_id WHERE s.purpose='MEASUREMENT' GROUP BY s.id ORDER BY s.sample_date DESC LIMIT 20"));
        result.put("autoReview",jdbc.queryForList("SELECT model,count(*) AS attempts,sum(duration_ms) AS duration_ms,count(*) FILTER(WHERE error_code IS NOT NULL) AS errors FROM t_bad_case_auto_review_attempt GROUP BY model"));
        result.put("recurrenceByRootCause",jdbc.queryForList("SELECT coalesce(b.root_cause,'UNREVIEWED') AS root_cause,sum(s.recurrence_count) AS recurrence_count FROM t_bad_case_eval_sample s JOIN t_bad_case b ON b.id=s.bad_case_id GROUP BY b.root_cause"));
        return result;
    }

    /** Old workflow trace storage is retired; Agent tool duration is not a retrieval-only P95. */
    public long retrieveP95(long fallback) {
        return Math.max(1, fallback);
    }

    /** 只归档已处理记录，保留唯一键墓碑，防止旧反馈再次建单。 */
    public int archiveResolved(int days) {
        if(days<=0) return 0;
        return jdbc.update("""
                UPDATE t_bad_case SET deleted=1,user_question=NULL,assistant_answer=NULL,thinking_content=NULL,
                 comment=NULL,sources=NULL,retrieved_chunks=NULL,trace_nodes=NULL,suspicions=NULL,evidence_notes=NULL,
                 archived_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP
                WHERE deleted=0 AND review_status IN ('FIXED','IGNORED')
                 AND review_time<CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                """,days);
    }

    private BadCaseRecord map(ResultSet rs,int row) throws SQLException {
        BadCaseReport r=BadCaseReport.builder()
                .messageId(rs.getString("message_id")).conversationId(rs.getString("conversation_id"))
                .userId(rs.getString("user_id")).messageType(rs.getString("message_type")).runId(rs.getString("run_id"))
                .traceMatchedByFallback(rs.getInt("trace_matched_by_fallback")==1).traceMatchStatus(rs.getString("trace_match_status"))
                .vote(rs.getInt("vote")).reason(rs.getString("reason")).comment(rs.getString("comment"))
                .userQuestion(rs.getString("user_question")).assistantAnswer(rs.getString("assistant_answer"))
                .thinkingContent(rs.getString("thinking_content")).messageStatus(rs.getString("message_status"))
                .sources(decode(rs.getString("sources"),new TypeReference<List<SourceRef>>(){}))
                .retrievedChunks(decode(rs.getString("retrieved_chunks"),new TypeReference<List<GroundingChunk>>(){}))
                .traceNodes(decode(rs.getString("trace_nodes"),new TypeReference<List<BadCaseReport.TraceNodeSummary>>(){}))
                .suspicions(decode(rs.getString("suspicions"),new TypeReference<List<String>>(){}))
                .evidenceNotes(decode(rs.getString("evidence_notes"),new TypeReference<List<String>>(){}))
                .captureStatus(rs.getString("capture_status")).roundCount((Integer)rs.getObject("round_count")).toolCallCount((Integer)rs.getObject("tool_call_count"))
                .messageCreateTime(rs.getTimestamp("message_create_time")).build();
        return new BadCaseRecord(rs.getString("id"),r,rs.getString("root_cause"),rs.getString("root_cause_detail"),
                rs.getString("fix_action"),rs.getString("fix_ref"),rs.getString("fix_note"),rs.getString("review_status"),
                rs.getString("reviewer"),rs.getTimestamp("review_time"),rs.getLong("revision"),
                rs.getTimestamp("create_time"),rs.getTimestamp("update_time"),candidate(rs));
    }

    private Map<String,Object> candidate(ResultSet rs)throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        for(String field:List.of("subject_type","subject_id","dedup_key","title","primary_source","confidence","severity","triage_status","signal_count","first_detected_at","last_detected_at","snapshot_status","auto_review_model","auto_reviewed_at","suppressed_reason"))m.put(field,rs.getObject(field));
        String review=rs.getString("auto_review_result");if(review!=null)try{m.put("auto_review_result",json.readValue(review,Map.class));}catch(Exception e){m.put("auto_review_result",Map.of("verdict","UNCERTAIN"));}
        return m;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value==null?List.of():value); }
        catch(Exception e) { throw new IllegalStateException("bad case 快照序列化失败",e); }
    }
    private <T> List<T> decode(String value,TypeReference<List<T>> type) {
        if(value==null || value.isBlank() || "null".equals(value)) return List.of();
        try { return json.readValue(value,type); }
        catch(Exception e) { throw new IllegalStateException("bad case 快照解析失败",e); }
    }
}
