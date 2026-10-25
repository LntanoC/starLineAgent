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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="STARLINE_BADCASE_TEST_URL",matches=".+")
class AgentRuntimeBadCaseIntegrationTest {
 private JdbcTemplate admin,jdbc;private String schema;private AgentRuntimeBadCaseDetector detector;private Instant ended;
 private static Path projectRoot(){Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.isRegularFile(p.resolve("resources/database/schema_pg.sql")))p=p.getParent();if(p==null)throw new IllegalStateException("Project root not found");return p;}
 @BeforeEach void setup()throws Exception{String url=System.getenv("STARLINE_BADCASE_TEST_URL"),user=System.getenv("STARLINE_BADCASE_TEST_USER"),password=System.getenv("STARLINE_BADCASE_TEST_PASSWORD");admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="bc_runtime_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,user,password));String ddl=Files.readString(projectRoot().resolve("resources/database/schema_pg.sql"));for(String table:List.of("t_agent_run","t_agent_run_node","t_bad_case_detector_cursor")){var m=Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?"+table+" \\([\\s\\S]*?\\n\\);",Pattern.CASE_INSENSITIVE).matcher(ddl);assertThat(m.find()).as(table).isTrue();jdbc.execute(m.group());}detector=new AgentRuntimeBadCaseDetector(new AgentBadCaseScanReader(jdbc));ended=Instant.now().minusSeconds(100);jdbc.update("INSERT INTO t_agent_run(id,run_id,conversation_id,user_id,assistant_message_id,run_status) VALUES('r1','run1','c','u','m','NORMAL')");}
 @AfterEach void cleanup(){if(admin!=null&&schema!=null&&schema.matches("bc_runtime_it_[a-f0-9]{32}"))admin.execute("DROP SCHEMA "+schema+" CASCADE");}
 private void node(String id,String status){jdbc.update("INSERT INTO t_agent_run_node(id,run_id,node_id,node_type,node_name,status,started_at,ended_at) VALUES(?,'run1',?,'TOOL','search_knowledge',?,?,?)",id,id,status,Timestamp.from(ended.minusSeconds(1)),Timestamp.from(ended));}
 private BadCaseScanContext context(){return new BadCaseScanContext(ended.minusSeconds(60),Instant.now().minusSeconds(15),2,false);}
 @Test void compositeCursorAdvancesPastFullTimestampPagesAndLateSweepFindsLateWrites(){for(int i=1;i<=5;i++)node("n"+i,"FAILED");Set<String>seen=new HashSet<>();for(int i=0;i<4;i++){var c=context();detector.scan(c).signals().forEach(s->seen.add(s.occurrenceKey()));detector.checkpoint(c);}assertThat(seen).hasSize(5);assertThat(jdbc.queryForObject("SELECT cursor_id FROM t_bad_case_detector_cursor WHERE detector='agent-runtime'",String.class)).isEqualTo("n5");node("n0","FAILED");for(int i=0;i<4;i++){var c=context();detector.scan(c).signals().forEach(s->seen.add(s.occurrenceKey()));detector.checkpoint(c);}assertThat(seen).contains("tool:run1:n0");}
 @Test void deniedAndUserInterruptedAreNotFailures(){node("denied","DENIED");node("cancel","INTERRUPTED");jdbc.update("UPDATE t_agent_run SET run_status='INTERRUPTED'");assertThat(detector.scan(context()).signals()).isEmpty();}
 @Test void laterToolSuccessLowersFailureSeverity(){node("failed","FAILED");jdbc.update("INSERT INTO t_agent_run_node(id,run_id,node_id,node_type,node_name,status,started_at,ended_at) VALUES('ok','run1','ok','TOOL','search_knowledge','SUCCESS',?,?)",Timestamp.from(ended.plusSeconds(2)),Timestamp.from(ended.plusSeconds(3)));assertThat(detector.scan(context()).signals()).allMatch(s->s.ruleCode().equals("TOOL_TRANSIENT_FAILED")&&s.severity().equals("S4"));}
}
