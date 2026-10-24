# Agent 调用链路追踪 —— 自建方案

> 目标：每一轮 Agent 对话都能看到完整执行链路（ReAct 轮次、模型调用、工具调用、完整 prompt、token 用量），
> 并在管理端可视化。零外部组件、约 0 内存增量、数据全部落在自己的库里，可用 SQL 查询、可被代码读取。
>
> 本方案不依赖任何外部追踪服务，不引入额外容器，不占用新端口。

---

## 一、设计边界

**要做的：**
- 记录一轮对话的完整执行现场：轮次、每次模型调用的 prompt 与 token、每次工具调用的入参/结果/耗时
- 管理端列表 + 详情瀑布图
- 数据可被 bad case 分析与看板直接消费

**明确不做的：**
- 成本核算（按模型单价折算金额）
- 提示词版本管理与在线调参
- 数据集实验对比
- 跨服务分布式链路串联

前两项若将来确有需要，再单独评估，不在本期范围内。

---

## 二、三个决定架构的判断

### 判断 1：链路主键用 `taskId`，不用任何外部追踪标识

`AgentRunTracer.traceId()` 第 142-145 行的实现：

```java
return ref == null || !ref.span.getSpanContext().isValid() ? null : ref.span.getSpanContext().getTraceId();
```

这段代码依赖一个 span 是否存在且有效，而 span 是否有效取决于外部追踪能力的开关。**把链路主键绑在它上面，等于让「能不能查链路」依赖「某个开关开没开」**——这是本方案要避免的。

本方案不启用任何外部追踪能力，因此那条路径天然返回 `null`，完全不能作为主键来源。

**决策**：`run_id = taskId`。

理由：
- `AgentRunHandle` 第 46 行持有 `private final String taskId`，每轮对话唯一且必然存在；
- SSE 的 `meta` 帧已经把它下发到前端（`AgentMetaPayload(conversationId, taskId)`），前端手上就有链路 ID，不需要新增传参；
- `taskId` 同时是 `StreamTaskManager` 的键，与"停止生成"天然对齐。

### 判断 2：新建独立的 `audit` 包，不复用 `trace` 包

`agent/trace/` 下现有 10 个类是围绕外部追踪后端设计的：`AgentTraceSerializer` 是 package-private 且注释写明"摊成某后端能展开的 JSON"，`AgentTracingConfiguration` 会 `GlobalOpenTelemetry.set()` 占用全局位置（第 106-109 行已占用则直接抛异常）。

**决策**：新建 `agent/audit` 包，不 import `trace` 包任何东西，自带序列化器。

收益：
- 审计与外部追踪能力彻底解耦，互不影响；
- 将来整个 `trace` 包废弃或删除，审计不受牵连；
- 序列化需求本来就不同——审计要的是入库友好的结构化 JSON，不需要对外部后端的属性名做适配。

代价：约 80 行序列化逻辑需要自己实现（详见 4.2），换来的是零耦合。

### 判断 3：新增两张表，不动 `t_agent_message.blocks`

`blocks` 是**给用户看的时间线**：`AgentTurn.tsx` 直接渲染它，`AgentBadCaseMessageProvider` 把它转成节点树。它的语义是展示，不是审计。

往里塞 prompt 和 token 有三个问题：
1. **语义污染**——展示模型与审计模型混在一起，后续谁都不敢改；
2. **查不了**——它是整条消息一个大 JSONB，无法按轮次、按工具名、按耗时聚合，也建不了索引；
3. **冗余**——工具的状态和耗时 `blocks` 里已经有了，审计不该再维护一套并行状态机。

**决策**：`blocks` 保持原样。审计数据独立成表，通过 `run_id` + `message_id` 与消息关联。

---

## 三、数据结构

### 3.1 `t_agent_run`（一轮问答一行）

```sql
CREATE TABLE IF NOT EXISTS t_agent_run (
    id                   VARCHAR(20) NOT NULL PRIMARY KEY,
    run_id               VARCHAR(64) NOT NULL,
    conversation_id      VARCHAR(20) NOT NULL,
    user_id              VARCHAR(20) NOT NULL,
    user_message_id      VARCHAR(20),
    assistant_message_id VARCHAR(20),
    question             TEXT,
    answer               TEXT,
    run_status           VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    round_count          INTEGER     DEFAULT 0,
    model_call_count     INTEGER     DEFAULT 0,
    tool_call_count      INTEGER     DEFAULT 0,
    input_tokens         BIGINT      DEFAULT 0,
    output_tokens        BIGINT      DEFAULT 0,
    cached_tokens        BIGINT      DEFAULT 0,
    duration_ms          BIGINT,
    error_message        TEXT,
    create_time          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_run UNIQUE (run_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_run_conv  ON t_agent_run (conversation_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_time  ON t_agent_run (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_state ON t_agent_run (run_status, create_time DESC);
```

`run_status` 取值：`RUNNING` / `NORMAL` / `INTERRUPTED` / `FAILED` / `AWAITING_CONFIRM`
（与 `AgentMessageStatus` 的 `NORMAL / INTERRUPTED / AWAITING_CONFIRM` 对齐，另加运行期的 `RUNNING` 与异常的 `FAILED`）

### 3.2 `t_agent_run_node`（一次调用一行）

```sql
CREATE TABLE IF NOT EXISTS t_agent_run_node (
    id             VARCHAR(20) NOT NULL PRIMARY KEY,
    run_id         VARCHAR(64) NOT NULL,
    node_id        VARCHAR(32) NOT NULL,
    parent_node_id VARCHAR(32),
    round_index    INTEGER     DEFAULT 0,
    node_type      VARCHAR(16) NOT NULL,
    node_name      VARCHAR(128),
    status         VARCHAR(16),
    sequence       INTEGER     DEFAULT 0,
    batch_id       VARCHAR(32),
    input_payload  JSONB,
    output_payload JSONB,
    input_tokens   BIGINT,
    output_tokens  BIGINT,
    cached_tokens  BIGINT,
    ttft_ms        BIGINT,
    started_at     TIMESTAMP,
    ended_at       TIMESTAMP,
    duration_ms    BIGINT,
    error_message  TEXT,
    create_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_node UNIQUE (run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_node_run  ON t_agent_run_node (run_id, started_at);
CREATE INDEX IF NOT EXISTS idx_agent_node_type ON t_agent_run_node (node_type, status);
```

`node_type` 取值：`AGENT`（根）/ `LLM`（模型调用）/ `TOOL`（工具调用）/ `CONFIRM`（待确认）/ `ERROR`（中断提示）

字段用途说明：

| 字段 | 用途 |
|---|---|
| `input_payload` | LLM 节点存完整 messages（人设+记忆+摘要+工具结果）；TOOL 节点存入参 |
| `output_payload` | LLM 节点存正文/思考/工具调用；TOOL 节点存结果 |
| `round_index` | 第几轮 ReAct，工具节点归属靠它 |
| `parent_node_id` | 指向该轮 LLM 节点，还原树形 |
| `batch_id` + `sequence` | 同批并行工具靠这两个区分，同名并行时 `sequence` 是唯一判别 |
| `ttft_ms` | 首字延迟，用户实际感知的等待 |
| `status` | `SUCCESS` / `FAILED` / `AWAITING` / `DENIED` / `INTERRUPTED` |

---

## 四、采集层

### 4.1 `AgentAuditMiddleware`

位置：`agent/src/main/java/.../agent/audit/AgentAuditMiddleware.java`

实现 `AgentMiddleware` 接口，装配条件：

```java
@ConditionalOnProperty(prefix = "agent.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
```

默认开启（审计是排查基础设施，不该需要额外打开）。

**采集点与写入内容：**

| 回调 | 取值 | 写入 |
|---|---|---|
| `onAgent` 入口 | 首条 user 消息 | 建 `t_agent_run`，`run_status=RUNNING` |
| `onModelCall` | `ModelCallInput.messages()` | 建 LLM 节点，`input_payload` = 完整 prompt |
| `onModelCall` 首个 `TextBlockDeltaEvent` | `Instant.now()` | 该节点 `ttft_ms` |
| `onModelCall` 内 `ModelCallEndEvent.getUsage()` | `ChatUsage` | `input/output/cached_tokens` |
| `onActing` | 工具调用开始 | 建 TOOL 节点，`status=RUNNING` |
| `TOOL_RESULT_END` | `ToolResultEndEvent.getState()` | 节点收口、`output_payload` |
| `RequireUserConfirmEvent` | 待确认批 | 节点 `status=AWAITING` |
| `AllToolsDeniedEvent` | 被拒批 | 节点 `status=DENIED` |
| `onAgent` 收尾 | 结局 | 更新 `t_agent_run` 汇总 |

### 4.2 序列化器 `AgentAuditSerializer`

**核心逻辑（必须照这个结构写，不要简化）：**

```java
Map<String, Object> describeMessage(Msg msg) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("role", msg.getRole() == null ? null : msg.getRole().name().toLowerCase(Locale.ROOT));
    item.put("name", msg.getName());
    item.put("blocks", describeBlocks(msg.getContent()));
    // 确认续跑那条消息一个块都没有，批准还是拒绝只存在于 metadata 里
    if (msg.getMetadata() != null && !msg.getMetadata().isEmpty()) {
        item.put("metadata", msg.getMetadata());
    }
    return item;
}
```

`describeBlock` 必须覆盖四种块类型 + 未知兜底：

| 块类型 | 输出 |
|---|---|
| `TextBlock` | `{type:"text", text}` |
| `ThinkingBlock` | `{type:"thinking", thinking}` |
| `ToolUseBlock` | `{type:"tool_use", id, name, state, input}` |
| `ToolResultBlock` | `{type:"tool_result", id, name, state, output}` |
| 未知类型 | `{type:"<类名>", value: toString()}` ← **不能吞掉**，否则框架新增块类型会静默丢失 |

截断策略：单字段上限（默认 32768 字）+ 总预算上限（默认 131072 字）。逐字段裁，不要整段裁——否则最有价值的工具结果反而被整块吞掉。

### 4.3 工具入参的采集：必须按 callKey 累积增量

这是最容易踩坑的一处。工具入参**不在 `blocks` 里**，必须自己从流式增量攒：

```java
// ToolCallDeltaEvent 给的是增量片段，不是累积快照 → 必须 append
pending.computeIfAbsent(callKey(event.getToolCallId()), k -> new StringBuilder())
       .append(event.getDelta());

// 收尾时才拼出完整 JSON
String raw = pending.remove(callKey(call.getToolCallId())).toString();
Object arguments = parseOrFallback(raw);   // 解析失败保留原串，不要丢
```

**两个必须注意的点：**
1. **用 `append` 不用覆盖**——`ToolCallDeltaEvent` 是增量而非累积快照，覆盖只会剩下最后一片；
2. **key 不能用裸 `toolCallId`**——同名工具并行时框架会给同一个工具发多个调用，必须用「`toolCallId` + `callIndex`」组合键，否则两个并行调用的入参会串在一起。

### 4.4 异步落库

项目里目前**没有** `@Async` 也没有 `ThreadPoolTaskExecutor`（`MinerUPollingExecutor` 是自己 `new` 的），需要自建：

```java
@Bean(destroyMethod = "shutdown")
public ExecutorService agentAuditExecutor() {
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2000),
            r -> { Thread t = new Thread(r, "agent-audit"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
}
```

**三个要点：**
- **`DiscardOldestPolicy`**：审计是旁路，队列满了宁可丢审计数据，也绝不能反压业务拖慢 SSE；
- **单线程**：天然串行，`t_agent_run` 的更新不需要额外加锁；
- **收尾批量写入**：一次运行把节点攒在内存 `List`，结束时一次 `insert` 全部节点，而不是每个事件一条 SQL。一轮对话通常 2-5 次模型调用 + 少量工具，攒完写一次开销极小。

---

## 五、接口层

照项目中 `RagTraceController` 的三接口范式，路径 `/agent/runs`：

```java
@GetMapping("/agent/runs")                    // 分页列表
Result<IPage<AgentRunVO>> page(AgentRunPageRequest request);

@GetMapping("/agent/runs/{runId}")            // 运行详情（含汇总）
Result<AgentRunDetailVO> detail(@PathVariable String runId);

@GetMapping("/agent/runs/{runId}/nodes")      // 节点列表（瀑布图数据）
Result<List<AgentRunNodeVO>> nodes(@PathVariable String runId);
```

`AgentRunPageRequest` 字段：
`current / size / conversationId / runId / runStatus / beginTime / endTime / slowOnly / minInputTokens`

**两个实现要求：**
1. **鉴权**：`StpUtil.checkRole("admin")`，与 `BadCaseController` 保持一致；
2. **列表不读大字段**：`input_payload` 可能几十 KB。列表查询只取汇总列，用 `left(question,300)` 截断，大字段置 `NULL::jsonb` 占位，详情页再读全量。

---

## 六、前端

### 6.1 页面

| 路由 | 页面 | 内容 |
|---|---|---|
| `/admin/agent-runs` | 列表 | 时间、会话、问题（截断）、轮数、模型/工具次数、总耗时、总 token、状态 |
| `/admin/agent-runs/:runId` | 详情 | 汇总卡片 + 瀑布图 + 节点详情抽屉 |

菜单挂在 `AdminLayout.tsx` 的"链路追踪"分组，与现有 RAG 链路页并列，标签区分清楚。

### 6.2 复用 `agentTimeline.ts`（最大省力点）

`frontend/src/lib/agentTimeline.ts` 已经写好一套成熟规则，直接搬：

- **批区间**：`batchWindow()` 算同批起止
- **并行识别**：`overlapped()` 判断同批是否重叠执行
- **耗时口径**：`durationSource` 区分"单工具自己"与"整批共享"（含老数据兼容）
- **空态**：`isStreaming && blocks.length === 0` 显示等待提示

**建议抽一个 `AgentWaterfall` 组件**，让聊天页与链路详情页共用。否则两处渲染逻辑必然漂移——这是这类系统最常见的腐化方式。

### 6.3 与聊天页的差异

聊天页是**流式友好**的：块实时追加、`open` 状态控制思考块折叠。
链路详情页是**静态回放**：所有节点已定型，应默认全部折叠、按 `started_at` 排序，并支持按耗时排序以快速定位慢节点。

---

## 七、实施顺序

分四步，每步可独立验收。

### 第一步：建表（半天）

1. 新增 `resources/database/migrations/20261024_agent_run.sql`，风格照现有 `20261024_bad_case.sql`：`BEGIN` + `SET LOCAL lock_timeout` + 全部 `IF NOT EXISTS`，保证可重复执行；
2. 配套 `20261024_agent_run_verify.sql`：事务内插合成数据验证唯一键，再 `ROLLBACK`，最后检查合成行残留为 0。

**验收**：两张表存在，唯一键生效，verify 脚本输出残留 0。

### 第二步：采集层（1-2 天）

1. `AgentAuditSerializer`（4.2）+ `AgentAuditMiddleware`（4.1）+ `agent.audit.*` 配置；
2. 异步线程池 + 收尾批量写入（4.4）；
3. 工具入参按 key 累积（4.3）；
4. 收尾回填 `assistant_message_id`。

**验收**：发一条会调工具的对话，`t_agent_run` 出现 1 行、`t_agent_run_node` 出现 N 行；
`input_payload` 能看到完整 messages（人设、历史、工具结果都在）；token 三项非空；两个并行同名工具的入参不串。

### 第三步：接口（半天）

三个只读接口 + 鉴权。

**验收**：按 `runId` 能拿到完整节点树。

### 第四步：前端（2-3 天）

列表页 + 详情页 + 抽 `AgentWaterfall`。

**验收**：从列表进详情，瀑布图正确显示并行工具重叠、批区间、每节点耗时与 token。

---

## 八、与 bad case 打通（建议同期做）

这一步让方案价值明显放大。

现状：`AgentBadCaseMessageProvider` 只能从 `blocks` 拼节点树，缺 prompt、缺 token。而 `BadCaseRules` 里 **R4（疑似召回为空）和 R7（疑似幻觉）都需要真实上下文**才能判断，靠 `blocks` 判断不了。

有了 `t_agent_run_node` 之后：

- `AgentBadCaseMessageProvider` 改为**优先读 `t_agent_run_node`**，读不到再退回 `blocks`；
- bad case 详情页直接嵌 `AgentWaterfall`，形成「点踩 → 看完整链路 → 判根因」的闭环；
- 将来做回归验证时，从 `input_payload` 取出原问题就能重放，不必再解析 `blocks`。

---

## 九、风险与取舍

| 项 | 说明 | 应对 |
|---|---|---|
| **prompt 体积** | 满上下文可达十万字，`input_payload` 会膨胀 | 双层截断（单字段 + 总预算）；`t_agent_run_node` 按月归档 |
| **审计丢失** | 队列满时 `DiscardOldestPolicy` 会丢数据 | 可接受（审计是旁路）；若不可接受，改为先落本地文件再异步补库 |
| **多实例** | 单线程池是进程内的，多实例各写各的 | 无冲突，`run_id` 全局唯一 |
| **工具入参串味** | 同名并行工具若用裸 `toolCallId` 做 key 会混 | 必须用 `toolCallId` + `callIndex` 组合键（4.3） |
| **`parent_node_id`** | 工具节点归属哪一轮推理 | 用 `round_index` + 事件先后推导，不依赖任何 span 父子关系 |
| **存量数据** | 历史消息不会有 `t_agent_run` | 无法回填（prompt 从未采集过），属一次性损失 |
| **`trace` 包保留** | `agent/trace/` 下 10 个类仍在，配置默认关闭 | 保持关闭不动即可，不占资源不装配；与审计完全隔离，互不影响 |

---

## 十、验证方式

采集是否完整，用一条带工具调用的对话就能验完：

```sql
-- 1. 这一轮的汇总
SELECT run_id, question, round_count, model_call_count, tool_call_count,
       input_tokens, output_tokens, duration_ms, run_status
FROM t_agent_run ORDER BY create_time DESC LIMIT 1;

-- 2. 按时间排出瀑布，含每节点耗时与 token
SELECT round_index, node_type, node_name, status, sequence,
       duration_ms, input_tokens, output_tokens, ttft_ms,
       left(input_payload::text, 200) AS input_head
FROM t_agent_run_node
WHERE run_id = '<上一步的 run_id>'
ORDER BY started_at;
```

**验收标准：**
- LLM 节点的 `input_payload` 里能同时看到系统人设、历史消息、工具结果三类内容；
- 两个并行同名工具的 `input_payload` 内容不同（没有串味）；
- `round_count` 与 `model_call_count` 一致（每轮一次模型调用）；
- 中断一次对话，`run_status` 应为 `INTERRUPTED` 而不是 `NORMAL`。
