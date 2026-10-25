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

package com.nageoffer.ai.starlineagent.rag.service;

import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolUpdateRequest;
import com.nageoffer.ai.starlineagent.rag.controller.request.McpToolConfigureRequest;
import com.nageoffer.ai.starlineagent.rag.dao.entity.McpToolDO;

import java.util.List;
import java.util.Map;

public interface McpToolRegistryService {
    List<McpToolDO> listAll();

    List<McpToolDO> listEnabled();

    List<McpToolDO> listDispatchable();

    Map<String, McpToolDO> enabledById();

    void update(String toolId, McpToolUpdateRequest request);

    void configure(McpToolConfigureRequest request);

    int registerDiscovered(String toolId, String displayName, String description);
}
