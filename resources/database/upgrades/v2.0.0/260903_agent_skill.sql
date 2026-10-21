-- v2.0.0 260903 智能体技能
-- 技能是写给模型看的操作手册：正文纯 Markdown，讲清一件事按什么顺序办、中途要判断什么
-- skill_code 是模型加载正文时报的名字，唯一索引带 WHERE deleted = 0，删掉之后这个名字可以重新占用
-- tool_ids 存「加载本技能后才解锁」的 MCP 工具，取值只能来自意图树的 MCP 节点；它不是「正文会用到的工具」，
-- 正文可以按名字引用任何工具。只放不看手册就会办错的那几个（提交、预订这类写操作），其余工具照常直接暴露给主 Agent
-- 默认 enabled = 1：技能建出来就是要用的，不想启用的可以在控制台停用

CREATE TABLE IF NOT EXISTS t_agent_skill (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    skill_code  VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) NOT NULL,
    content     TEXT         NOT NULL,
    tool_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill_code ON t_agent_skill (skill_code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_skill_enabled ON t_agent_skill (enabled, deleted);

COMMENT ON TABLE t_agent_skill IS '智能体技能表';
COMMENT ON COLUMN t_agent_skill.id IS '主键ID';
COMMENT ON COLUMN t_agent_skill.skill_code IS '技能标识，模型按此名加载正文';
COMMENT ON COLUMN t_agent_skill.name IS '技能展示名';
COMMENT ON COLUMN t_agent_skill.description IS '技能适用场景，随清单一起交给模型判断是否加载';
COMMENT ON COLUMN t_agent_skill.content IS '技能正文 Markdown，模型加载后按此执行';
COMMENT ON COLUMN t_agent_skill.tool_ids IS '加载技能后才解锁的 MCP 工具 ID，取自意图树 MCP 节点';
COMMENT ON COLUMN t_agent_skill.sort_order IS '排序，越小越靠前';
COMMENT ON COLUMN t_agent_skill.enabled IS '是否启用 0：停用 1：启用';
COMMENT ON COLUMN t_agent_skill.create_by IS '创建人';
COMMENT ON COLUMN t_agent_skill.update_by IS '更新人';
COMMENT ON COLUMN t_agent_skill.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_skill.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_skill.deleted IS '是否删除 0：正常 1：删除';

-- AGENT_MAIN 补技能判据：告诉模型什么时候该加载手册、什么时候直接查知识库
-- 用追加而不是整段覆盖，控制台改过人设的不会被这次升级冲掉；NOT LIKE 保证可重复执行
-- 追加后需重启进程或在控制台重新保存一次人设，提示词缓存才会失效
UPDATE t_agent_prompt
SET content     = content || $prompt$

# 技能手册
清单里出现 load_skill 时，说明本系统为部分业务事项写好了办理手册。手册讲的是"这件事按什么顺序办、中途要判断什么"，不是资料。

- 用户是来办一件事的（申请、提交、预订、变更、取消、办理某项业务）→ 先看 load_skill 描述里的技能清单有没有对得上的场景，有就先加载手册再动手
- 用户不是来办事，只是问情况、要数据、要解释说明的 → 都不加载技能，按上面的工具选择判据挑工具
- 同一件事既要问规定又要办：先答问的那部分；用户已经明确要办的，接着直接加载手册继续，不要再确认一遍办理意图；用户把办不办挂在答案上（"可以的话再…"）的，按答案决定，不成立就说明情况停下
- 不看手册就容易办错的工具（如提交、预订），在对应手册加载前不出现在工具清单里。判断入口是技能清单不是工具清单：要办的事在技能清单里有对得上的场景，就先加载那份手册；没有对得上的，按上面的工具选择判据处理，不要改用别的工具凑
- 手册加载后按手册执行：手册写明的追问项、顺序与注意事项优先于本节以外的通用规则；手册没覆盖到的地方仍按上面的工具选择判据处理
- 只加载确实用得上的那一两份手册，不要为了"先看看"把清单里的手册逐个加载$prompt$,
    update_time = CURRENT_TIMESTAMP
WHERE slot_key = 'AGENT_MAIN'
  AND deleted = 0
  AND content NOT LIKE '%# 技能手册%';

-- AGENT_MAIN「调用方式」段补相对日期规则：它是所有带日期参数工具的通用规则，不该只写在某份手册里，
-- 否则 leave_query / meeting_room_query 这类不挂手册的查询工具拿不到这条约束
-- 锚在 260812 那行必填参数规则后面插入；控制台改过那一行的锚不上，UPDATE 0，需在控制台手工补这一条
UPDATE t_agent_prompt
SET content     = REPLACE(content,
                          '- 必填参数无法从对话中确定时，一次问清再调用，不要猜测或填默认值；能从上下文补齐的和可选的都不要追问',
                          '- 必填参数无法从对话中确定时，一次问清再调用，不要猜测或填默认值；能从上下文补齐的和可选的都不要追问' || E'\n' ||
                          '- 参数要具体日期而用户只说了「今天」「明天」「下周一」这类相对日期时，先用日期类工具取到今天是几号再换算，本轮已取过的直接复用；没有日期工具或调不通就问用户具体日期，不要凭印象推断'),
    update_time = CURRENT_TIMESTAMP
WHERE slot_key = 'AGENT_MAIN'
  AND deleted = 0
  AND content NOT LIKE '%这类相对日期时，先用日期类工具%';
