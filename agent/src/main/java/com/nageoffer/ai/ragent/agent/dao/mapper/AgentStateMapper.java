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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AgentScope 状态持久化 Mapper
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentStateMapper {

    @Insert("""
            INSERT INTO t_agent_state (user_id, session_id, state_key, payload, create_time, update_time)
            VALUES (#{userId}, #{sessionId}, #{stateKey}, #{payload}::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, session_id, state_key)
            DO UPDATE SET payload = EXCLUDED.payload, update_time = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("userId") String userId,
                @Param("sessionId") String sessionId,
                @Param("stateKey") String stateKey,
                @Param("payload") String payload);

    @Select("""
            SELECT payload
            FROM t_agent_state
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    String selectPayload(@Param("userId") String userId,
                         @Param("sessionId") String sessionId,
                         @Param("stateKey") String stateKey);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM t_agent_state
                WHERE user_id = #{userId} AND session_id = #{sessionId}
            )
            """)
    boolean exists(@Param("userId") String userId, @Param("sessionId") String sessionId);

    @Delete("DELETE FROM t_agent_state WHERE user_id = #{userId} AND session_id = #{sessionId}")
    void deleteBySession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    @Delete("""
            DELETE FROM t_agent_state
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    void deleteByKey(@Param("userId") String userId,
                     @Param("sessionId") String sessionId,
                     @Param("stateKey") String stateKey);

    @Select("""
            SELECT DISTINCT session_id
            FROM t_agent_state
            WHERE user_id = #{userId}
            ORDER BY session_id
            """)
    List<String> selectSessionIds(@Param("userId") String userId);
}
