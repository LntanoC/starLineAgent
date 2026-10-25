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

package com.nageoffer.ai.starlineagent.rag.dao.mapper;

import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolVectorDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface McpToolVectorMapper {

    @Select("SELECT tool_id AS toolId, model_id AS modelId, dimension, embedding, "
            + "description_hash AS descriptionHash, update_time AS updateTime FROM t_mcp_tool_vector")
    List<McpToolVectorDO> selectAll();

    @Insert("INSERT INTO t_mcp_tool_vector(tool_id, model_id, dimension, embedding, description_hash, update_time) "
            + "VALUES(#{toolId}, #{modelId}, #{dimension}, #{embedding,jdbcType=BINARY}, #{descriptionHash}, CURRENT_TIMESTAMP) "
            + "ON CONFLICT(tool_id) DO UPDATE SET model_id=EXCLUDED.model_id, dimension=EXCLUDED.dimension, "
            + "embedding=EXCLUDED.embedding, description_hash=EXCLUDED.description_hash, update_time=CURRENT_TIMESTAMP")
    int upsert(@Param("toolId") String toolId,
               @Param("modelId") String modelId,
               @Param("dimension") int dimension,
               @Param("embedding") byte[] embedding,
               @Param("descriptionHash") String descriptionHash);

    @Delete("DELETE FROM t_mcp_tool_vector WHERE tool_id = #{toolId}")
    int deleteByToolId(@Param("toolId") String toolId);
}
