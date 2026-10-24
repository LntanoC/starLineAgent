# 点踩 → 离线 bad case 分析链路 实施方案

> 目标：把 `t_message_feedback` 里 `vote = -1` 的记录，变成可人工判定根因、可追溯执行链路的 bad case 工单。
> 本方案不引入「点踩自动回流 Agent 记忆」，只做「信号收集 + 根因定位 + 人工决策」。

---

## 0. 现状盘点（已核对代码）

| 环节 | 现状 | 缺口 |
|---|---|---|
| 点踩采集 | ✅ `FeedbackButtons.tsx` → `chatService.ts` → `MessageFeedbackController` | 无 |
| 反馈落库 | ✅ MQ 异步 → `MessageFeedbackConsumer` → `t_message_feedback` | 无 |
| 回答现场 | ✅ `t_message`（content / thinking_content / sources / retrieved_chunks / message_status） | 无 |
| 执行链路 | ✅ `t_rag_trace_run` + `t_rag_trace_node`，已有查询接口 `RagTraceController` | **缺 message_id ↔ trace_id 直连** |
| 定时任务 | ✅ 已有 `@Scheduled` 范式（`KnowledgeDocumentScheduleJob`、`ScheduleLockManager`） | 无 |
| 人工看板 | ❌ 无 | 需新建 |
| bad case 台账表 | ❌ 无 | 需新建 |

**核心断点**：`t_message` 无 `trace_id`；`t_rag_trace_run` 无 `message_id`。目前只能靠 `conversation_id + 时间` 近似关联，多轮对话会错配。

**关键有利条件**：`StreamChatTraceRunner.run()` 在方法开头就生成 `traceId` 并写入 `RagTraceContext`（TTL，异步线程可继承）；而消息落库发生在 `JdbcConversationMemoryStore.append()`。因此**落库时刻 trace_id 一定可用**，补关联的成本极低。

---

## 阶段一：补断点（`message_id ↔ trace_id`）

### 1.1 数据库变更

`resources/database/schema_pg.sql`：

```sql
-- t_message 新增一列
ALTER TABLE t_message ADD COLUMN trace_id VARCHAR(64);
CREATE INDEX idx_message_trace ON t_message (trace_id);

-- t_rag_trace_run 新增一列（可选，便于反查）
ALTER TABLE t_rag_trace_run ADD COLUMN message_id VARCHAR(20);
CREATE INDEX idx_trace_run_message ON t_rag_trace_run (message_id);
```

> 若线上已有数据，需配套写一次性回填脚本：
> ```sql
> UPDATE t_message m
> SET trace_id = r.trace_id
> FROM t_rag_trace_run r
> WHERE r.conversation_id = m.conversation_id
>   AND m.role = 'assistant'
>   AND r.start_time BETWEEN m.create_time - INTERVAL '5 minutes' AND m.create_time + INTERVAL '5 minutes';
> ```
> 回填存在误配风险，建议只回填最近 7 天数据，并允许人工修正。

### 1.2 Java 改动

**`rag/dao/entity/ConversationMessageDO.java`** — 新增字段：

```java
/**
 * 本条消息所属的 RAG Trace 链路 ID，关联 t_rag_trace_run.trace_id
 * 仅 assistant 消息有值，用于 bad case 根因追溯
 */
private String traceId;
```

**`rag/dao/entity/ConversationMessageBO.java`** — 同步新增 `private String traceId;`

**`rag/core/memory/JdbcConversationMemoryStore.append()`** — 在构建 BO 时带上 traceId：

```java
ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
        .conversationId(conversationId)
        .userId(userId)
        // ... 现有字段
        .traceId(RagTraceContext.getTraceId())   // ← 新增
        .build();
```

**`rag/service/impl/ConversationMessageServiceImpl.addMessage()`** — 在转换为 DO 时透传 `traceId`。

> **注意**：`RagTraceContext.getTraceId()` 在非 trace 模式下（`traceProperties.isEnabled() == false`）返回 null，属正常降级，不影响主流程。

---

## 阶段二：坏因定位服务（BadCaseAnalyzer）

新增 `rag/feedback/badcasedetect/` 包（或 `rag/service/badcase/`），职责：**给定 messageId，产出一份可读的分析报告**。

### 2.1 输入输出定义

```java
public class BadCaseReport {
    private String messageId;
    private String conversationId;
    private String userId;
    private String traceId;          // 阶段一打通后可得
    private Integer vote;
    private String reason;            // 用户填的反馈原因
    private String comment;
    private String userQuestion;      // 上一条 user 消息 content
    private String assistantAnswer;   // 本条 assistant 消息 content
    private String thinkingContent;
    private String messageStatus;
    private List<SourceRef> sources;
    private List<GroundingChunk> retrievedChunks;
    private List<TraceNodeSummary> traceNodes;   // 扁平化节点列表
    private List<String> suspicions;  // 自动初步归因提示
}
```

`TraceNodeSummary`：`nodeId / parentNodeId / depth / nodeType / nodeName / status / durationMs / errorMessage`

### 2.2 归因启发式规则（自动初判，人工终判）

规则写在 `BadCaseAnalyzer` 中，按顺序命中，输出 `suspicions` 列表：

| 编号 | 判定条件 | 提示的根因 |
|---|---|---|
| R1 | `traceNodes` 中存在 `status=ERROR` | 执行链路报错，优先看 `errorMessage` |
| R2 | `messageStatus = INTERRUPTED` | 生成被中断（用户取消/超时） |
| R3 | `messageStatus = REJECTED` | 被限流拒绝，非质量问题 |
| R4 | `sources` 为空 或 `retrievedChunks` 为空 | **检索召回为空** → 查知识库覆盖度 / query 改写 |
| R5 | `sources` 非空但 `retrievedChunks` 命中文档与 `userQuestion` 主题不符 | **检索召回噪音** → 查 rerank / top_k / `t_query_term_mapping` |
| R6 | 存在 `nodeType=RETRIEVE` 节点且 `durationMs` > P95 | 检索性能问题（非质量） |
| R7 | 存在 `nodeType=LLM` 节点但 `sources` 有引用，而 answer 中无对应内容 | **幻觉/未忠实于检索结果** |
| R8 | answer 中出现"抱歉/我不知道"且 `sources` 非空 | 拒答但有依据 → prompt 约束过严 |
| R9 | answer 含明显截断（末尾无句号/结尾突兀） | 输出被截断 → max_tokens 或流式异常 |
| R10 | `traceNodes` 中出现 `nodeType=REWRITE` 且改写结果与原 query 语义漂移 | query 改写过度 |

### 2.3 核心实现要点

- 消息查询：`conversationMessageMapper.selectOne(id, userId, deleted=0)`
- user 问题：同 `conversation_id` 下、`role='user'`、且 `create_time < assistant.create_time` 的最近一条
- trace 查询：优先按 `t_message.trace_id` 查；若为空则**降级**用 `t_rag_trace_run.conversation_id + user_id + 时间窗口` 兜底，并在报告中标记 `traceMatchedByFallback = true`（提示人工该关联不可靠）
- 节点扁平化：`t_rag_trace_node` 按 `trace_id` 查出后，用 `parent_node_id` 构建树，再按 `depth, start_time` 摊平成列表

---

## 阶段三：定时扫描任务

### 3.1 参照现有范式

新建 `rag/feedback/job/BadCaseScanJob.java`，完全照抄 `KnowledgeDocumentScheduleJob` 的写法：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BadCaseScanJob {

    private final BadCaseMapper badCaseMapper;
    private final BadCaseAnalyzer badCaseAnalyzer;
    private final BadCaseScanProperties properties;   // 配置项
    private final Executor badCaseExecutor;

    @Scheduled(fixedDelayString = "${rag.feedback.scan-delay-ms:300000}",
               initialDelay = 60_000)
    public void scan() {
        // 1. 查最近 N 分钟内 vote=-1 且尚未建单的反馈
        // 2. 已有台账的跳过（幂等：按 message_id + user_id 唯一）
        // 3. 逐条调 badCaseAnalyzer 生成报告
        // 4. 写入 bad_case 表
        // 5. 失败只记日志，不中断整批
    }
}
```

### 3.2 多节点防重复

你项目是单实例部署，暂时不需要分布式锁。但为将来考虑，可复用 `ScheduleLockManager` 的思路，或直接依赖**唯一约束**保证幂等：

```sql
CONSTRAINT uk_bad_case_src UNIQUE (message_id, user_id)
```

### 3.3 配置项

```yaml
rag:
  feedback:
    bad-case:
      enabled: true
      scan-delay-ms: 300000        # 5 分钟扫一次
      lookback-minutes: 60         # 回看窗口
      batch-size: 50
```

---

## 阶段四：bad case 台账表

### 4.1 建表

```sql
CREATE TABLE t_bad_case (
    id                  VARCHAR(20)   NOT NULL PRIMARY KEY,
    message_id          VARCHAR(20)   NOT NULL,
    conversation_id     VARCHAR(20)   NOT NULL,
    user_id             VARCHAR(20)   NOT NULL,
    trace_id            VARCHAR(64),
    trace_matched_by_fallback SMALLINT NOT NULL DEFAULT 0,
    vote                SMALLINT      NOT NULL,
    reason              VARCHAR(255),
    comment             VARCHAR(1024),
    user_question       TEXT,
    assistant_answer    TEXT,
    thinking_content    TEXT,
    message_status      VARCHAR(16),
    sources             JSONB,
    retrieved_chunks    JSONB,
    trace_nodes         JSONB,
    suspicions          JSONB,
    -- 人工判定
    root_cause          VARCHAR(32),    -- RETRIEVAL_MISS / RETRIEVAL_NOISE / INTENT / HALLUCINATION / FORMAT / PERF / OTHER
    root_cause_detail   VARCHAR(1024),
    fix_action          VARCHAR(32),    -- MAPPING / INTENT_NODE / KNOWLEDGE_BASE / PROMPT / RERANK / NONE
    fix_ref             VARCHAR(128),   -- 被修改对象的 ID，如 mappingId / intentCode
    fix_note            VARCHAR(1024),
    review_status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING / REVIEWED / IGNORED / FIXED
    reviewer            VARCHAR(64),
    review_time         TIMESTAMP,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_bad_case_src UNIQUE (message_id, user_id)
);
CREATE INDEX idx_bad_case_status ON t_bad_case (review_status, create_time);
CREATE INDEX idx_bad_case_trace ON t_bad_case (trace_id);
```

### 4.2 `root_cause` 枚举（与 2.2 规则一一对应）

```
RETRIEVAL_MISS     检索召回为空
RETRIEVAL_NOISE    检索召回噪音
INTENT             意图识别错误
HALLUCINATION      幻觉/不忠实于检索结果
FORMAT             输出格式错误
PERF               性能问题（非质量）
OTHER              其他
```

### 4.3 `fix_action` 枚举（对应你已有的三张配置表）

```
MAPPING        改 t_query_term_mapping
INTENT_NODE    改 t_intent_node
KNOWLEDGE_BASE 改知识库文档/分块
PROMPT         改 prompt（t_intent_node.prompt_snippet / prompt_template）
RERANK         调整检索参数（top_k / rerank）
NONE           无需修改
```

---

## 阶段五：人工判定看板

### 5.1 后端接口（新建 `rag/feedback/controller/BadCaseController.java`）

```java
// 分页查询待判定 bad case
GET  /rag/bad-cases?reviewStatus=PENDING&rootCause=&page=&size=

// 单条详情（含完整报告）
GET  /rag/bad-cases/{id}

// 提交人工判定
PUT  /rag/bad-cases/{id}/review
Body: { rootCause, rootCauseDetail, fixAction, fixRef, fixNote, reviewStatus }

// 统计：各 root_cause 的数量分布（决定优先级）
GET  /rag/bad-cases/stats
```

### 5.2 前端页面（新建 `frontend/src/pages/admin/badcase/`）

建议 3 个组件：

1. **列表页** — 表格列：时间 / 用户问题（截断）/ 助手回答（截断）/ `reason` / 自动初判 `suspicions` / `root_cause` / 状态。支持按 `root_cause`、`review_status` 筛选。
2. **详情抽屉** — 上半部分：`user_question` vs `assistant_answer` 对比 + `sources`（可点开预览文档）+ `retrieved_chunks`；下半部分：**trace 节点树**（按 `depth` 缩进，显示 `nodeType` / `duration_ms` / `status`，ERROR 节点标红）。
3. **判定表单** — `root_cause` 下拉 + `fix_action` 下拉 + `fix_ref` 输入 + 备注。保存后状态置 `REVIEWED`。

> 前端可直接复用 `frontend/src/components/chat/SourcesButton.tsx` 的文档预览能力渲染 `sources`。

---

## 阶段六（可选）：把判定结果一键落到配置表

当 `fix_action = MAPPING` 且人工填了 `fix_ref` 时，提供"应用"按钮，直接调用已有的 `QueryTermMappingController` 创建/更新映射，而不是让人手动去管理页改。

| fix_action | 对应的现有 Controller |
|---|---|
| MAPPING | `QueryTermMappingController` |
| INTENT_NODE | `IntentNodeController`（若存在） |
| PROMPT | `IntentNodeController` 的 prompt 更新接口 |

这样闭环是：踩 → 扫描 → 判定 → 一键修复 → 回归验证。

---

## 实施顺序建议

```
阶段一（补断点）        ← 必须最先做，否则后面全是错的
   ↓
阶段二（Analyzer）+ 阶段四（台账表）   ← 核心
   ↓
阶段三（定时扫描）        ← 依赖前两步
   ↓
阶段五（看板）            ← 人工介入入口
   ↓
阶段六（一键修复）        ← 可选，最后做
```

**最小可用版本** = 阶段一 + 阶段二 + 阶段四 + 一个简单的列表接口。
先跑通「点踩 → 自动生成报告 → 人工看到根因线索」这条线，看板 UI 和一键修复可以后置。

---

## 风险与注意点

1. **trace_id 关联是全部工作的地基**。没做阶段一，阶段二只能拿到不可靠的时间匹配结果。
2. **RETRIEVE 节点的 `extra_data` 目前是空的**（`RagTraceAspect` 只写元信息）。若要精确分析"召回了什么"，需额外在检索节点把召回结果写进 `extra_data`。否则只能依赖 `t_message.retrieved_chunks`（注意：它按类注释**不参与模型回答上下文**，是给推荐追问用的，不能等同于真实 prompt 里的检索结果）。
3. **反馈质量低**。当前 `reason`/`comment` 前端不强制填写，大部分记录可能只有 `vote=-1`。建议前端在点踩时弹一个轻量原因选择（答非所问 / 信息过时 / 编造内容 / 格式错误 / 其他），成本很低但价值很高。
4. **数据量增长**。`t_bad_case` 会持续增长，建议加保留策略（如只保留 90 天，或 `review_status=FIXED` 的归档）。
5. **别在阶段二里引入 LLM 自动判定**。先用确定性规则产出 `suspicions`，人工终判。等积累了足够标注数据，再考虑训练或用模型做自动归类。
