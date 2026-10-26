# Bad Case 统一候选池使用说明

管理入口：`/admin/bad-cases`，全部API要求admin。

## 页面与状态

- 人工待办默认只取 `PENDING + READY_FOR_HUMAN`；高置信仍需人工核实根因。
- 低置信池保存重复追问、连续澄清、无后续行为、疑似证据不一致。多个 LOW 不会相加变成 HIGH。
- 抽样评审用于发现问题；测量抽查是独立人工队列，包含机器判正常/已抑制的样本。
- 每条候选可展开信号、最小脱敏证据、发生时间、规则版本、快照完整度、机器建议和审核历史。
- REVIEWED 不被新信号回退；FIXED 仅被完成之后新发生的高置信信号重开，旧审核先存历史、清空本轮字段并重新补现场。
- 所有信号失效时才可抑制候选；单条无后续信号失效不会影响并存的点踩。

## 开关与灰度

部署镜像配置在 `resources/docker/server/releases/starline-20261022/application.yaml` 的 `rag.feedback.bad-case`。
新高置信入口最初SHADOW，部署联调通过后由脚本开启运行异常、明确纠正、星序业务、邮件交付四类入口。
行为、抽样、自动评审、聚合告警默认分别关闭，按试用规模逐项启用。点踩保持ON。
`OFF`停止入口；`SHADOW`只记检测指标；`ON`写候选/信号。管理页的试扫不会写信号或推进游标。

当前没有星序创建提醒的 Agent/MCP 工具适配器。业务校验已经有稳定错误码和显式调用上下文，普通表单被正确拒绝不建单；AGENT_TOOL/SYSTEM_JOB违例经测试覆盖。真实Agent业务全链路需未来工具适配器传入该上下文。

## 评测闭环

1. 人工确认根因，选择有代表性且可复现的候选。
2. 在详情编辑样本草稿。会话样本使用现有runner字段；业务校验选择SERVICE_TEST，邮件/外部交付选择INTEGRATION_TEST，提供testRef、fixture和assertions。
3. 人工核对脱敏、固定时间/外部工具数据及正确断言后批准。系统不会根据用户纠正自动推断标准答案。
4. 导出已批准JSON及独立manifest。CONVERSATION导出只包含现有runner认可的字段；通过代码评审合入仓库。生产管理页面不直接写仓库文件。
5. 使用相同数据和运行条件比较baseline/candidate。现有runner产物转换示例：

```powershell
python resources/regression/agent-memory-evaluation/prepare_bad_case_import.py --run-dir <运行产物目录> --manifest <manifest.json> --output <import.json>
```

6. 通过SSH隧道直连API（不能通过公网Nginx），携带管理员登录令牌向 `/rag/bad-cases/eval-samples/import-result` 提交JSON。每次完整运行按 `(runId,caseId,turnId,repetition)` 幂等，缺失轮次/重复次数、指纹不匹配均拒绝。
7. 每个measure轮的全部scored repetition都成功且断言通过才是PASSED；请求失败/unscored为ERROR，不据此判断复发。较旧运行只留痕，不回退最新结论。
8. 同一指纹先通过再失败才是复发；持续失败不重复计复发。已有FIXED候选重开会保留审核历史。最后由人工确认FIXED，关联样本未通过时后端拒绝标记。

## 安全与运维

- 邮件发送异常与状态落库异常分开。UNKNOWN投递结果或SENDING卡滞不会被自动重发。
- 自动评审无工具权限，输入脱敏限长、有超时/重试/日预算与多实例预占；不完整/截断证据强制UNCERTAIN。
- 抽样冻结成员、种子和高水位，固定样本量在配额后确定，真实概率为selected/frame；样本未全部人工检查前不报告整体问题率。
- 指标与试扫结果在stats/scan API可查。告警目前是结构化聚合日志入口，开启后按严重度和阈值输出，不会主动向外部群聊发送消息。
- 服务器操作按日见 `docs/server-operations/`，本次变更详见 `docs/change-log/2026-10-25.md`。
