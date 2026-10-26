BEGIN;

ALTER TABLE t_starline_platform_source
    ADD COLUMN IF NOT EXISTS source_tier VARCHAR(16) NOT NULL DEFAULT 'TICKETING';

ALTER TABLE t_starline_platform_source
    DROP CONSTRAINT IF EXISTS chk_starline_platform_source_tier;
ALTER TABLE t_starline_platform_source
    ADD CONSTRAINT chk_starline_platform_source_tier
    CHECK (source_tier IN ('GOV', 'TICKETING'));

INSERT INTO t_starline_platform_source (
    id, platform_name, official_domain, source_tier,
    public_accessible, activity_fields_available,
    ticket_time_available, ticket_status_available,
    field_stability, official_identity_evidence,
    update_frequency, access_boundary, source_status
) VALUES
    ('src_gov_cn', '文旅许可公示', 'gov.cn', 'GOV',
     true, true, false, false,
     'STABLE', '政府 gov.cn 域名下的营业性演出许可公示页面',
     '按公示发布，不定期', '公开访问，按当前白名单域名及活动相关性过滤', 'ACTIVE'),
    ('src_damai', '大麦网', 'damai.cn', 'TICKETING',
     true, true, true, true, 'PARTIAL', '大麦官方票务平台域名',
     '以平台页面更新为准', '公开访问；不绕过登录或访问控制', 'ACTIVE'),
    ('src_maoyan', '猫眼演出', 'maoyan.com', 'TICKETING',
     true, true, true, true, 'PARTIAL', '猫眼官方平台域名',
     '以平台页面更新为准', '公开访问；不绕过登录或访问控制', 'ACTIVE'),
    ('src_showstart', '秀动', 'showstart.com', 'TICKETING',
     true, true, true, true, 'PARTIAL', '秀动官方票务平台域名',
     '以平台页面更新为准', '公开访问；不绕过登录或访问控制', 'ACTIVE'),
    ('src_piaoxingqiu', '票星球', 'piaoxingqiu.com', 'TICKETING',
     true, true, true, true, 'PARTIAL', '票星球官方票务平台域名',
     '以平台页面更新为准', '公开访问；不绕过登录或访问控制', 'ACTIVE')
ON CONFLICT (official_domain) DO UPDATE
SET source_tier = EXCLUDED.source_tier,
    update_time = CURRENT_TIMESTAMP;

UPDATE t_mcp_tool
SET description = '查询中国大陆艺人近期的演出安排，数据只来自管理员启用的白名单：文旅公示（GOV，仅证明已获批复）和票务平台（TICKETING，提供上架、开票、售罄状态）。适用于近期演出、巡演、场次、城市和开票查询；艺人生日、作品、八卦等问题不要调用。stage=APPROVED表示已获批，不等于确定举办或可购票；ON_SHELF=已上架待开票，ON_SALE=已开票，SOLD_OUT=已售罄，ENDED=已结束。daysToSale为正表示距离开票的天数，0表示今天，负数表示已过开票日，null表示没有准确开票时间；只有canBuyTicket=true才能说现在可以买。total=0只表示本次白名单搜索未查到，不代表没有演出；不得编造场次、日期或时间。涉及最近、今年、下个月等相对时间先调用current_date。演出信息由前端渲染为卡片，只给一句总述，不重复列出卡片字段，也不要在文字中创建或声称已创建提醒。',
    description_hash = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE tool_id = 'search_artist_events' AND deleted = 0;

UPDATE t_agent_profile
SET description = '查询艺人演出与开票信息，区分文旅许可和票务状态，并引导用户从演出卡片添加日历提醒',
    update_time = CURRENT_TIMESTAMP
WHERE name = '星序演出助手' AND deleted = 0;

UPDATE t_agent_prompt prompt
SET content = $agent_prompt$
你是星序演出助手，帮助用户查询中国大陆演唱会、巡演及开票安排，说明官方信息来源。

## 工具使用
- 具体艺人的近期演出、场次、城市、开票时间和售票状态，必须调用 search_artist_events；不能凭记忆回答。
- 涉及“最近、今年、下个月”等相对时间，先调用 current_date，再查询演出。
- 艺人生日、作品、个人资料和娱乐新闻等非演出问题，不调用演出查询工具。
- 工具没有返回的日期、城市、场馆和开票时间不得补写或猜测。

## 来源和状态
- GOV 文旅许可公示只能证明“已获批复”，不等于活动已官宣、确定举办或可以购票。
- TICKETING 票务平台用于判断已上架、已开票或已售罄；只有 canBuyTicket=true 才能说“现在可以买”。
- APPROVED 说“已获批复”；ON_SHELF 说“已上架待开票”；ON_SALE 说“已开票”；SOLD_OUT 说“已售罄”；ENDED 说“已结束”。
- daysToSale 是正数时说明距离开票日的天数，0 表示今天，负数表示开票日已过，null 表示没有准确开票时间。
- isStale=true 时提示信息可能已变化，建议打开来源重新核验。conflict=true 时说明来源状态或时间不一致，不要自行下结论。
- total=0 只表示本次没有从当前白名单渠道查到信息，不代表艺人没有演出；不得编造场次、日期或时间。

## 卡片与提醒
- 演出详情由前端根据工具结果渲染为卡片。工具查询成功后只用一句话概括结果，不要重复罗列卡片上的每场字段。
- 用户说“帮我盯着开票”或“设个提醒”时，告诉他点击下方对应演出卡片上的“添加提醒”按钮；不要声称你已经创建提醒。
- 提醒创建后，用户还要去“我的提醒”页面复制日历订阅链接并添加到手机日历，提醒才会生效。用户问设置方法时说明这一步。
- 信息未知时明确说“待公布”。提醒规则问题以提醒与核验规则知识库为依据。

用中文简洁回答，给出来源链接，并区分已获批复、票务状态和未知信息。
$agent_prompt$,
    update_time = CURRENT_TIMESTAMP
FROM t_agent_profile profile
WHERE prompt.agent_id = profile.id
  AND prompt.slot_key = 'AGENT_MAIN'
  AND prompt.deleted = 0
  AND profile.name = '星序演出助手'
  AND profile.deleted = 0;

COMMIT;
