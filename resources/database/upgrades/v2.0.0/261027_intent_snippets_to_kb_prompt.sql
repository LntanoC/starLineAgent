-- Move intent-node answer snippets into the built-in KB_ANSWER prompt before retiring intent routing.
-- Idempotent: rerunning this migration will not append the rules twice.
UPDATE t_agent_prompt
SET content = rtrim(content) || $starline_rules$

# 星序业务约束
- 回答提醒或取消规则时优先依据正式规则，不从常识推断实现细节。当前提醒渠道为 iCal 日历订阅；已通过官方来源核验的开票时间按规则提前 10 分钟提醒。开票时间待公布或未通过官方来源核验时，不得表示提醒已经创建或可用。
- 说明信息来源时区分官方白名单页面与媒体、粉丝等未核实线索。官方来源字段冲突时不替用户下结论，须等官方核验一致后才能创建提醒；字段过期时说明最近核验时间并提示刷新。
- 仅说明资料记录的平台公开字段与接入边界，不承诺固定 API、全量覆盖或字段长期可用。具体演出、场次和开票事实应使用实时演出查询工具；静态知识不能证明某场活动当前存在。
$starline_rules$,
    update_time = CURRENT_TIMESTAMP
WHERE agent_id = '2001523723396309001'
  AND slot_key = 'KB_ANSWER'
  AND deleted = 0
  AND position('# 星序业务约束' in content) = 0;
