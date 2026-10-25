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

package com.nageoffer.ai.starlineagent.mcp.executor.starline;

import java.time.Instant;
import java.util.List;

/** Search-provider boundary; provider-specific response formats stop here. */
public interface WebSearchClient {

    /** @param publicationFreshness provider-side page publication-time filter, not an event date window */
    List<RawResult> search(String query, String publicationFreshness, int count);

    record RawResult(String title, String url, String excerpt, Instant publishTime, String siteName) {
    }
}
