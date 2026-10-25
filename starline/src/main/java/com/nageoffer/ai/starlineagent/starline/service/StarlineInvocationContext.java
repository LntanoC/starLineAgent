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

package com.nageoffer.ai.starlineagent.starline.service;
/** Explicit context avoids leaking thread-local attribution between requests. */
public record StarlineInvocationContext(Origin origin,String runId,String messageId,String conversationId) {
 public enum Origin {USER_FORM,AGENT_TOOL,SYSTEM_JOB}
 public static StarlineInvocationContext userForm(){return new StarlineInvocationContext(Origin.USER_FORM,null,null,null);}
 public boolean systemGenerated(){return origin==Origin.AGENT_TOOL||origin==Origin.SYSTEM_JOB;}
}
