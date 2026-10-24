# Agent 调用链路追踪：项目内自建审计设计

## 1. 目标与范围

为每次 Agent 流式运行保存可回放的执行现场：ReAct 轮次、实际发送给模型的 messages、模型输出与 token 用量、工具调用入参/结果/状态/耗时、首字延迟，以及中断或失败原因。管理员可从后台按运行记录检索并查看时间瀑布图；Bad case 可从差回答跳到对应运行现场。

方案只使用星序已有的 Spring Boot 服务和 PostgreSQL，不增加外部追踪平台、容器、端口或 API key。成本包括数据库存储、写入 I/O 和采集缓冲；完整 prompt 采集不可能做到零资源开销，因此必须设置上限、保留期和访问控制。

2026-10-24：审计表、AgentScope 采集、异步写入、管理员 API、前端列表/瀑布详情及 Bad case 消息关联已实现。使用方法见 ../agent-audit-usage.md；部署与实际验收结果见 ../server-operations.md。旧 OTel 集成已从 Agent 主流程和依赖中移除。

## 2. 当前项目基础与方案边界

- 一次运行在 AgentChatServiceImpl 创建 Snowflake taskId，并由 AgentRunHandle 持有；它是停止任务与流式生命周期的键。
- SSE 的 AgentMetaPayload 已向前端发送 conversationId 和 taskId，运行详情可按这个 ID 查询。
- AgentScope 事件由 AgentStreamEventBridge 翻译为用户时间线块；t_agent_message.blocks 服务聊天展示和现有 Bad case 摘要，本方案不向其中塞 prompt 或 token。
- 项目已有 t_agent_message、t_bad_case、RAG Trace 页面和管理员鉴权，可沿用管理端布局，但 Agent 运行审计与 RAG Trace 是两类数据。
- Agent 运行审计以自建 run_id 为主键，不依赖 OTel trace/span ID。

## 3. 总体结构

    用户请求 / SSE
        -> AgentChatServiceImpl 创建 taskId
        -> AgentScope Agent 与 AgentAuditMiddleware
        -> 有界异步写入队列
        -> 星序 PostgreSQL（t_agent_run / t_agent_run_node）
        -> 管理员查询 API
        -> Agent 运行列表与瀑布详情
        -> Bad case 关联与分析

新增独立包 agent.audit：AgentAuditMiddleware 采集事件，AgentAuditSerializer 负责稳定的结构化序列化，审计服务汇总运行状态，Repository 使用参数化 JDBC 负责 PostgreSQL 存取。它不依赖 OTel Trace。旧 workflow 存储已下线，模型回调仅保留无存储兼容契约。现有 AgentStreamEventBridge 仍负责 SSE 和用户时间线，避免审计改变对话展示语义。

## 4. 运行标识和状态

### 4.1 主键

- run_id = taskId，run_id 唯一，最长 64 字符。它标识一次真实 Agent 执行请求，不是外部生成的 trace ID。
- 每个 HTTP/SSE 请求各是一条 run。用户批准待确认工具后，服务会创建新的 taskId；新 run 通过 parent_run_id 和 resume_message_id 关联等待确认的上一个 run，保留完整的中断点和续跑关系。
- conversation_id、user_id、user_message_id、assistant_message_id 用于列表过滤、权限校验和 bad case 精确关联。运行结束后回填消息 ID。

### 4.2 t_agent_run：一次执行一行

建议字段：

| 字段 | 说明 |
| --- | --- |
| id | 项目现有 MyBatis-Plus Snowflake 字符串 ID，VARCHAR(20) |
| run_id | taskId，VARCHAR(64)，唯一索引 |
| parent_run_id / resume_message_id | 确认后续跑时关联原运行 |
| conversation_id / user_id | 所属会话和用户 |
| user_message_id / assistant_message_id | 对应消息，完成或待确认时回填 |
| question / answer | 列表检索摘要，详情仍以消息表为准 |
| run_status | RUNNING、NORMAL、INTERRUPTED、FAILED、AWAITING_CONFIRM |
| capture_status | COMPLETE、PARTIAL、DISABLED，标记采集是否完整 |
| round_count / model_call_count / tool_call_count | 分开统计 ReAct 轮次、模型调用和工具调用 |
| input_tokens / output_tokens / cached_tokens | 聚合模型 usage；供应商未返回时保留未知状态 |
| duration_ms / error_message | 运行耗时和经过长度限制的错误信息 |
| create_time / update_time / deleted | 沿用项目字段风格 |

索引采用 (conversation_id, create_time DESC)、(create_time DESC)、(run_status, create_time DESC) 和 UNIQUE(run_id)。不建立到会话/消息的外键，沿用项目的软删除及历史兼容方式。

### 4.3 t_agent_run_node：一个模型或工具节点一行

字段包括 run_id、node_id、parent_node_id、round_index、node_type、node_name、status、sequence、batch_id、input_payload JSONB、output_payload JSONB、token、ttft_ms、起止时间、耗时、错误和软删除信息。

- node_type：AGENT、LLM、TOOL、CONFIRM、ERROR。
- status：RUNNING、SUCCESS、FAILED、AWAITING、DENIED、INTERRUPTED。
- LLM.input_payload 保存模型实际收到的 messages（角色、名称、全部 blocks 和必要 metadata）；输出保留文本、thinking、tool-use 块。
- TOOL.input_payload 保存解析后的参数；output_payload 保存结构化结果。敏感字段在序列化前按字段策略遮蔽。
- 同一批并行工具用 batch_id + sequence 区分；parent_node_id 指向产生该批工具调用的 LLM 节点。
- 唯一键采用 (run_id, node_id)；查询索引采用 (run_id, started_at)、(node_type, status)。

列表查询只取摘要列，问题截断至 300 字，不加载 JSONB；详情接口才读取 payload。单字段最多 32,768 字符，每份 payload 最多 131,072 字符，逐字段截断并记录 truncated 及可读截断标记；整次运行另限 1,048,576 字符。未知 AgentScope block 类型保留类名与文本预览，不静默丢弃。

## 5. 采集时机与生命周期

| 采集点 | 数据动作 |
| --- | --- |
| onAgent 进入 | 创建 t_agent_run，记录问题、用户/会话和 RUNNING |
| 每次 onModelCall | 分配 LLM 节点与递增 round_index，快照实际 ModelCallInput.messages() |
| 首个 TextBlockDeltaEvent | 写 LLM 节点 ttft_ms，之后不覆盖 |
| ModelCallEndEvent | 收口输出和 usage；未返回 usage 时不伪造 token 数 |
| onActing / 工具开始 | 按调用批次创建 TOOL 节点并记录开始时间 |
| ToolCallDeltaEvent | 按 toolCallId + callIndex 聚合增量参数，使用 append；结束后解析 JSON，失败则保留原始字符串 |
| TOOL_RESULT_END | 写工具状态、结果、结束时间和耗时 |
| 确认/拒绝事件 | 写 CONFIRM 节点 AWAITING 或 DENIED；run 收口为 AWAITING_CONFIRM |
| AgentRunHandle complete/cancel/fail/release | 兜底收口 NORMAL、INTERRUPTED 或 FAILED，回填消息 ID 和汇总值 |

确认续跑的关联在 AgentChatServiceImpl.startConfirmRun(...) 根据被确认的 messageId 查找上一条 assistant_message_id，再写 parent_run_id。没有精确上游时不按时间猜测关联。

## 6. 写入策略与故障隔离

审计是旁路能力，不能拖慢 SSE 或改变工具执行结果。入口创建一行轻量 run 记录；模型节点/工具节点完成时将有界快照异步落库，最终汇总在生命周期收尾时更新。这样进程意外退出时，已经结束的节点仍可查。

- 使用单写线程和有界事件队列，另预留 64 个收尾槽位；收尾优先并以 revision 防止旧快照覆盖新状态；同时限制队列任务数和 payload 总字节，不能只按任务数量限流。
- 队列满时丢弃新审计快照，不阻塞业务；将 run 标记为 PARTIAL 并计数，不能静默显示完整。拒绝策略不使用 DiscardOldestPolicy，避免丢掉已排队的 run 或节点起始记录。
- 节点起止状态采用同一节点的 insert/update 或 upsert。批量 flush 有事务边界；失败有限重试，重试耗尽只记录脱敏告警。
- 启动时只将超过阈值的旧 RUNNING 标记为 INTERRUPTED，并注明进程中断；单机条件下可按更新时间判断。扩容前需增加实例租约或心跳，避免误结束其他实例正在执行的 run。

## 7. 安全、存储与保留

完整 prompt 可能包含用户个人信息、内部提示词和工具返回内容。后台 API 必须和 BadCaseController 一样执行 StpUtil.checkRole("admin")；列表不返回大字段；详情接口不把 prompt 写入应用日志。对 API key、密码、邮箱验证码等已知密钥字段进行序列化前遮蔽。数据库继续使用服务器现有私网访问，不开放新端口。

建议首期原始 payload 暂按 30 天保留，之后删除或归档节点 payload、保留必要汇总指标；具体期限与自动清理在上线评审时确定。删除和归档按 run_id 整体处理，避免留下显示为完整的部分链路。关闭内容采集时仍保存结构、状态、耗时和 usage，并显示 capture_status=DISABLED。

## 8. 查询 API 与管理端

接口沿用项目 Result、MyBatis-Plus 分页和管理员鉴权：

    GET /agent/runs
    GET /agent/runs/{runId}
    GET /agent/runs/{runId}/nodes
    GET /agent/runs/by-message/{messageId}

列表过滤项：current、size、conversationId、runId、runStatus、时间范围、slowOnly、minInputTokens。列表只查汇总列；详情返回汇总及续跑关系；nodes 返回按开始时间排列的节点树。仅知道 runId 不能绕过管理员鉴权。

前端新增 /admin/agent-runs 列表页和 /admin/agent-runs/:runId 详情页，作为唯一对话执行审计入口（旧 /admin/traces 已下线）。列表展示时间、会话、问题摘要、模型/工具次数、耗时、token、运行状态和采集状态。详情页显示瀑布图与节点折叠面板，呈现模型轮次、工具批次、并行关系及耗时；用户聊天继续读取原有 blocks，SSE 时间线语义不变。

frontend/src/lib/agentTimeline.ts 已有批区间、并行识别和耗时口径。实现时优先抽取共用 AgentWaterfall，避免聊天回放和后台审计的两套算法漂移；RAG Trace 继续使用自己的数据接口。

## 9. Bad case 关联

Bad case 优先通过 assistant_message_id 精确关联 t_agent_run，再以 run_id 读取节点；没有审计记录时退回消息 blocks。不要要求旧 OTel trace_id，也不对多个时间候选自动选一条。Bad case 详情嵌入相同的 AgentWaterfall，形成“点踩 → 运行现场 → 人工判断根因”的链路。

## 10. 分阶段验收

### 阶段 A：结构与关联

新增 20261024_agent_run.sql 和 verify 脚本；同步 schema_pg.sql 与服务器 release 副本。验证表、唯一键、索引；合成行事务回滚后残留为零。t_agent_message.blocks 保持原样。

### 阶段 B：采集和收口

实现 agent.audit、序列化器、DAO 和有界写入器；入口创建 run，结束/取消/失败均可收口。用一次带工具调用和一次人工确认续跑的对话验收：prompt 包含 system/history/tool-result；工具参数增量不丢；并行同名调用不串值；缺 usage 和被截断内容有明确标记。

### 阶段 C：管理 API

实现三个只读 API、分页筛选和管理员鉴权。列表不加载 JSONB；详情按 runId 取得节点；普通用户访问被拒绝。

### 阶段 D：管理页面与 Bad case

完成列表、瀑布图、节点详情，并将 Bad case 精确关联 run。验收并行工具的重叠区间、状态、节点耗时、token 和采集部分丢失提示。

### 每阶段的可观察验收

- 普通对话：一条 t_agent_run，含最终状态和 assistant_message_id。
- 工具对话：存在关联的 LLM/TOOL 节点，参数、结果和耗时可查。
- 确认续跑：前后两条 run 通过 parent_run_id 关联，首条为 AWAITING_CONFIRM。
- 取消与异常：分别收口为 INTERRUPTED / FAILED，已完成节点仍可读。
- 队列达到上限：SSE 继续工作，审计标为 PARTIAL，不显示为完整。

## 11. 本轮 Langfuse 退役范围

服务器移除 Langfuse 六个容器、专用 Compose 文件和 .env 中 LANGFUSE_ 变量；Agent API Compose 不再注入 Langfuse endpoint/keys，也不连接 langfuse-net。Langfuse 数据卷保留为可恢复的离线数据，不再消耗容器内存或 CPU；如需释放磁盘空间，应另行明确删除卷。

项目移除 Langfuse Compose/环境变量模板、专用回读探针、旧 OTel 代码、导出器依赖和配置项。历史消息与 bad case 中已有的旧 trace ID 会在数据库迁移时改名保留，不再写入或读取。
## 12. 实现说明与验收边界

- AgentAuditRepository 采用现有 NamedParameterJdbcTemplate；未额外增加 ORM 模块。模型节点按 onReasoning 轮次标记，callIndex 从 AgentToolExecutionFacts 获取，参数按 round/replyId/toolCallId/callIndex 分组追加。
- 写入采用事件队列与收尾队列，同一节点 UPSERT 通过 revision 拒绝旧版本覆盖；收尾 bundle 有事务边界。队列/字节预算超限即标记 PARTIAL，不阻塞 SSE。
- captureContent=false 时节点 payload 为 null；未知 usage 为 null。字段截断会标记 PARTIAL。现阶段不自动删除历史审计，30 天保留策略仍需评审确认。
- 当前单机启动恢复只收口超过 24 小时的 RUNNING；多实例租约、完整队列监控指标及自动归档不在本次实现内。
- 已有单元用例覆盖参数隔离、取消/失败收口、内容脱敏/预算及消息关联。生产真实模型/工具与页面检查结果另见部署记录；人工确认续跑、多工具并行与极端队列压力仍需结合对应业务工具做场景验收。
