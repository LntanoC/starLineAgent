# bad case 多入口改造技术方案（交付 Codex 执行）

> 目标：把 bad case 的唯一入口（用户点踩）扩展为四个入口——**点踩 / 系统异常 / 重复提问 / 评测失败**，让已写好的 R1–R10 规则真正能被触发。
>
> 依据：本方案所有文件路径、行号、约束均经本地源码实地核实（2026-10-24）。**未修改任何项目文件。**

---

## 0. 一句话结论

现有 `BadCaseRules` 的 10 条规则覆盖了报错、中断、限流、截断、召回为空等症状，但**唯一入口是"用户点踩"**（`BadCaseRepository.pendingFeedback` 的 SQL 硬条件 `WHERE f.vote=-1`）。而用户遇到报错时通常是直接关页面，不会点踩——**最典型的 bad case 永远进不来**。

改造核心：把「读点踩表」抽象成「读多来源候选」，用 `t_bad_case.source` 区分来源，其余分析链路（Analyzer → Rules → Repository.insert）完全复用。

---

## 1. 三个必须先解开的硬约束

这三处不改，任何新入口都插不进数据。

### 约束 1：`vote` 字段强制必须是 -1

`resources/database/migrations/20261024_bad_case.sql` 第 19 行：

```sql
vote SMALLINT NOT NULL CHECK (vote = -1),
```

异常建单/评测建单**没有用户投票**，无法满足这个约束。

**改造**：

```sql
ALTER TABLE t_bad_case DROP CONSTRAINT IF EXISTS t_bad_case_vote_check;
ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_vote_check CHECK (vote IN (-1, 0));
COMMENT ON COLUMN t_bad_case.vote IS '用户投票：-1=点踩，0=无用户投票（系统/重复/评测来源）';
```

### 约束 2：`insert` 的 SQL 内置了 `WHERE EXISTS` 点踩校验

`rag/.../feedback/badcase/BadCaseRepository.java` 第 54-68 行：

```sql
INSERT INTO t_bad_case (...)
SELECT ?,?,?,?,...
WHERE EXISTS (SELECT 1 FROM t_message_feedback f
              WHERE f.message_id=? AND f.user_id=? AND f.vote=-1 AND f.deleted=0)
ON CONFLICT (message_id,user_id) DO NOTHING
```

这个 `WHERE EXISTS` 是第二道闸门——即使 `vote` 约束放开了，非点踩来源依然插不进去。

**改造**：拆成两个方法，点踩来源保留原 SQL 不动（它是一层防误插保护），新增 `insertFromSource()` 不带 `WHERE EXISTS`：

```java
public boolean insertFromSource(BadCaseReport r) {
    return jdbc.update("""
        INSERT INTO t_bad_case
        (id,message_id,conversation_id,user_id,message_type,trace_id,trace_matched_by_fallback,
         trace_match_status,vote,reason,comment,user_question,assistant_answer,thinking_content,
         message_status,sources,retrieved_chunks,trace_nodes,suspicions,evidence_notes,
         message_create_time,source,source_detail)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb)
        ON CONFLICT (message_id,user_id) DO NOTHING
        """,
        IdWorker.getIdStr(), r.getMessageId(), r.getConversationId(), r.getUserId(),
        r.getMessageType(), r.getTraceId(), r.isTraceMatchedByFallback()?1:0,
        r.getTraceMatchStatus(), r.getVote()==null?0:r.getVote(), r.getReason(), r.getComment(),
        r.getUserQuestion(), r.getAssistantAnswer(), r.getThinkingContent(), r.getMessageStatus(),
        encode(r.getSources()), encode(r.getRetrievedChunks()), encode(r.getTraceNodes()),
        encode(r.getSuspicions()), encode(r.getEvidenceNotes()), r.getMessageCreateTime(),
        r.getSource(), encode(r.getSourceDetail())) > 0;
}
```

### 约束 3：`Feedback` record 没有来源概念

`BadCaseRepository` 第 40 行：

```java
public record Feedback(String messageId, String conversationId, String userId,
                       int vote, String reason, String comment) {}
```

**改造**：新增候选类型，保留原 `Feedback` 不动（点踩链路零改动），复用一个统一的候选记录：

```java
/** 多来源统一候选；source 决定后续注解与去重口径 */
public record Candidate(String messageId, String conversationId, String userId,
                        String source,           // FEEDBACK / SYSTEM / REPEAT / EVAL
                        Integer vote,            // FEEDBACK 时为 -1，其余为 0
                        String reason, String comment,
                        Map<String,Object> sourceDetail) {}   // 触发证据，落 source_detail
```

---

## 2. 表结构改造（一次性）

新增两列 + 一个索引：

```sql
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'FEEDBACK';
ALTER TABLE t_bad_case ADD COLUMN IF NOT EXISTS source_detail JSONB;

ALTER TABLE t_bad_case DROP CONSTRAINT IF EXISTS t_bad_case_source_check;
ALTER TABLE t_bad_case ADD CONSTRAINT t_bad_case_source_check
    CHECK (source IN ('FEEDBACK','SYSTEM','REPEAT','EVAL'));

CREATE INDEX IF NOT EXISTS idx_bad_case_source
    ON t_bad_case (source, review_status, create_time);

COMMENT ON COLUMN t_bad_case.source IS '线索来源：FEEDBACK=用户点踩，SYSTEM=系统异常，REPEAT=重复提问，EVAL=评测失败';
COMMENT ON COLUMN t_bad_case.source_detail IS '来源触发证据（异常类型/相似度/评测断言等），供人工复核';
```

> `DEFAULT 'FEEDBACK'` 保证历史数据自动归类，无需回填。

---

## 3. 入口 A：异常就建单（优先级最高）

### 3.1 为什么优先

R1（链路 ERROR）、R2（INTERRUPTED）、R3（REJECTED）描述的全是系统级失败——**机器 100% 知道**，却要求用户先点踩。这是最明显的错位，且改造零用户依赖。

### 3.2 插入点（3 处）

**插入点 1：Agent 流式异常** — `agent/.../service/handler/AgentStreamEventBridge.java`

第 218-233 行 `runHandle.fail(...)` 内，`log.error("Agent 流式会话异常...")` 之后、`settleAndPersistMessage(content, AgentMessageStatus.INTERRUPTED)` 之后：

```java
String messageId = settleAndPersistMessage(content, AgentMessageStatus.INTERRUPTED);
// 新增：异常即建单（异步，不阻塞收尾）
if (messageId != null) {
    badCaseIntake.submitSystemException(messageId, conversationId, userId,
            "STREAM_ERROR", throwable.getMessage());
}
```

**插入点 2：待确认落库失败** — 同文件第 368-375 行 `log.error("待确认消息落库失败...")`

**插入点 3：取消/中断收尾** — 同文件第 258 行 `settleAndPersistMessage(content, AgentMessageStatus.INTERRUPTED)`（用户主动取消，`source_detail.reason=CANCEL`，优先级降低，可选）

### 3.3 ⚠️ 实测修正：状态枚举里没有 ERROR/FAILED/REJECTED

**这是本方案最容易写错的地方，已实地核实：**

`agent/.../agent/enums/AgentMessageStatus.java` 只有 **3 个值**：

```java
NORMAL             // 正常完成
INTERRUPTED        // 用户中断，内容为已生成的部分
AWAITING_CONFIRM   // 挂起在写操作确认上（唯一非终态）
```

**没有** `ERROR` / `FAILED` / `REJECTED`。这带来三个后果：

1. **`message_status` 无法区分「用户主动取消」与「系统异常中断」**——两者都是 `INTERRUPTED`
2. **`BadCaseRules` 的 R3（`"REJECTED".equals(...)`）在 Agent 侧是死规则**，永远不会命中
3. 真正的异常信号藏在 **`blocks` JSONB** 里

### 3.4 真实可用的异常信号（三个）

| 信号 | 位置 | 含义 |
|---|---|---|
| **ERROR 块** | `t_agent_message.blocks` 内含 `kind='error'` | 系统异常输出的中断提示块（`AgentBlock.KIND_ERROR`） |
| **工具失败** | `blocks` 内 `kind='tool'` 且 `status='failed'` | `AgentToolStatus.FAILED`（"执行报错"） |
| **工具被拒** | `blocks` 内 `status='denied'` | `AgentToolStatus.DENIED`（"被拒绝，框架直接合成结果，工具本体没被调用"） |

`AgentToolStatus` 完整取值：`pending / running / awaiting / done / failed / denied / interrupted`

### 3.5 修正后的扫描 SQL（用 blocks JSONB 判定）

```sql
-- 系统异常候选：有 ERROR 块，或存在 failed/denied 的工具块
SELECT m.id, m.conversation_id, m.user_id, m.message_status, m.create_time,
       m.blocks
FROM t_agent_message m
WHERE m.role = 'assistant'
  AND m.deleted = 0
  AND m.blocks IS NOT NULL
  AND m.create_time >= ?                       -- lookback
  AND (
        m.blocks @> '[{"kind":"error"}]'::jsonb
     OR m.blocks @> '[{"status":"failed"}]'::jsonb
     OR m.blocks @> '[{"status":"denied"}]'::jsonb
      )
  AND NOT EXISTS (
      SELECT 1 FROM t_bad_case b
      WHERE b.message_id = m.id AND b.user_id = m.user_id
  )
ORDER BY m.create_time
LIMIT ?;
```

对应 `BadCaseRepository` 新增：

```java
public List<Candidate> pendingSystemFailures(Date since, int limit) {
    return jdbc.query("""
        SELECT m.id, m.conversation_id, m.user_id, m.message_status, m.create_time, m.blocks
        FROM t_agent_message m
        WHERE m.role='assistant' AND m.deleted=0 AND m.blocks IS NOT NULL
          AND (?::timestamp IS NULL OR m.create_time>=?)
          AND (
                m.blocks @> '[{"kind":"error"}]'::jsonb
             OR m.blocks @> '[{"status":"failed"}]'::jsonb
             OR m.blocks @> '[{"status":"denied"}]'::jsonb
              )
          AND NOT EXISTS (SELECT 1 FROM t_bad_case b
                          WHERE b.message_id=m.id AND b.user_id=m.user_id)
        ORDER BY m.create_time LIMIT ?
        """,
        (rs, n) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3),
                "SYSTEM", 0, null, null,
                Map.of("trigger", "BLOCKS_ANOMALY",
                       "messageStatus", rs.getString(4),
                       "messageCreateTime", String.valueOf(rs.getTimestamp(5)),
                       "signal", detectSignal(rs.getString(6)))),
        since == null ? null : new Timestamp(since.getTime()), since, limit);
}

/** 从 blocks JSON 里提取首个异常信号，供 source_detail 记录 */
private String detectSignal(String blocksJson) {
    if (blocksJson == null) return "UNKNOWN";
    if (blocksJson.contains("\"kind\":\"error\"")) return "ERROR_BLOCK";
    if (blocksJson.contains("\"status\":\"failed\"")) return "TOOL_FAILED";
    if (blocksJson.contains("\"status\":\"denied\"")) return "TOOL_DENIED";
    return "UNKNOWN";
}
```

### 3.6 `INTERRUPTED` 的语义区分（重要）

`message_status='INTERRUPTED'` 同时覆盖「用户主动取消」和「流式异常」。要区分，**必须在插入点直接知道原因**（3.2 的插入点 1 传 `STREAM_ERROR`，插入点 3 传 `USER_CANCEL`），并写入 `source_detail.reason`。

**不要在 SQL 里扫 `message_status='INTERRUPTED'`**——那会把大量用户主动取消也建成单，产生噪声。走插入点精确传入原因。

### 3.7 R3 死规则的处置建议

`BadCaseRules` 第 43 行：

```java
if("REJECTED".equals(r.getMessageStatus())) result.add("R3 请求被拒绝，优先核查限流及准入条件。");
```

Agent 侧永远不会出现 `REJECTED`，这条规则**当前是死代码**。两个选择（本轮建议选 a）：

- **a. 保留但加注释**：标注「仅对 RAG 侧有效，Agent 侧无此状态」，等 workflow 下线后一并清理
- **b. 改判 `denied`**：把 `TOOL_DENIED` 映射成 R3，但这与"限流/准入"语义不符，不建议混用

### 3.4 规则适配

**关键问题**：`BadCaseRules.evaluate()` 里 R2/R3 依赖 `messageStatus`，但 `AgentMessageFeedbackService` 查询时用了 `.eq(messageStatus, NORMAL)` —— 这会导致**异常来源的消息读不到**。

`agent/.../service/AgentMessageFeedbackService.java` 第 61 行：

```java
.eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.NORMAL.name())
```

**改造**：`AgentBadCaseMessageProvider.find()` 本来就没这个过滤（它是按 id+userId+role 查），所以异常来源直接调 Provider 可以拿到数据。但要确认 `AgentMessageFeedbackService` 的 NORMAL 过滤不影响 Provider 路径——两者是独立查询，**Provider 无此问题**。

---

## 4. 入口 B：重复提问就建单

### 4.1 判定逻辑

同一会话内，短期内出现**高度相似**的用户提问 → 说明第一轮没答到点上。

**判定条件（三选二即可触发）**：

| 信号 | 判定 | 说明 |
|---|---|---|
| 词面相似度 | Jaccard > 0.85 | 中文可用字符二元组，无需分词依赖 |
| 时间间隔 | < 10 分钟 | 排除"隔天再问" |
| 前一轮未调工具 | 前一轮 blocks 无 tool | 强调"没查就答" |

### 4.2 SQL（自连接，一次查完）

```sql
-- 重复提问候选：同会话内相邻两条 user 消息高度相似
SELECT
    a.id            AS first_user_msg_id,
    a.conversation_id,
    a.user_id,
    a.content       AS first_question,
    b.id            AS repeat_user_msg_id,
    b.content       AS repeat_question,
    b.create_time
FROM t_agent_message a
JOIN t_agent_message b
  ON b.conversation_id = a.conversation_id
 AND b.user_id = a.user_id
 AND b.role = 'user'
 AND b.deleted = 0
 AND b.create_time > a.create_time
 AND b.create_time <= a.create_time + INTERVAL '10 minutes'
WHERE a.role = 'user' AND a.deleted = 0
  AND a.create_time >= ?                       -- lookback
  AND NOT EXISTS (SELECT 1 FROM t_bad_case c WHERE c.message_id = a.id AND c.user_id = a.user_id)
ORDER BY b.create_time
LIMIT ?;
```

> **相似度在哪算**：SQL 只做「时间窗口 + 同会话」的粗筛，**相似度在 Java 里算**（避免 pg_trgm 依赖与中文分词问题）。粗筛结果通常很少（同会话 10 分钟内可能只有 2-5 条），Java 侧计算成本可忽略。

### 4.3 Java 侧相似度实现

```java
/** 字符二元组 Jaccard；中文无需分词，对"换个说法再问"也有效 */
private static double similarity(String a, String b) {
    if (a == null || b == null) return 0;
    Set<String> sa = bigrams(a.trim()), sb = bigrams(b.trim());
    if (sa.isEmpty() || sb.isEmpty()) return 0;
    Set<String> inter = new HashSet<>(sa);
    inter.retainAll(sb);
    Set<String> union = new HashSet<>(sa);
    union.addAll(sb);
    return (double) inter.size() / union.size();
}

private static Set<String> bigrams(String s) {
    Set<String> out = new HashSet<>();
    for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
    return out;
}
```

### 4.4 绑定哪条消息

**必须绑"被重复的那条 assistant 回答"**，而不是 user 消息：

```java
// repeat 检测拿到 first_user_msg_id 后，找它对应的 assistant 回复
String assistantId = messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
    .eq(AgentMessageDO::getReplyToMessageId, firstUserMsgId)
    .eq(AgentMessageDO::getRole, "assistant")
    .eq(AgentMessageDO::getDeleted, 0)
    .orderByAsc(AgentMessageDO::getCreateTime).last("LIMIT 1")).getId();
```

因为 `t_bad_case.message_id` 语义是「出问题的那条消息」，`suspicions` 也是对回答做判定的。

`source_detail` 记录证据：

```json
{ "trigger": "REPEAT_QUESTION",
  "similarity": 0.92,
  "intervalSeconds": 180,
  "repeatQuestion": "林俊杰的生日是什么时候",
  "firstQuestion": "那林俊杰生日是什么时候" }
```

---

## 5. 入口 C：评测失败就建单

### 5.1 与现有评测框架的衔接

`resources/regression/agent-memory-evaluation/` 产出 `turns.csv` / `turns.jsonl`，其中失败的 turn 有明确记录。

**方案**：评测跑批结束后，把 `passed=false` 的 turn 批量提交到新接口：

```
POST /rag/bad-cases/intake/eval
Body: { "runId": "...", "cases": [
  { "question": "...", "answer": "...", "failedChecks": [...], "conversationId": "..." }
]}
```

### 5.2 实现要点

评测产生的会话**不在生产用户数据里**（评测用独立账号），所以：

- `user_id` 用评测账号 ID，天然与真实用户隔离
- `source = 'EVAL'`，`source_detail` 记录 `runId` + 失败的断言
- **不需要** `t_agent_message` 有对应记录（评测跑完消息可能已清理），直接构造 `BadCaseReport`：

```java
BadCaseReport.builder()
    .messageId("eval:" + runId + ":" + caseId + ":" + turnId)   // 合成 ID，满足长度限制
    .conversationId(evalConversationId)
    .userId(evalUserId)
    .messageType("AGENT")
    .messageStatus("EVAL_FAILED")
    .userQuestion(turn.question())
    .assistantAnswer(turn.answer())
    .sources(List.of()).retrievedChunks(List.of())
    .evidenceNotes(List.of("来源：评测 " + runId + "；失败断言：" + String.join(" / ", failedChecks)))
    .source("EVAL")
    .sourceDetail(Map.of("runId", runId, "caseId", caseId, "turnId", turnId,
                         "failedChecks", failedChecks))
    .build();
```

> ⚠️ `messageId` 是主键 + 唯一键的一部分，合成 ID 要保证 ≤ 20 字符（`VARCHAR(20)`）且稳定（同一 turn 重复跑不产生多条）。建议 `hash(runId+caseId+turnId)` 取 18 位。

### 5.3 前置依赖

这条入口依赖**评测体系先跑起来**。当前状态：
- `resources/regression/agent-memory-evaluation/` 框架完整，可直接用
- `rag/eval/EvalController`（`GET /rag/eval`）已有检索层接口，但缺评测集

**所以本入口排在最后实施**，前两个入口不依赖它。

---

## 6. 防刷屏：三道降噪闸门

新入口一开，最大风险是**bad case 表被瞬间灌满**（比如一次服务抖动产生 200 条 INTERRUPTED）。必须加闸门。

### 闸门 1：同会话同来源限流

```sql
-- 建单前检查：同一会话 1 小时内同类来源不超过 N 条
SELECT count(*) FROM t_bad_case
WHERE conversation_id = ? AND source = ?
  AND create_time >= now() - interval '1 hour';
```

超过阈值（建议 SYSTEM=3、REPEAT=2）则跳过，只记日志。

### 闸门 2：全局日配额

`BadCaseProperties` 新增：

```java
private int dailyIntakeLimit = 500;      // 每日新建单总量上限
private int systemIntakeLimit = 200;     // 其中 SYSTEM 来源上限
private int repeatSimilarityThreshold = 85;  // 重复提问相似度阈值（百分数）
```

### 闸门 3：优先级队列（超限时丢低价值）

超限时按优先级丢弃：`FEEDBACK > SYSTEM(ERROR) > SYSTEM(INTERRUPTED) > REPEAT > EVAL`

即：**用户主动点踩的永远不丢**，先丢评测和重复提问。

---

## 7. 代码改动清单

### 7.1 数据库

```
resources/database/migrations/20261025_bad_case_intake.sql   （新建）
  - vote 约束放宽
  - 新增 source / source_detail 两列
  - 新增 source CHECK 约束 + 索引
```

### 7.2 rag 模块

| 文件 | 改动 |
|---|---|
| `feedback/badcase/BadCaseRepository.java` | 新增 `Candidate` record、`insertFromSource()`、`pendingSystemFailures()`、`pendingRepeats()`、`countByConversationAndSource()` |
| `feedback/badcase/BadCaseService.java` | `scan()` 扩展为四来源；新增 `intakeFromSource(Candidate)`；新增 `submitSystemException()` / `submitRepeat()` / `submitEval()` |
| `feedback/badcase/BadCaseProperties.java` | 新增 `dailyIntakeLimit` / `systemIntakeLimit` / `repeatSimilarityThreshold` 等 |
| `feedback/badcase/BadCaseReport.java` | 新增 `source` / `sourceDetail` 字段 |
| `feedback/badcase/BadCaseAnalyzer.java` | `analyze()` 重载一个接收 `Candidate` 的版本；**同时修 `findFirst()` 空集合保护** |
| `feedback/badcase/BadCaseMessageProvider.java` | 不变（接口已足够） |
| `feedback/badcase/BadCaseRules.java` | 按 `source` 调整提示语（如 SYSTEM 来源直接标注「系统异常，无需查证据」） |
| `feedback/controller/BadCaseController.java` | 新增 `POST /rag/bad-cases/intake/eval` |
| `feedback/job/BadCaseScanJob.java` | `scan()` 里串行调用四来源；新增 `@Scheduled` 的 `intakeSystem()` / `intakeRepeat()`（或复用现有 5 分钟任务） |

### 7.3 agent 模块

| 文件 | 改动 |
|---|---|
| `service/handler/AgentStreamEventBridge.java` | 第 232 行后（`STREAM_ERROR`）、第 258 行后（`USER_CANCEL`，可选）、第 371 行后（`PERSIST_FAILED`）调用 `badCaseIntake.submitSystemException(...)` |
| `agent/enums/AgentMessageStatus.java` | **不改**（只有 NORMAL/INTERRUPTED/AWAITING_CONFIRM 三值，异常原因靠 `source_detail.reason` 传） |

> **重要设计约束**：`AgentStreamEventBridge` 是流式收尾的关键路径，bad case 建单**必须异步且吞异常**，绝不能因为它失败而影响用户收尾。建议用现有线程池或 `@Async`，且超时保护。

### 7.4 前端

| 文件 | 改动 |
|---|---|
| `pages/admin/badcase/BadCasePage.tsx` | 列表增加「来源」列与筛选（FEEDBACK/SYSTEM/REPEAT/EVAL） |
| `pages/admin/badcase/BadCaseDetailDialog.tsx` | 展示 `source_detail` 证据（相似度、异常类型、失败断言） |
| `services/badCaseService.ts` | 查询参数增加 `source` |

---

## 8. 实施顺序（4 阶段，每阶段独立可验证）

**阶段 1：数据库 + 点踩链路零回归**（0.5 天）
- 执行迁移脚本
- `insert()` 保留原样，确认点踩链路完全不受影响
- 验证：手动点踩 → bad case 正常生成，`source='FEEDBACK'`

**阶段 2：异常入口**（1 天）
- `pendingSystemFailures()` + `submitSystemException()`
- 定时扫描先只跑 SQL（不依赖插入点），验证能捞到 INTERRUPTED 记录
- 再加流式插入点（异步）
- 验证：手动制造超时 → 自动出现 `source='SYSTEM'` 的记录

**阶段 3：重复提问入口**（1 天）
- `pendingRepeats()` + Java 相似度
- 验证：用你贴的那段对话（生日问两遍）造数据 → 能建单

**阶段 4：评测入口 + 防刷屏闸门**（1 天）
- `POST /rag/bad-cases/intake/eval`
- 三道闸门
- 验证：跑一次评测，失败 turn 能落库

---

## 9. 风险与注意

1. **`vote` 约束改动会影响 check 约束名**：PostgreSQL 自动生成的约束名可能是 `t_bad_case_vote_check`，需先 `\d t_bad_case` 确认实际名称，再写 `DROP CONSTRAINT`。
2. **`messageId` 长度限制**：`VARCHAR(20)`，异常/重复来源用的都是真实消息 ID（雪花 19 位）没问题；评测来源的合成 ID 必须 ≤20。
3. **`uk_bad_case_src UNIQUE (message_id, user_id)`**：天然做去重。但要确认——**同一条消息既被点踩又系统异常**时，只有一条记录，`source` 会是先到者。建议 `insertFromSource` 加 `ON CONFLICT ... DO UPDATE SET source=...` 做优先级升级（FEEDBACK 优先级最高）。
4. **`AgentStreamEventBridge` 不能阻塞**：bad case 建单必须异步 + 吞异常，这是硬要求。
5. **`BadCaseAnalyzer.findFirst()` 顺序耦合**：删掉 `RagBadCaseMessageProvider`（workflow 下线方案里已列）后，只剩 `AgentBadCaseMessageProvider`，此处 `orElseGet` 兜底逻辑仍生效，但要确认 `providers` 非空。
6. **告警噪声**：异常入口上线初期可能产生大量记录，建议先用 `BadCaseProperties.enabled=false` 只跑日志观察一周，再真正建单。
7. **`ERROR`/`FAILED`/`REJECTED` 状态不存在**（见 3.3）：任何按 `message_status IN (...)` 扫描的写法都是错的，必须用 `blocks` JSONB 判定。
8. **`blocks @>` 查询性能**：`t_agent_message` 数据量小时无影响；数据量大后需加 GIN 索引：
   ```sql
   CREATE INDEX IF NOT EXISTS idx_agent_message_blocks ON t_agent_message USING gin (blocks);
   ```
   建议在阶段 2 就加上，成本很低。

---

## 10. 验证清单

改造完成后逐项确认：

- [ ] 手动点踩 → 生成 `source=FEEDBACK` 记录，`suspicions` 有内容
- [ ] 制造流式超时 → 生成 `source=SYSTEM` 记录，`source_detail` 有异常信息
- [ ] 同一问题连问两遍（10 分钟内）→ 生成 `source=REPEAT` 记录，`source_detail.similarity > 0.85`
- [ ] 评测失败 turn → 生成 `source=EVAL` 记录
- [ ] 同会话 1 小时内制造 5 次异常 → 只建 3 条（闸门生效）
- [ ] 前端可按来源筛选，详情能看到 `source_detail`
- [ ] 点踩链路回归：原有用例全部通过，无行为变化
