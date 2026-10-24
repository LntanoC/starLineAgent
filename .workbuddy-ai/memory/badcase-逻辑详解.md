# BadCase 逻辑详解（结合代码 / 数据库 / 真实案例）

> 本文把「用户点踩」到「形成可分析台账」的全过程拆开讲，每一环都对应具体方法、
> 具体表和真实数据。示例用的是你库里那条真实的点踩记录。

---

## 零、一句话总览

点踩只是一个**信号**，不是 bad case 本身。
系统真正做的事是：**捕获这个信号 → 回到现场取证 → 用确定性规则列出可疑点 → 存成台账 → 等人判定**。

它的核心设计哲学是：**机器只提供线索，人负责定结论**。

---

## 一、四张表的关系

```
t_message_feedback          触发源：谁踩了哪条消息，什么原因
        │  扫描（NOT EXISTS 过滤已建单的）
        ▼
t_bad_case                  台账：现场快照 + 线索 + 人工判定
        │
        ├──────────────► t_agent_message（engine=agent 时读这里）
        │                content / thinking_content / blocks / message_status
        │
        └──────────────► t_message（engine=workflow 时读这里）
                         content / thinking_content / sources / retrieved_chunks / trace_id
                                        │
                                        └──► t_rag_trace_run + t_rag_trace_node
                                             仅 RAG 侧有链路树可挂
```

**关键点：`t_bad_case` 存的不是指针，是快照。**
`user_question`、`assistant_answer`、`thinking_content`、`sources`、`trace_nodes` 全部是反馈那一刻的副本。
原因很简单：原消息后来可能被删除或修改，而 bad case 是要长期复盘的材料，不能因为原数据没了就变成空壳。

---

## 二、六步链路逐层拆解

### 第 1 步：用户点踩

**入口** `AgentMessageFeedbackController:42`

```java
@PostMapping("/agent/v1/messages/{messageId}/feedback")
public Result<Void> submitFeedback(@PathVariable String messageId, ...) {
    feedbackService.submitDislike(messageId, UserContext.getUserId(), request);
}
```

**校验** `AgentMessageFeedbackService.submitDislike`（第 45-66 行）非常严格，四道关：

| 校验 | 代码 | 为什么 |
|---|---|---|
| 必须有登录用户 | `!StringUtils.hasText(userId)` → 抛异常 | 反馈要能追到人 |
| 只接受点踩 | `request.getVote() != -1` → 抛异常 | 该接口只服务 bad case，点赞走别的路径 |
| 只能踩自己的 | 查询条件带 `.eq(getUserId, userId)` | 防止踩别人的消息 |
| **只能踩已完成的** | `.eq(getMessageStatus, NORMAL)` | 这是最关键一条 |

第四条为什么重要？因为 `INTERRUPTED`（用户自己中断）和 `AWAITING_CONFIRM`（挂起等确认）的消息**不该算 bad case**——前者是用户主动放弃，后者流程还没走完。**在入口处就挡掉，比后期在规则里过滤更省事。**

**注意**：这个校验也意味着——**一旦消息状态不是 NORMAL，用户连踩都踩不了**。所以规则里的 R2（INTERRUPTED）/ R3（REJECTED）在 Agent 侧**永远不会被触发**，因为它们对应的消息根本进不了反馈表。这是个逻辑闭环上的遗留问题，后面「缺陷」一节会讲。

**写入** `t_message_feedback`，字段：`message_id / conversation_id / user_id / vote / reason / comment`。

**⚠️ 这里有个语义问题**：Agent 侧复用了 RAG 的 `MessageFeedbackDO` 和 `MessageFeedbackMapper`，写进同一张 `t_message_feedback`。
表名和字段都属于 RAG 域，但实际存的是 `t_agent_message` 的 id。**从数据库层面无法区分这条反馈来自哪套引擎**——只能靠扫描时两个 provider 依次去试。

---

### 第 2 步：定时扫描

**调度** `BadCaseScanJob`

```java
@Scheduled(fixedDelayString="${rag.feedback.bad-case.scan-delay-ms:300000}", initialDelay=60000)
public void scan() { run(false); }          // 每 5 分钟，看最近 60 分钟

@Scheduled(fixedDelay=3600000, initialDelay=90000)
public void catchUp() { run(true); }        // 每小时，includeOlder=true 扫全部历史

@Scheduled(fixedDelay=86400000, initialDelay=120000)
public void archive() { ... }               // 每天，归档已处理且超期的
```

**为什么要有两个扫描任务？**
`scan()` 只看 60 分钟窗口（`lookback-minutes: 60`），效率高但会漏——如果服务停了两小时，窗口外的反馈就没人管。
所以 `catchUp()` 每小时不带时间限制地补扫一次。这是「快速响应 + 兜底补齐」的双轨设计。

```java
private void run(boolean includeOlder) {
    if (!properties.isEnabled()) return;              // 开关
    try {
        var result = service.scan(includeOlder);
        if (result.created() > 0 || result.failed() > 0) log.info("bad case 扫描完成: {}", result);
    } catch (Exception e) {
        log.warn("bad case 扫描批次失败，将在下一轮重试", e);   // 失败不抛，下轮重试
    }
}
```

**取待处理数据** `BadCaseRepository.pendingFeedback`（第 43-52 行）

```sql
SELECT f.message_id, f.conversation_id, f.user_id, f.vote, f.reason, f.comment
FROM t_message_feedback f
WHERE f.vote = -1 AND f.deleted = 0
  AND (?::timestamp IS NULL OR f.update_time >= ?)          -- 时间窗口，null 即全量
  AND NOT EXISTS (SELECT 1 FROM t_bad_case b                -- 已经建过单的跳过
                  WHERE b.message_id = f.message_id AND b.user_id = f.user_id)
ORDER BY f.update_time, f.id
LIMIT ?
```

`NOT EXISTS` 这一句是幂等性的第一层。没有它，每 5 分钟扫描都会把历史反馈重新处理一遍。

**并发保护** `BadCaseService.scan`

```java
if (!scanning.compareAndSet(false, true)) return new ScanResult(0,0,0,true);  // 已有实例在跑
```

`AtomicBoolean` 防止 scan 和 catchUp 撞车。**注意这只是单进程内的保护**，多实例部署时每个实例都会各扫一遍（有唯一键兜底不会重复建单，但会浪费资源）。

---

### 第 3 步：回到现场取证

**核心** `BadCaseAnalyzer.analyze`（第 37-49 行）

```java
BadCaseReport r = providers.stream()
        .map(p -> p.find(feedback.messageId(), feedback.userId()))
        .flatMap(Optional::stream)
        .findFirst()                                    // ← 第一个命中的生效
        .orElseGet(() -> BadCaseReport.builder()
                .messageType("UNKNOWN").messageStatus("MISSING")
                .evidenceNotes(List.of("原消息已删除或不可访问，未读取其他用户的消息。"))
                .build());
```

**这段代码是整套系统的枢纽**，有两个要点：

**要点一：多态取数。** 两个 provider 都实现 `BadCaseMessageProvider` 接口，各自去自己的表找：

| Provider | 注册条件 | 读哪张表 | 拿什么 |
|---|---|---|---|
| `AgentBadCaseMessageProvider` | `@ConditionalOnAgentEngine` | `t_agent_message` | content + thinking + **blocks** |
| `RagBadCaseMessageProvider` | 无条件 `@Component` | `t_message` | content + thinking + sources + retrievedChunks + traceId |

**要点二：`findFirst()` 意味着顺序依赖。** 两个 provider 都被 Spring 注入到 `List` 里，谁先谁后由注入顺序决定。在 `engine=agent` 下，`RagBadCaseMessageProvider` 依然注册着——如果它先执行且抛异常，`analyze` 会整个失败。

**Agent 侧如何把 blocks 转成节点树** `AgentBadCaseMessageProvider`（第 59-78 行）

```java
List<TraceNodeSummary> nodes = new ArrayList<>();
String root = "agent-message:" + id;
// 根节点：整条消息
nodes.add(new TraceNodeSummary(root, null, 0, "AGENT", "Agent 回答现场",
        "NORMAL".equals(m.getMessageStatus()) ? "SUCCESS" : m.getMessageStatus(),
        m.getDurationMs(), null, null, null));

// 逐块转成子节点
for (AgentBlock block : m.getBlocks()) {
    String kind = block.getKind();
    String type = "tool".equals(kind) ? "TOOL"
                : ("error".equals(kind) ? "ERROR"
                : ("confirm".equals(kind) ? "CONFIRM" : "LLM"));
    String status = "failed".equals(block.getStatus()) || "error".equals(kind) ? "ERROR"
                  : ("interrupted".equals(block.getStatus()) ? "INTERRUPTED" : "SUCCESS");
    ...
}
```

它刻意做了一件事：**把 blocks 伪装成和 RAG 链路一样的 `TraceNodeSummary` 结构**，这样前端和规则层可以统一处理，不用写两套。代码注释明确写了「不把它伪装成 RAG Trace」——意思是结构对齐，但 `messageType` 打 `AGENT` 标记，语义上不混淆。

**RAG 侧如何挂链路** `BadCaseAnalyzer.attachRagTrace`（第 51-94 行）

只有 `messageType=RAG` 才走这里（第 45 行 `if("RAG".equals(...))`）。三步：

1. 有 `traceId` → 直接按 `trace_id` 查 `t_rag_trace_run`，`traceMatchStatus=DIRECT`；
2. 没 traceId → 按「同用户 + 同会话 + 前后 5 分钟窗口」找候选：
   - 找到 1 个 → `traceMatchStatus=TIME_CANDIDATE`，且**在 evidenceNotes 里明确写「可能错配；未回写消息关联」**；
   - 找到多个 → `traceMatchStatus=AMBIGUOUS`，**不自动选**，留给人工；
3. 一个都没有 → `MISSING`，并记录「缺少节点不能推断模型没有执行检索」——**这句话很关键，它防止了「没数据 = 没问题」的误判**。

---

### 第 4 步：跑规则出线索

`BadCaseRules.evaluate`（第 35-72 行）。这是整套系统最值得看的地方——**它输出的不是结论，是「待核查线索」**。

十条规则分三类：

**A 类：系统状态类（不依赖 RAG）**

| 规则 | 条件 | 输出 |
|---|---|---|
| R1 | 存在 `status=ERROR` 的节点 | 执行链路存在错误，优先核对错误节点 |
| R2 | `message_status=INTERRUPTED` | 生成被中断，核查用户取消或超时 |
| R3 | `message_status=REJECTED` | 请求被拒绝，核查限流及准入 |

**B 类：RAG 专属（都带 `rag` 前置条件）**

| 规则 | 条件 | 输出 |
|---|---|---|
| R4 | `rag && retrieve && (!sources \|\| !chunks)` | 检索链路在但来源/grounding 为空，疑似召回为空 |
| R5 | `rag && sources && 证据≥80字 && 词面重合<0.08` | 疑似召回噪音 |
| R7 | `rag && sources && 回答≥80字 && 有LLM节点 && 重合<0.06` | 需人工核对忠实性 |

**C 类：通用启发式**

| 规则 | 条件 | 输出 |
|---|---|---|
| R6 | RETRIEVE 节点耗时 > P95 | 检索性能问题 |
| R8 | `sources非空 && 回答含拒答措辞` | 有来源却拒答，查依据是否足够 |
| R9 | 状态 NORMAL && 回答≥200字 && 末尾无结束符 | 疑似截断（**弱信号**） |
| R10 | REWRITE 节点有 extraData 且与原问题重合<0.1 | 疑似语义漂移 |

**每条规则都自带免责声明**，这是设计上最克制的地方：

```java
result.add("R5 问题与来源摘要的词面重合较低，疑似召回噪音；词面比较不能判定主题是否相关。");
result.add("R7 回答与来源摘要重合较低，需人工核对忠实性；摘要不代表模型完整上下文，不能据此认定幻觉。");
result.add("R9 回答末尾缺少常见结束标记，存在截断弱信号，请结合 token 上限与流式日志核查。");
```

**兜底更值得注意**（第 67 行）：

```java
if (result.isEmpty()) {
    result.add("未命中可用的确定性异常线索，请人工检查回答与证据；这不表示回答正确。");
}
```

**「这不表示回答正确」** 这七个字，是这套系统区别于"自动评分玩具"的分水岭。它明确承认自己的局限。

**证据补充**（第 68-70 行）会往 `evidenceNotes` 里追加「为什么某条规则没跑成」：

```java
if (rag && !retrieve) r.getEvidenceNotes().add("未记录 RETRIEVE 节点，无法据此判断是否召回为空。");
if (有REWRITE节点但extraData为空) r.getEvidenceNotes().add("改写节点缺少结果正文，R10 无法完成语义漂移检查。");
```

这是很成熟的做法——**不只报告"发现了什么"，还报告"哪些没查成、为什么"**，避免使用者误以为"没报问题就是没问题"。

---

### 第 5 步：幂等落台账

`BadCaseRepository.insert`（第 54-68 行）

```sql
INSERT INTO t_bad_case
(id, message_id, ..., suspicions, evidence_notes, message_create_time)
SELECT ?, ?, ..., ?::jsonb, ?::jsonb, ?
WHERE EXISTS (SELECT 1 FROM t_message_feedback f
              WHERE f.message_id = ? AND f.user_id = ?
                AND f.vote = -1 AND f.deleted = 0)      -- ← 第二层幂等
ON CONFLICT (message_id, user_id) DO NOTHING            -- ← 第三层幂等
```

**三层幂等的分工**：

| 层 | 位置 | 防什么 |
|---|---|---|
| 一 | `pendingFeedback` 的 `NOT EXISTS` | 重复扫描历史反馈 |
| 二 | `insert` 的 `WHERE EXISTS` | **用户点了踩又取消**，扫描中途撤销，不该建单 |
| 三 | `ON CONFLICT DO NOTHING` | 并发写入（多实例同时扫到同一条） |

第二层是最容易被忽略的——大多数实现只做第一层和第三层。它防的是「扫描已经开始、SQL 已经拼好、但在真正 insert 之前用户撤销了点踩」这个窗口。**如果不加，会产生一条没有对应反馈的孤儿 bad case。**

`INSERT ... SELECT ... WHERE EXISTS` 这种写法很巧：把条件判断放进 SQL 里，避免「先查后写」的竞态。

---

### 第 6 步：人工判定与修复

**判定** `BadCaseService.review` + `BadCaseRepository.review`

枚举值（`BadCaseService` 第 31-33 行）：

```java
ROOT_CAUSES = {RETRIEVAL_MISS, RETRIEVAL_NOISE, INTENT, HALLUCINATION, FORMAT, PERF, OTHER}
FIX_ACTIONS = {MAPPING, INTENT_NODE, KNOWLEDGE_BASE, PROMPT, RERANK, NONE}
STATUSES    = {PENDING, REVIEWED, IGNORED, FIXED}
```

校验逻辑（第 65-75 行）：

```java
if (r == null || r.revision() == null || !STATUSES.contains(r.reviewStatus()))
    throw new ClientException("请提供有效的状态和版本号");
if (Set.of("REVIEWED","FIXED").contains(r.reviewStatus()) && r.rootCause() == null)
    throw new ClientException("已判定或已修复记录必须选择根因");   // ← 强制填根因
```

**乐观锁**（`BadCaseRepository.review`）：

```sql
UPDATE t_bad_case SET ..., revision = revision + 1
WHERE id = ? AND revision = ? AND deleted = 0
```

返回 0 行就抛「该记录已被更新，请刷新后重新判定」。两个人同时打开同一条 bad case 时，后提交的会失败而不是覆盖。

**修复落地** `BadCaseRepairService.applyMapping` —— 这是唯一一个**真正改了系统行为**的修复动作：

```java
@Transactional
public String applyMapping(String id, MappingRequest request, String reviewer) {
    if (item.revision() != request.revision()) throw new ClientException("台账已更新，请刷新后再应用");
    // 1. 真写关键词映射配置
    if (mappingId == null) {
        mappingId = mappings.create(create);        // 新增
    } else {
        mappings.update(mappingId, update);         // 更新
    }
    // 2. 台账状态同步改为 REVIEWED + fixAction=MAPPING
    cases.review(id, new ReviewRequest(..., "MAPPING", mappingId, ..., "REVIEWED", ...), reviewer);
    // 3. 事务提交后再清缓存，避免清早了读到旧配置
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { cache.clearCache(); }
    });
    return mappingId;
}
```

三个细节都做对了：
- **配置修改与台账判定在同一事务**，要么都成功要么都回滚；
- **`afterCommit` 才清缓存**，不是方法内清（清早了其他线程会读到旧配置）；
- **`fix_ref` 存 mappingId**，形成「这条 bad case 是被哪条配置修复的」的可追溯链。

另外五种 `fix_action`（INTENT_NODE / KNOWLEDGE_BASE / PROMPT / RERANK / NONE）**只是打标签，没有任何自动化**。

---

## 三、用你的真实案例走一遍完整流程

**样本**：消息 `2113869005394755584`，会话 `2113868987493466112`，2026-10-24 13:43:39

### 现场还原

```sql
SELECT id, message_status, duration_ms, jsonb_array_length(blocks) FROM t_agent_message
WHERE id = '2113869005394755584';
```

结果：
- `message_status = NORMAL`，`duration_ms = 4252`
- `blocks` 有 2 个：`reasoning`（2227ms）+ `answer`（1788ms）
- 回答内容是「当前这次对话里没有接入实时演出查询工具，所以我无法查到薛之谦的演出或开票信息…」

### 用户反馈

```sql
SELECT message_id, vote, reason, create_time FROM t_message_feedback;
```

`vote = -1`（点踩），`create_time = 14:48:48`（发出 1 小时后点踩），`reason` 为空。

### 逐步走规则

| 规则 | 判定条件 | 本例结果 |
|---|---|---|
| R1 | 有 `status=ERROR` 节点 | ✗ 两个块都是正常的 reasoning/answer |
| R2 | `message_status=INTERRUPTED` | ✗ 是 NORMAL |
| R3 | `message_status=REJECTED` | ✗ |
| R4 | `rag && retrieve && 缺证据` | ✗ `messageType=AGENT`，且无 RETRIEVE 节点 |
| R5 | `rag && 词面重合低` | ✗ 非 RAG |
| R6 | RETRIEVE 耗时 > P95 | ✗ 无 RETRIEVE 节点 |
| R7 | `rag && 回答与来源重合低` | ✗ 非 RAG |
| R8 | `sources非空 && 含拒答措辞` | ✗ **双重失效**，见下 |
| R9 | 回答≥200字 && 末尾无结束符 | ✗ 回答以「方便你自己比对。」结尾，**匹配 ENDING 正则** |
| R10 | REWRITE 漂移 | ✗ 无 REWRITE 节点 |

**R8 的双重失效值得单独说**。这条规则的条件是 `sources && REFUSAL.matcher(answer).find()`：

1. **第一重**：`AgentBadCaseMessageProvider` 传的是 `sources(List.of())`（第 82 行），所以 `sources=false`，直接短路；
2. **第二重**：即使 sources 非空，正则也匹配不上——我实测过：

```python
REFUSAL = '抱歉|我不知道|无法回答|无法提供|没有查询到'
答案原文 = '...所以我无法查到薛之谦的演出或开票信息...'
匹配结果 = False
```

原文用的是**「无法查到」**，而正则里只有「无法回答」「无法提供」，**没有「无法查到」**。

### 最终输出

十条规则全部不命中，落到兜底：

```
未命中可用的确定性异常线索，请人工检查回答与证据；这不表示回答正确。
```

### 这个结果说明了什么

**这是一次真实的 bad case，用户明确点踩了，但规则一句建设性分析都给不出来。** 原因不是规则写错了，而是：

1. **8 条规则有 RAG 前置条件或依赖特定节点**（R4/R5/R7 要 `rag=true`，R6/R10 要 RETRIEVE/REWRITE 节点），Agent 模式下**天然全部失效**；
2. **真正的原因是「模型能力缺失 + 产品预期不符」**——用户想查演出信息，系统没接实时查询工具，模型诚实拒答。这属于**能力边界问题，不是回答质量问题**，任何基于文本的启发式规则都判断不出来；
3. **最能说明问题的是 reasoning 块**——里面明确写着「No real-time activity query tool. So I must state that current deployment lacks real-time activity query capability」，模型的行为完全正确，问题在于产品没有那个工具。

**这类 bad case 价值极高**：它指向的不是「模型答错了」，而是「产品缺功能」。而现有规则体系完全触达不到这一层。

---

## 四、设计上的四个亮点

**1. 线索与结论彻底分离。**
`suspicions`（规则产出）和 `root_cause`（人工判定）是两个独立字段，表注释写明「规则产生的待核查线索，不是已确认根因」。审核时 `REVIEWED`/`FIXED` 必须选根因，强制人做判断。

**2. 快照而非指针。**
不良消息后续被删被改，bad case 仍然完整。归档时（`archiveResolved`）只清空正文、保留唯一键墓碑：

```sql
UPDATE t_bad_case SET deleted=1, user_question=NULL, assistant_answer=NULL, ...
WHERE deleted=0 AND review_status IN ('FIXED','IGNORED') AND review_time < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
```

既满足隐私要求，又防止旧反馈重新建单。

**3. 诚实标注不确定性。**
`traceMatchStatus` 有五档：`DIRECT` / `TIME_CANDIDATE` / `AMBIGUOUS` / `MISSING` / `AGENT_BLOCKS`。
时间窗口匹配到的会标 `TIME_CANDIDATE` 并在 notes 里写「可能错配」；多个候选时标 `AMBIGUOUS` 且**不自动选**。这比"随便挑一个"诚实得多。

**4. 修复闭环有真落地。**
`applyMapping` 是真改配置，不是打标签。而且 `afterCommit` 才清缓存这个细节，说明写代码的人踩过坑。

---

## 五、五个实际缺陷（按影响排序）

### 缺陷 1：Agent 模式规则大面积失效（最严重）

R4/R5/R7 都有 `rag` 前置条件，R6/R10 依赖 RETRIEVE/REWRITE 节点——**这五条在 Agent 模式下永远不触发**。
R1/R2/R3 依赖的状态（ERROR 节点 / INTERRUPTED / REJECTED）在 Agent 侧也几乎不可能出现（见下一条）。
**结果：十条规则在 Agent 模式下实际只有 R9（截断检测）可能触发。**

### 缺陷 2：入口校验把 R2/R3 的消息挡在门外

`submitDislike` 要求 `.eq(getMessageStatus, NORMAL)`——**INTERRUPTED 和 REJECTED 的消息用户根本踩不了**。
所以 R2/R3 这两条规则写在那里，但永远等不到数据。这是个逻辑悖论：规则想检测中断，而中断的消息进不了规则。

### 缺陷 3：`REFUSAL` 正则覆盖不全

实测 `无法查到` 匹配不上。这类正则必然有遗漏，但既然 R8 是「有来源却拒答」的重要信号，至少应该补上「无法查询」「查不到」「暂无」「不支持」这类常见措辞。

### 缺陷 4：provider 顺序耦合

```java
providers.stream().map(...).findFirst()
```

两个 provider 都注册时，谁先执行取决于 Spring 注入顺序。`RagBadCaseMessageProvider` 是无条件 `@Component`，在 `engine=agent` 下也会注册并**先去 `t_message` 里找**（找不到返回 empty，然后才轮到 Agent provider）。

目前不出问题，但一旦 `t_message` 里恰好有同名 id（跨引擎 id 碰撞理论上可能），就会取错数据。而且 RAG provider 若抛异常，`analyze` 整个失败，`BadCaseService` 记为 `failed++`，**这条点踩就永远建不了单**。

**建议**：给 `RagBadCaseMessageProvider` 加 `@ConditionalOnWorkflowEngine`，与 Agent 侧对称。

### 缺陷 5：`retrieveP95` 对 Agent 无效

```sql
SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
FROM t_rag_trace_node WHERE ... node_type='RETRIEVE' ...
```

只查 RAG 节点表。Agent 模式下这张表是空的，所以 R6 永远用兜底值 3000ms。而 Agent 的工具耗时明明在 `t_agent_message.blocks` 里，却没被纳入统计。

---

## 六、建议的改进顺序

| 优先级 | 改动 | 预期收益 |
|---|---|---|
| P0 | 跑迁移脚本建 `t_bad_case`，修 `@TableField(exist=false)` 与 provider 条件注解 | 系统才能真正跑起来 |
| P1 | 补 `REFUSAL` 正则 | 一条 SQL 级别的低成本改进 |
| P1 | `retrieveP95` 兼容 Agent：改查 `blocks` 里的工具耗时 | 让 R6 在 Agent 模式生效 |
| P1 | 新增 Agent 专属规则：**能力缺失检测**（reasoning 里出现「没有…工具」「未接入」且 answer 拒答） | 覆盖本例这类高价值 bad case |
| P2 | 放开采集入口：允许对 INTERRUPTED 消息记录（不点踩也能自动入账） | 让 R2 真正可用 |
| P2 | 规则与 `root_cause` 建立映射建议（如 R4 → RETRIEVAL_MISS） | 减少人工判定负担 |

---

## 七、这套逻辑的本质

它是一套**「证据收集器 + 待办清单生成器」**，不是"AI 质量自动评分系统"。

**它的价值边界在**：把「用户随手一个点踩」变成「一份带完整现场的可复盘工单」，让人的判断有据可依、不靠回忆。

**它做不到的**：判断回答到底对不对。因为对错依赖业务预期（比如"这里本该有实时查询工具"），而业务预期不在代码里。
