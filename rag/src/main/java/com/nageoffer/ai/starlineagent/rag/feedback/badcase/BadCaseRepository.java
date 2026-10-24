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
                  AND NOT EXISTS (SELECT 1 FROM t_bad_case b WHERE b.message_id=f.message_id AND b.user_id=f.user_id)
                ORDER BY f.update_time,f.id LIMIT ?
                """, (rs, n) -> new Feedback(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getString(6)),
                since == null ? null : new Timestamp(since.getTime()), since, limit);
    }

    public boolean insert(BadCaseReport r) {
        return jdbc.update("""
                INSERT INTO t_bad_case
                (id,message_id,conversation_id,user_id,message_type,run_id,trace_matched_by_fallback,trace_match_status,
                 vote,reason,comment,user_question,assistant_answer,thinking_content,message_status,
                 sources,retrieved_chunks,trace_nodes,suspicions,evidence_notes,message_create_time)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?
                WHERE EXISTS (SELECT 1 FROM t_message_feedback f WHERE f.message_id=? AND f.user_id=? AND f.vote=-1 AND f.deleted=0)
                ON CONFLICT (message_id,user_id) DO NOTHING
                """, IdWorker.getIdStr(),r.getMessageId(),r.getConversationId(),r.getUserId(),r.getMessageType(),
                r.getRunId(),r.isTraceMatchedByFallback()?1:0,r.getTraceMatchStatus(),r.getVote(),r.getReason(),r.getComment(),
                r.getUserQuestion(),r.getAssistantAnswer(),r.getThinkingContent(),r.getMessageStatus(),
                encode(r.getSources()),encode(r.getRetrievedChunks()),encode(r.getTraceNodes()),
                encode(r.getSuspicions()),encode(r.getEvidenceNotes()),r.getMessageCreateTime(),r.getMessageId(),r.getUserId()) > 0;
    }

    public Page page(String status, String cause, int current, int size) {
        String where = " WHERE deleted=0";
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where += " AND review_status=?"; args.add(status); }
        if (cause != null && !cause.isBlank()) { where += " AND root_cause=?"; args.add(cause); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM t_bad_case"+where,Long.class,args.toArray());
        // 列表不读取大段 reasoning、来源及节点快照，打开详情时再取。
        String columns = "id,message_id,conversation_id,user_id,message_type,run_id,trace_matched_by_fallback,trace_match_status,"
                + "vote,reason,comment,left(user_question,300) user_question,left(assistant_answer,300) assistant_answer,"
                + "NULL::text thinking_content,message_status,NULL::jsonb sources,NULL::jsonb retrieved_chunks,"
                + "NULL::jsonb trace_nodes,suspicions,evidence_notes,message_create_time,root_cause,root_cause_detail,"
                + "fix_action,fix_ref,fix_note,review_status,reviewer,review_time,revision,create_time,update_time";
        args.add(size); args.add((current-1)*size);
        List<BadCaseRecord> records = jdbc.query("SELECT "+columns+" FROM t_bad_case"+where
                +" ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
        long count=total == null ? 0 : total;
        return new Page(records,count,current,size,(count+size-1)/size);
    }

    public Optional<BadCaseRecord> find(String id) {
        return jdbc.query("SELECT * FROM t_bad_case WHERE id=? AND deleted=0",this::map,id).stream().findFirst();
    }

    public int review(String id, BadCaseService.ReviewRequest r, String reviewer) {
        return jdbc.update("""
                UPDATE t_bad_case SET root_cause=?,root_cause_detail=?,fix_action=?,fix_ref=?,fix_note=?,
                 review_status=?,reviewer=?,review_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP,revision=revision+1
                WHERE id=? AND revision=? AND deleted=0
                """,r.rootCause(),r.rootCauseDetail(),r.fixAction(),r.fixRef(),r.fixNote(),r.reviewStatus(),reviewer,id,r.revision());
    }

    public Map<String,Object> stats() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total",jdbc.queryForObject("SELECT count(*) FROM t_bad_case WHERE deleted=0",Long.class));
        result.put("byStatus",jdbc.queryForList("SELECT review_status AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY review_status ORDER BY review_status"));
        result.put("byRootCause",jdbc.queryForList("SELECT coalesce(root_cause,'UNREVIEWED') AS value,count(*) AS count FROM t_bad_case WHERE deleted=0 GROUP BY root_cause ORDER BY count(*) DESC"));
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
                 update_time=CURRENT_TIMESTAMP
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
                .messageCreateTime(rs.getTimestamp("message_create_time")).build();
        return new BadCaseRecord(rs.getString("id"),r,rs.getString("root_cause"),rs.getString("root_cause_detail"),
                rs.getString("fix_action"),rs.getString("fix_ref"),rs.getString("fix_note"),rs.getString("review_status"),
                rs.getString("reviewer"),rs.getTimestamp("review_time"),rs.getLong("revision"),
                rs.getTimestamp("create_time"),rs.getTimestamp("update_time"));
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
