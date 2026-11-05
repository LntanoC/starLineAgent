// 从工具代码同步初始化说明和增量迁移，避免线上注册表与实际 schema 使用不同工具名。
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
const root = fileURLToPath(new URL("../", import.meta.url));
const base = resolve(root, "resources/initializer/starline-events");
const source = readFileSync(resolve(root, "mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/trip/JourneyMcpExecutor.java"), "utf8");
const hash = (value) => createHash("sha256").update(value).digest("hex");
const literal = (value) => "'" + value.replaceAll("'", "''") + "'";
const descriptions = ["JOURNEY_DESCRIPTION", "EVENT_DESCRIPTION"].map((name) => {
  const match = source.match(new RegExp(`public static final String ${name} = "([^"]+)";`));
  if (!match) throw new Error("Missing description " + name);
  return match[1];
});
const tools = [["plan_journey", "出行规划", descriptions[0]], ["plan_event_journey", "观演出行与花费", descriptions[1]]];
for (const [index, [id, label, description]] of tools.entries()) {
  writeFileSync(resolve(base, `mcp-tools/${id}.properties`), `tool-id=${id}\ndisplay-name=${label}\ndescription=${description}\nrequire-confirm=false\nresident=false\ndispatchable=true\nenabled=true\nsort-order=${110 + index * 10}\nremark=出行规划 v3\n`);
}
const skill = readFileSync(resolve(base, "skills/trip_planning.md"), "utf8");
const skillDescription = readFileSync(resolve(base, "skills/trip_planning.properties"), "utf8").split(/\r?\n/).find((line) => line.startsWith("description=")).slice("description=".length);
const prompt = readFileSync(resolve(base, "prompts/agent-main.txt"), "utf8");
const supplement = prompt.slice(prompt.indexOf("## 出行规划 v3"));
const eventSource = readFileSync(resolve(root, "mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchMcpExecutor.java"), "utf8");
const eventDescription = eventSource.match(/private static final String DESCRIPTION = "([^"]+)";/)[1];
const eventPath = resolve(base, "mcp-tools/search_artist_events.properties");
writeFileSync(eventPath, readFileSync(eventPath, "utf8").replace(/^description=.*$/m, "description=" + eventDescription));
const sql = ["-- v3 增量更新工具与手册，不执行完整初始化，不删除用户会话或知识库。", "BEGIN;",
  "UPDATE t_mcp_tool SET enabled=0, dispatchable=0, update_time=now(), remark='已由 journey v3 替代' WHERE tool_id IN ('plan_trip','plan_event_trip');"];
for (const [index, [id, label, description]] of tools.entries()) sql.push(`INSERT INTO t_mcp_tool(id,tool_id,display_name,description,description_hash,require_confirm,resident,dispatchable,enabled,sort_order,remark)
VALUES ('26110414000000000${index + 1}', ${literal(id)}, ${literal(label)}, ${literal(description)}, '${hash(description)}',0,0,1,1,${110 + index * 10},'出行规划 v3')
ON CONFLICT (tool_id) DO UPDATE SET display_name=EXCLUDED.display_name,description=EXCLUDED.description,description_hash=EXCLUDED.description_hash,require_confirm=0,resident=0,dispatchable=1,enabled=1,sort_order=EXCLUDED.sort_order,remark=EXCLUDED.remark,update_time=now(),deleted=0;`);
sql.push(`UPDATE t_mcp_tool SET description=${literal(eventDescription)},description_hash='${hash(eventDescription)}',update_time=now() WHERE tool_id='search_artist_events';`);
sql.push(`INSERT INTO t_agent_skill(id,skill_code,name,description,content,tool_ids,sort_order,enabled)
VALUES ('261104140000000010','trip_planning','出行与观演规划',${literal(skillDescription)},${literal(skill)},'["plan_journey","plan_event_journey"]'::jsonb,120,1)
ON CONFLICT (skill_code) WHERE deleted=0 DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,content=EXCLUDED.content,tool_ids=EXCLUDED.tool_ids,enabled=1,update_time=now();`);
sql.push(`UPDATE t_agent_prompt ap SET content=replace(replace(ap.content,'plan_event_trip','plan_event_journey'),'plan_trip','plan_journey')
 || CASE WHEN position('## 出行规划 v3' in ap.content)=0 THEN E'\\n\\n' || ${literal(supplement)} ELSE '' END, update_time=now()
FROM t_agent_profile p WHERE ap.agent_id=p.id AND p.builtin=1 AND p.deleted=0 AND ap.slot_key='AGENT_MAIN' AND ap.deleted=0;`);
sql.push("DELETE FROM t_mcp_tool_vector v USING t_mcp_tool t WHERE v.tool_id=t.tool_id AND t.tool_id IN ('plan_journey','plan_event_journey','search_artist_events') AND v.description_hash IS DISTINCT FROM t.description_hash;");
sql.push(`DO $check$ BEGIN
IF (SELECT count(*) FROM t_mcp_tool WHERE tool_id IN ('plan_journey','plan_event_journey') AND enabled=1 AND dispatchable=1 AND deleted=0)<>2 THEN RAISE EXCEPTION 'journey registry mismatch'; END IF;
IF NOT EXISTS(SELECT 1 FROM t_agent_skill WHERE skill_code='trip_planning' AND tool_ids='["plan_journey","plan_event_journey"]'::jsonb AND enabled=1 AND deleted=0) THEN RAISE EXCEPTION 'journey skill mismatch'; END IF;
END; $check$;`, "COMMIT;", "");
writeFileSync(resolve(root, "resources/database/upgrades/v1.1.0/261104_journey_v3.sql"), sql.join("\n"));
function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => entry.isDirectory() ? walk(resolve(directory, entry.name)) : [resolve(directory, entry.name)]);
}
const files = walk(base).filter((file) => !file.endsWith(".java") && !file.endsWith("checksums.sha256"))
  .map((file) => relative(base, file).replaceAll("\\", "/")).sort();
writeFileSync(resolve(base, "checksums.sha256"), files.map((file) => hash(readFileSync(resolve(base, file))) + "  " + file).join("\n") + "\n");
console.log(`Synced ${tools.length} journey tools, migration and ${files.length} template checksums`);
