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
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseSignal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Component;
@Component
public class BadCaseDedupPolicy {
 public String key(BadCaseSignal s) {
  if(s.messageId()!=null&&!s.messageId().isBlank())return "MSG:"+s.messageId();
  if(!Set.of("AGENT_MESSAGE","RAG_MESSAGE","AGENT_RUN","CONVERSATION_EPISODE","STARLINE_REMINDER","STARLINE_RULE","EVAL_TURN").contains(s.subjectType()))throw new IllegalArgumentException("Unknown candidate subject");
  return switch(s.subjectType()) {
   case "AGENT_RUN"->"RUN:"+s.subjectId();
   case "STARLINE_REMINDER"->"STARLINE_REMINDER:"+s.subjectId();
   case "STARLINE_RULE"->"STARLINE_RULE:"+s.subjectId();
   case "EVAL_TURN"->"EVAL:"+s.subjectId();
   case "CONVERSATION_EPISODE"->"CONV:"+s.conversationId()+":CLARIFY:"+s.subjectId();
   default->"MSG:"+s.subjectId();
  };
 }
 public static String hash(String value) {
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 public static boolean shouldReopen(String status,boolean archived,java.time.Instant completed,java.time.Instant occurrence,String confidence) {
  return "FIXED".equals(status)&&"HIGH".equals(confidence)&&completed!=null&&occurrence.isAfter(completed);
 }
}
