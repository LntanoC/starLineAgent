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

package com.nageoffer.ai.starlineagent.agent.badcase;
import com.nageoffer.ai.starlineagent.framework.badcase.api.BadCaseDetector.BadCaseScanContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Component @RequiredArgsConstructor
public class AgentBadCaseScanReader {
 private final JdbcTemplate jdbc;private final Map<String,Map<String,Object>> pending=new ConcurrentHashMap<>();
 public List<Map<String,Object>> read(String code,String select,BadCaseScanContext c){
  var cursor=jdbc.queryForList("SELECT * FROM t_bad_case_detector_cursor WHERE detector=?",code);
  Timestamp time=cursor.isEmpty()?Timestamp.from(c.since()):(Timestamp)cursor.get(0).get("cursor_time");
  String id=cursor.isEmpty()?"":(String)cursor.get(0).get("cursor_id");
  var rows=jdbc.queryForList("WITH events AS ("+select+") SELECT * FROM events WHERE scan_time>=? AND scan_time<=? AND (scan_time>? OR (scan_time=? AND scan_id>?)) ORDER BY scan_time,scan_id LIMIT ?",Timestamp.from(c.since()),Timestamp.from(c.until()),time,time,id,c.limit());
  if(!rows.isEmpty())pending.put(code,rows.get(rows.size()-1));else pending.put(code,Map.of());return rows;
 }
 public void checkpoint(String code,BadCaseScanContext c){if(c.dryRun())return;var row=pending.remove(code);if(row==null)return;
  if(row.isEmpty()){if(code.endsWith(":late"))jdbc.update("DELETE FROM t_bad_case_detector_cursor WHERE detector=?",code);return;}
  jdbc.update("INSERT INTO t_bad_case_detector_cursor(detector,cursor_time,cursor_id) VALUES(?,?,?) ON CONFLICT(detector) DO UPDATE SET cursor_time=EXCLUDED.cursor_time,cursor_id=EXCLUDED.cursor_id,update_time=CURRENT_TIMESTAMP WHERE (EXCLUDED.cursor_time,EXCLUDED.cursor_id)>(t_bad_case_detector_cursor.cursor_time,t_bad_case_detector_cursor.cursor_id)",code,row.get("scan_time"),row.get("scan_id"));
 }
}
