import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
const root = fileURLToPath(new URL("../", import.meta.url));
const base = resolve(root, "resources/initializer/starline-events");
const hash = text => createHash("sha256").update(text).digest("hex");
const literal = text => "'" + text.replaceAll("'", "''") + "'";
const code = readFileSync(resolve(root, "mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchMcpExecutor.java"), "utf8");
const description = code.match(/private static final String DESCRIPTION = "([^"]+)";/)[1];
const props = resolve(base, "mcp-tools/search_artist_events.properties");
writeFileSync(props, readFileSync(props, "utf8").replace(/^description=.*$/m, "description=" + description));
const rules = `## 演出发现覆盖 v1
- “有哪些演出/最近有什么演出/巡演有哪些城市”只调用 search_artist_events；用户未指定城市时省略city，不调用观演规划工具追问城市。
- window默认year未来365天；recent未来90天，all未来730天；默认不含已结束。回答须说明本次检索窗口，只能说“本次检索到的”，不能保证覆盖。
- 解释coverage/notes中的来源失败、提供方单页限制、截断和待核实日期。还有未展示时告知可加载更多；来源没有命中不表示该平台没有场次。
- 日期无年份/无法解析时保持dateUnknown，不以候选年份或记忆补造日期、场馆和时刻；未知或冲突不能自动创建提醒。交通规划仍只在用户明确提出出行且有目标场次/城市时调用plan_event_journey。
`;
const promptPath = resolve(base, "prompts/agent-main.txt");
let prompt = readFileSync(promptPath, "utf8");
prompt = prompt.replace(/\n## 演出发现覆盖 v1[\s\S]*$/, "").trimEnd() + "\n\n" + rules;
writeFileSync(promptPath, prompt);
const skillPath = resolve(base, "skills/trip_planning.md");
let skill = readFileSync(skillPath, "utf8");
skill = skill.replace(/\n## 演出发现覆盖 v1[\s\S]*$/, "").trimEnd() + "\n\n" + rules;
writeFileSync(skillPath, skill);
const sql = ["-- 演出查询覆盖v1：仅更新工具说明及发现路由规则，不删除业务数据。", "BEGIN;",
  `UPDATE t_mcp_tool SET description=${literal(description)},description_hash='${hash(description)}',update_time=now() WHERE tool_id='search_artist_events';`,
  `DELETE FROM t_mcp_tool_vector v USING t_mcp_tool t WHERE v.tool_id=t.tool_id AND t.tool_id='search_artist_events' AND v.description_hash IS DISTINCT FROM t.description_hash;`,
  `UPDATE t_agent_skill SET content=regexp_replace(content, E'\\n## 演出发现覆盖 v1[\\s\\S]*$', '') || E'\\n\\n' || ${literal(rules)},update_time=now() WHERE skill_code='trip_planning' AND deleted=0;`,
  `UPDATE t_agent_prompt ap SET content=regexp_replace(ap.content, E'\\n## 演出发现覆盖 v1[\\s\\S]*$', '') || E'\\n\\n' || ${literal(rules)},update_time=now() FROM t_agent_profile p WHERE ap.agent_id=p.id AND p.builtin=1 AND p.deleted=0 AND ap.slot_key='AGENT_MAIN' AND ap.deleted=0;`,
  "COMMIT;", ""];
writeFileSync(resolve(root, "resources/database/upgrades/v1.1.0/261104_event_search_v1.sql"), sql.join("\n"));
function walk(dir) { return readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(resolve(dir, entry.name)) : [resolve(dir, entry.name)]); }
const paths = walk(base).filter(p => !p.endsWith(".java") && !p.endsWith("checksums.sha256")).map(p => relative(base, p).replaceAll("\\", "/")).sort();
writeFileSync(resolve(base, "checksums.sha256"), paths.map(p => hash(readFileSync(resolve(base, p))) + "  " + p).join("\n") + "\n");
console.log("Event search description, migration and template checksums synchronized");
