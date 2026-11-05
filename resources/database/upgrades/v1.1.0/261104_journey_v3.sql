-- v3 增量更新工具与手册，不执行完整初始化，不删除用户会话或知识库。
BEGIN;
UPDATE t_mcp_tool SET enabled=0, dispatchable=0, update_time=now(), remark='已由 journey v3 替代' WHERE tool_id IN ('plan_trip','plan_event_trip');
INSERT INTO t_mcp_tool(id,tool_id,display_name,description,description_hash,require_confirm,resident,dispatchable,enabled,sort_order,remark)
VALUES ('261104140000000001', 'plan_journey', '出行规划', '规划城市或地址之间的普通出行，返回 trip-plan.v3 分段时间轴、交通方式、费用明细、人均/同行合计、可行性与来源。必须提供 departAt 或 arriveBy 二选一；城市定位点会明确标注。支持铁路/市内公共交通、自驾、打车和短途步行；航班与大巴专用时刻尚未接入。未知金额为 null，不能说成零；参考价不是实价。FAILED 才表示无可用结果；NEEDS_CLARIFICATION 请按 questions 追问，PARTIAL 仍应展示可用方案。观演场景调用 plan_event_journey，不订票、不承诺余票。', 'c0c061a42e699e5bdbbb22bfac026e6c8d9eb5a07d64a9a4e38ce4c1b5afe976',0,0,1,1,110,'出行规划 v3')
ON CONFLICT (tool_id) DO UPDATE SET display_name=EXCLUDED.display_name,description=EXCLUDED.description,description_hash=EXCLUDED.description_hash,require_confirm=0,resident=0,dispatchable=1,enabled=1,sort_order=EXCLUDED.sort_order,remark=EXCLUDED.remark,update_time=now(),deleted=0;
INSERT INTO t_mcp_tool(id,tool_id,display_name,description,description_hash,require_confirm,resident,dispatchable,enabled,sort_order,remark)
VALUES ('261104140000000002', 'plan_event_journey', '观演出行与花费', '为艺人+城市的观演出行一次查询事件并规划交通、时间轴、往返和费用，返回 trip-plan.v3。上海站在 city 槽位表示上海场，火车站放 origin。origin 可从已知用户信息/记忆补充，不知道则省略并按 questions 追问，最多两问；多场先选日期。场馆、开场和票价只从官方来源或用户提供信息获得，演出日期与开票时刻不能当开场。跨城默认含返程，散场或末班车未知会明确标记。只解释工具结果，不自行算钱或倒推时间；INFEASIBLE 必须明说赶不上，UNKNOWN 不保证来得及。', '63ff80504b92a966f66aae96c9a69b99e6e5a1853a0abfaef0bd2e076598e9ca',0,0,1,1,120,'出行规划 v3')
ON CONFLICT (tool_id) DO UPDATE SET display_name=EXCLUDED.display_name,description=EXCLUDED.description,description_hash=EXCLUDED.description_hash,require_confirm=0,resident=0,dispatchable=1,enabled=1,sort_order=EXCLUDED.sort_order,remark=EXCLUDED.remark,update_time=now(),deleted=0;
UPDATE t_mcp_tool SET description='查询中国大陆艺人近期的演出安排，数据只来自管理员启用的白名单：文旅公示（GOV，仅证明已获批复）和票务平台（TICKETING，提供上架、开票、售罄状态）。适用于近期演出、巡演、场次、城市和开票查询；艺人生日、作品、八卦等问题不要调用。stage=APPROVED表示已获批，不等于确定举办或可购票；ON_SHELF=已上架待开票，ON_SALE=已开票，SOLD_OUT=已售罄，ENDED=已结束。daysToSale为正表示距离开票的天数，0表示今天，负数表示已过开票日，null表示没有准确开票时间；只有canBuyTicket=true才能说现在可以买。total=0只表示本次白名单搜索未查到，不代表没有演出；不得编造场次、日期或时间。涉及最近、今年、下个月等相对时间先调用current_date。演出信息由前端渲染为卡片，只给一句总述，不重复列出卡片字段，也不要在文字中创建或声称已创建提醒。',description_hash='191cc9d547703e621d56526f948fb2afac4f931dcdeb629800a815d7de556ad2',update_time=now() WHERE tool_id='search_artist_events';
INSERT INTO t_agent_skill(id,skill_code,name,description,content,tool_ids,sort_order,enabled)
VALUES ('261104140000000010','trip_planning','出行与观演规划','普通问路调用 plan_journey，观演出行调用 plan_event_journey，一次获取事件锚点、交通组合、分段时间轴、费用明细、人均与同行合计、可行性、返程和待确认项。需要追问时仅按 questions 提问；未知票价和时刻不补零、不猜测。纯演出信息仍用 search_artist_events；不订票、不做多日旅游。','# 出行与观演规划 v3

1. 普通问路使用 `plan_journey`，不先查询演出；`origin`、`destination` 和 `departAt`/`arriveBy` 二选一。用户未给时刻时只追问具体出发或最晚到达时间，不替用户填当天零点。
2. 去看某艺人某城市演出使用 `plan_event_journey`，传 artist/city，一次由工具核实事件并规划；不用模型串多个路线工具。“薛之谦上海站”的 city 是上海场；普通“从上海站出发”的 origin 保持上海站，不改写成城市中心。
3. 出发地优先使用本次对话、用户画像或长期记忆中用户已确认的信息并传 origin；未确认则省略。不能从 IP、工具历史或推测补地址。
4. `NEEDS_CLARIFICATION` 只按 questions 追问，最多两问；多个场次先选择日期/场馆。能查到的信息不反复问。场馆和准确开场缺失时不得拿日期或开票时间代替，也不能用 POI 搜索猜举办场馆。
5. `partySize` 默认1；`budgetMinor` 是同行合计预算，`ticketPaidMinor` 是每人门票实付，都用人民币分。未知道价格不要填0；跨城观演默认查询返程，同城默认单程；用户要求当天回时显式 roundTrip=true。
6. 所有时间、合计、分摊、比较和可行性使用工具返回的数值。按“结论、方案对比、时间轴、花费、风险与待确认”五部分简洁解释；前端卡片展示明细，文字不要再展开重复整张表。
7. 有 unknownCategories 时只说“已知部分”，并列出待核实项。QUOTED 说用户提供/已报价，REFERENCE 说参考价，ESTIMATED 说估算；不能把参考和估算改称实付，也不能把往返价格自行复制。
8. INFEASIBLE 必须说“赶不上/来不及”，不推荐；TIGHT 说偏紧，UNKNOWN 说无法确认来得及。无 recommendedOptionId 时不能自行保证某方案全程可行。返程 NOT_REQUESTED/UNKNOWN 和末班车缺口必须明确说出来。
9. PARTIAL 仍展示可用方案并解释 warnings；FAILED 才说明本次没有可用结果。不能把 provider 故障当业务假设。费用不完整时，不能确认预算够用或硬比较最省。
10. 不编造车次、航班、票价、场馆、开场或散场。铁路时刻/参考价依赖高德覆盖，不代表余票；航班与大巴专用时刻未接入。赶不上返程时可建议另查打车、次日返程或住宿，住宿/餐饮不进入当前合计；不订票、不锁座。
','["plan_journey","plan_event_journey"]'::jsonb,120,1)
ON CONFLICT (skill_code) WHERE deleted=0 DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,content=EXCLUDED.content,tool_ids=EXCLUDED.tool_ids,enabled=1,update_time=now();
UPDATE t_agent_prompt ap SET content=replace(replace(ap.content,'plan_event_trip','plan_event_journey'),'plan_trip','plan_journey')
 || CASE WHEN position('## 出行规划 v3' in ap.content)=0 THEN E'\n\n' || '## 出行规划 v3
- 观演交通直接调用 plan_event_journey，事件核实已在复合工具内部完成，不额外先调用 search_artist_events。
- “艺人+上海站”是上海场；“从上海站出发”才是铁路地点。出发地先使用用户已确认的对话/画像/记忆，未知则按 questions 追问，最多两问。
- 所有金额、分摊、倒推与比较采用 trip-plan.v3 返回值。未知费用写“待核实”，有费用缺口只能说“已知部分”，不能保证预算足够。
- 不把演出日期、开票时刻当开场；不猜场馆、散场、车次或票价。INFEASIBLE 明说赶不上，不推荐；UNKNOWN 不保证来得及。返程未计算、散场或末班车未知必须明确说明。
- 用“结论、方案对比、时间轴、花费、风险与待确认”简洁解释卡片。参考、估算和实付须区分；不自行复制去程价格算返程，不承诺余票或订票。
' ELSE '' END, update_time=now()
FROM t_agent_profile p WHERE ap.agent_id=p.id AND p.builtin=1 AND p.deleted=0 AND ap.slot_key='AGENT_MAIN' AND ap.deleted=0;
DELETE FROM t_mcp_tool_vector v USING t_mcp_tool t WHERE v.tool_id=t.tool_id AND t.tool_id IN ('plan_journey','plan_event_journey','search_artist_events') AND v.description_hash IS DISTINCT FROM t.description_hash;
DO $check$ BEGIN
IF (SELECT count(*) FROM t_mcp_tool WHERE tool_id IN ('plan_journey','plan_event_journey') AND enabled=1 AND dispatchable=1 AND deleted=0)<>2 THEN RAISE EXCEPTION 'journey registry mismatch'; END IF;
IF NOT EXISTS(SELECT 1 FROM t_agent_skill WHERE skill_code='trip_planning' AND tool_ids='["plan_journey","plan_event_journey"]'::jsonb AND enabled=1 AND deleted=0) THEN RAISE EXCEPTION 'journey skill mismatch'; END IF;
END; $check$;
COMMIT;
