-- 演出查询覆盖v1：仅更新工具说明及发现路由规则，不删除业务数据。
BEGIN;
UPDATE t_mcp_tool SET description='发现艺人的演出/巡演安排，city可省略；未指定城市时不补填城市，不调用plan_event_journey。仅从数据库启用的公开白名单检索，默认window=year（未来365天），recent=未来90天，all=未来730天；includeEnded默认false。多查询、多城市、一页多场次；pageSize默认20上限50，nextCursor可加载更多。coverage和notes说明窗口、查询/来源数、过滤、截断及提供方限制，只能说本次检索到的，不得称全部或保证覆盖。dateUnknown表示日期待核实，不能用候选年份当确定演出日；未知场馆/开场不能补造。GOV/APPROVED仅证明许可；TICKETING提供ON_SHELF待开票、ON_SALE已开票、SOLD_OUT售罄、ENDED已结束状态，只有canBuyTicket=true才能说现在可以买；isStale/conflict时必须核验，不自动创建提醒。total=0不表示没有演出。相对时间先用current_date。前端渲染卡片，只给一句总述和覆盖限制，不重复卡片，也不声称已创建提醒。艺人生日/作品/八卦问题不要调用。',description_hash='30c826a7ef35d2789189aae75e9f20ef37cf462ba55cd65f29f4a880ccc7de46',update_time=now() WHERE tool_id='search_artist_events';
DELETE FROM t_mcp_tool_vector v USING t_mcp_tool t WHERE v.tool_id=t.tool_id AND t.tool_id='search_artist_events' AND v.description_hash IS DISTINCT FROM t.description_hash;
UPDATE t_agent_skill SET content=regexp_replace(content, E'\n## 演出发现覆盖 v1[\s\S]*$', '') || E'\n\n' || '## 演出发现覆盖 v1
- “有哪些演出/最近有什么演出/巡演有哪些城市”只调用 search_artist_events；用户未指定城市时省略city，不调用观演规划工具追问城市。
- window默认year未来365天；recent未来90天，all未来730天；默认不含已结束。回答须说明本次检索窗口，只能说“本次检索到的”，不能保证覆盖。
- 解释coverage/notes中的来源失败、提供方单页限制、截断和待核实日期。还有未展示时告知可加载更多；来源没有命中不表示该平台没有场次。
- 日期无年份/无法解析时保持dateUnknown，不以候选年份或记忆补造日期、场馆和时刻；未知或冲突不能自动创建提醒。交通规划仍只在用户明确提出出行且有目标场次/城市时调用plan_event_journey。
',update_time=now() WHERE skill_code='trip_planning' AND deleted=0;
UPDATE t_agent_prompt ap SET content=regexp_replace(ap.content, E'\n## 演出发现覆盖 v1[\s\S]*$', '') || E'\n\n' || '## 演出发现覆盖 v1
- “有哪些演出/最近有什么演出/巡演有哪些城市”只调用 search_artist_events；用户未指定城市时省略city，不调用观演规划工具追问城市。
- window默认year未来365天；recent未来90天，all未来730天；默认不含已结束。回答须说明本次检索窗口，只能说“本次检索到的”，不能保证覆盖。
- 解释coverage/notes中的来源失败、提供方单页限制、截断和待核实日期。还有未展示时告知可加载更多；来源没有命中不表示该平台没有场次。
- 日期无年份/无法解析时保持dateUnknown，不以候选年份或记忆补造日期、场馆和时刻；未知或冲突不能自动创建提醒。交通规划仍只在用户明确提出出行且有目标场次/城市时调用plan_event_journey。
',update_time=now() FROM t_agent_profile p WHERE ap.agent_id=p.id AND p.builtin=1 AND p.deleted=0 AND ap.slot_key='AGENT_MAIN' AND ap.deleted=0;
COMMIT;
