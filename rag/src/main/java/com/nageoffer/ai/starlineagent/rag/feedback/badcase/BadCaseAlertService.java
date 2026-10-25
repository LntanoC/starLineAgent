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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Slf4j @Component @RequiredArgsConstructor
public class BadCaseAlertService {
 private final JdbcTemplate jdbc;private final BadCaseProperties p;
 @Scheduled(fixedDelay=900000,initialDelay=240000)
 public void aggregate(){if(!p.isEnabled()||!p.getAlerts().isEnabled())return;
  try{for(var row:jdbc.queryForList("SELECT rule_code,coalesce(evidence->>'errorCode',evidence->>'last_error_code','UNKNOWN') AS error_code,count(*) AS failures FROM t_bad_case_signal WHERE invalidated_at IS NULL AND severity IN ('S1','S2') AND create_time>CURRENT_TIMESTAMP-(?*INTERVAL '1 minute') GROUP BY rule_code,error_code HAVING count(*)>=?",Math.max(1,p.getAlerts().getWindowMinutes()),Math.max(1,p.getAlerts().getS2Threshold())))log.warn("BADCASE_AGGREGATE_ALERT rule={} error={} count={}",row.get("rule_code"),row.get("error_code"),row.get("failures"));}
  catch(Exception e){log.warn("Bad case aggregate alert unavailable type={}",e.getClass().getSimpleName());}
 }
}
