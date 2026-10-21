-- v2.0.0 260909 Agent 轮次耗时落库
-- 原先前端回放是拿 assistant 与前一条 user 的落库时刻相减，两个毛病：
-- 一是「配对即消费」，一个 Turn 里确认续跑那条 assistant 前面没有 user，永远配不上，刷新后整段耗时消失；
-- 二是这个差值把排队、落库延迟都算了进去，与服务端实测差几十到几百毫秒
-- 改成服务端在 run 收口那一刻定格一次，落库与 SSE 读的是同一个数，直播与刷新后显示一致
-- 只给 assistant 写值；user 行与全部历史数据留 null，前端读到 null 就不显示耗时，不再回退到时刻相减
-- 挂起等确认的那段也在这里收口，续跑是新的一次 run 各报各的；用户点确认前想了多久不进任何一段

ALTER TABLE t_agent_message
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
COMMENT ON COLUMN t_agent_message.duration_ms IS '本轮 run 的服务端耗时（毫秒），仅 assistant 有值';
