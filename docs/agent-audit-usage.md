# Agent 运行审计使用说明

## 入口

管理员登录后，打开“管理后台 → Agent 运行审计”，或访问：

- 列表：http://139.199.191.109/admin/agent-runs
- 详情：http://139.199.191.109/admin/agent-runs/{taskId}
- Bad case 详情：“Agent 运行现场”区域。

Agent 运行审计记录对话、模型与工具执行；旧 workflow 的“RAG 链路追踪”入口及存储已下线。

## 一次查询怎么查看

1. 新建 Agent 对话，要求“调用 search_knowledge 查询提醒提前量规则，再根据依据回答”。
2. 等待回答保存，打开“管理后台 → Agent 运行审计”，找到刚才的对话并进入运行详情。
3. 紫色 LLM 节点表示模型调用，绿色 TOOL 节点表示工具调用；点击任意节点。
4. LLM 输入中的 data.messages 是这次调用实际传入的 system、历史、user、tool-result 等上下文；同一输入还包含工具定义和允许记录的模型选项。
5. LLM 输出有 answer、thinking、toolCalls；TOOL 输入为解析后的参数，输出为结构化工具结果。相同工具名的多次调用按 callId 分开。
6. 顶部显示总耗时、轮次、模型/工具调用次数与 Token。模型没有返回 usage 时显示“未知”。

瀑布图支持按开始时间或耗时排序。并行工具用实际执行区间判断重叠，批次耗时按区间计算，不把并行耗时直接相加。

## 状态与限制

- NORMAL：Agent 正常结束。工具节点可能失败后被模型处理，应同时查看节点状态。
- AWAITING_CONFIRM：等待用户确认；确认后新运行通过 parent_run_id 链接原运行。
- INTERRUPTED：用户取消或运行被中断。
- FAILED：执行链路异常或达到最大迭代次数。
- PARTIAL：发生字段/运行预算截断、节点上限、队列拒绝或记录失败；不能视为完整现场。
- DISABLED：关闭节点 payload 采集，仍保留运行摘要、结构、状态、耗时与 usage。

新功能上线前的历史对话不能恢复当时未采集的 prompt；Bad case 会退回原消息事件快照。现有 Bad case 台账内容为生成时快照，重新生成后才会更新它的审计匹配摘要；详情可直接按消息实时查看新审计。

内容先经过字段脱敏与上限控制。字段 32,768 字符、单 payload 131,072 字符、整次运行 1,048,576 字符、最多 256 节点；队列默认 512 项、总预算 32 MiB，另有 64 个收尾槽位。超过上限保留明确标记。已知密钥模式可以遮蔽，不能保证任意自然语言中的个人信息都被自动识别。

当前没有自动清理历史审计的定时任务；30 天保留期仍是待确认建议。数据库会随使用增长，后续按实际量决定保留与归档策略。

## 配置与数据库

配置源：bootstrap/src/main/resources/application.yaml 中 agent.audit。服务器使用 resources/docker/server/releases/starline-20261022/application.yaml 的同步副本，通过 Compose 只读挂载到 /app/application.yaml 并以 SPRING_CONFIG_ADDITIONAL_LOCATION 加载；环境变量覆盖密码与 key。旧 OTel Trace 配置和导出器已从项目中移除。

已验证运行：[2113935016424869889](http://139.199.191.109/admin/agent-runs/2113935016424869889)，管理员登录后可查看。该运行有 2 次模型调用、1 次工具调用和 4 个节点。

表：

- public.t_agent_run：每次请求一条运行汇总，run_id 等于 SSE taskId。
- public.t_agent_run_node：模型、工具、确认等节点，输入输出为 JSONB。

SQL：resources/database/migrations/20261024_agent_run.sql。
新库初始化：resources/database/schema_pg.sql。
发布镜像副本：resources/docker/server/releases/starline-20261022/。

管理员接口均在 /api/starlineagent 前缀下：

- GET /agent/runs：分页列表，支持运行/会话/消息/状态/日期/慢运行/输入 Token 筛选。
- GET /agent/runs/{runId}：运行汇总和全部节点。
- GET /agent/runs/{runId}/nodes：节点列表。
- GET /agent/runs/by-message/{messageId}：按助手消息精确定位运行。

## 部署记录

服务器每条操作与结果见 docs/server-operations.md。发布脚本先备份数据库与当前应用，增量建表后替换 JAR/前端并重建 API/Web；异常自动恢复应用文件。Langfuse 保持停用，无新增容器和端口。
