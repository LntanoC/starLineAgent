# bad case 规则 Agent 化改造技术方案（交付 Codex 执行）

> 目标：让 bad case 判定规则在 Agent 架构下真正生效。当前 10 条规则中 7 条因数据形态不匹配而失效，需按 Agent 的真实数据结构重写。
>
> 依据：本方案所有代码路径、行号、阈值数值均经本地源码与实测计算核实（2026-10-24）。**未修改任何项目文件。**

---

## 0. 问题定性

`BadCaseRules` 的 R1–R10 是为 **workflow 编排管线**设计的，依赖 `RETRIEVE` / `REWRITE` 节点和 `sources` 字段。workflow 下线后：

| 规则  | 依赖                | Agent 侧实际                                             | 状态    |
| --- | ----------------- | ----------------------------------------------------- | ----- |
| R1  | ERROR 节点          | `AgentAuditNode.nodeType=ERROR` 或 blocks `kind=error` | ✅ 有效  |
| R2  | INTERRUPTED       | 枚举有此值                                                 | ✅ 有效  |
| R3  | REJECTED          | **枚举无此值**                                             | ❌ 死规则 |
| R4  | `rag && RETRIEVE` | 无 RETRIEVE 节点                                         | ❌ 失效  |
| R5  | `rag && sources`  | `sources` 恒空                                          | ❌ 失效  |
| R6  | RETRIEVE 节点耗时     | 无 RETRIEVE；且 `retrieveP95()` 已退化为返回常量                 | ❌ 失效  |
| R7  | `rag && sources`  | `sources` 恒空                                          | ❌ 失效  |
| R8  | `sources` 非空      | 恒空                                                    | ❌ 失效  |
| R9  | 结尾无标点             | 与引擎无关                                                 | ✅ 有效  |
| R10 | REWRITE 节点        | 无 REWRITE 节点                                          | ❌ 失效  |

**线上印证**：3 条实际 bad case（`message_type` 全为 `AGENT`）**全部**输出兜底文案「未命中可用的确定性异常线索」。

---

## 1. Agent 侧真实可用的数据（关键前提）

### 1.1 `t_agent_run`（`AgentAuditRun`）

```
runId / conversationId / userId / assistantMessageId
question / answer
runStatus / captureStatus
roundCount / modelCallCount / toolCallCount     ← 关键
inputTokens / outputTokens / cachedTokens
durationMs / errorMessage
```

### 1.2 `t_agent_run_node`（`AgentAuditNode`）

```
nodeType      : AGENT / LLM / TOOL / CONFIRM
nodeName      : 工具名（如 search_knowledge）
status        : RUNNING / DONE / FAILED / DENIED / AWAITING
inputPayload  : JSON —— TOOL 节点是工具入参 {"query":"..."}
outputPayload : JSON —— TOOL 节点是工具返回全文；LLM 节点是 {answer,thinking,toolCalls}
durationMs / inputTokens / outputTokens / cachedTokens / ttftMs
batchId / toolCallId / callIndex / roundIndex
```

**关键结论**：Workflow 里 `RETRIEVE` / `REWRITE` 节点提供的证据，Agent 侧**全都有对应物**：

| Workflow 概念        | Agent 对应物                                               |
| ------------------ | ------------------------------------------------------- |
| RETRIEVE 节点 + 召回结果 | `TOOL` 节点（`nodeName=search_knowledge`）的 `outputPayload` |
| REWRITE 节点 + 改写结果  | `TOOL` 节点的 `inputPayload.query`                         |
| RETRIEVE 节点耗时      | `TOOL` 节点 `durationMs`                                  |
| `sources` 字段       | `TOOL` 节点 `outputPayload` 全文                            |

**所以不是「没有数据」，而是「数据换了位置，规则没跟着搬」。**

---

## 2. 先修一个数据缺口（必做前置）

### 2.1 现状

`agent/.../service/AgentBadCaseMessageProvider.java` 第 59-64 行，把审计节点转成 `TraceNodeSummary`：

```java
new BadCaseReport.TraceNodeSummary(
    n.getNodeId(), n.getParentNodeId(),
    "AGENT".equals(n.getNodeType()) ? 0 : "TOOL".equals(n.getNodeType()) ? 2 : 1,
    n.getNodeType(), n.getNodeName(),
    "FAILED".equals(n.getStatus()) ? "ERROR" : n.getStatus(),
    n.getDurationMs(), n.getErrorMessage(), n.getStartedAt(),
    clip(n.getOutputPayload(), 16000))        // ← 只传了 output，input 丢了
```

`TraceNodeSummary` 定义（`BadCaseReport` 内部 record）：

```java
public record TraceNodeSummary(String nodeId, String parentNodeId, Integer depth,
                               String nodeType, String nodeName, String status, Long durationMs,
                               String errorMessage, Date startTime, String extraData) {}
```

**只带 `extraData`（= outputPayload），没有 `inputPayload`。** 而 A6（改写漂移）需要工具入参。

### 2.2 改动

`BadCaseReport.TraceNodeSummary` 追加一个字段（放最后，兼容既有调用）：

```java
public record TraceNodeSummary(String nodeId, String parentNodeId, Integer depth,
                               String nodeType, String nodeName, String status, Long durationMs,
                               String errorMessage, Date startTime, String extraData,
                               String inputData) {}      // 新增：工具入参 / 模型输入
```

`AgentBadCaseMessageProvider` 两处构造同步补 `clip(n.getInputPayload(), 8000)`：

- 第 59-64 行（审计节点分支）→ `clip(n.getInputPayload(), 8000)`
- 第 74-84 行（blocks 分支）→ 工具块的入参不在 blocks 里（blocks 只有 `result`），传 `null`

同时 `RagBadCaseMessageProvider`（workflow 下线方案中已列删除）若保留需补 `null`，删除则无需处理。

---

## 3. ⚠️ 核心发现：重合度方向在 Agent 侧是反的

这是本方案最重要的一条，**直接照搬 R7 会算错**。

`overlap(a, b)` 的分母是 **a 的 token 数**（`BadCaseRules` 第 97-101 行）：

```java
static double overlap(String query, String evidence) {
    Set<String> q = tokens(query), e = tokens(evidence);
    if (q.isEmpty()) return 1;
    return q.stream().filter(e::contains).count() / (double) q.size();   // 分母 = q
}
```

### 3.1 实测数据（用真实长度的检索结果与回答）

样本：问题 13 token，检索返回 195 token，忠实回答 78 token，脱离回答 43 token。

| 算法                                         | 忠实回答       | 脱离回答       | 判别力   |
| ------------------------------------------ | ---------- | ---------- | ----- |
| `overlap(toolOutput, answer)` ← **R7 原写法** | 0.2872     | 0.0205     | 勉强    |
| `overlap(answer, toolOutput)` ← **应该这么写**  | **0.7179** | **0.0930** | **强** |

**原因**：`toolOutput` 有 195 个 token，分母太大，任何回答都算不出高分，绝对数值全被压扁。反过来用回答做分母，忠实回答能到 0.72，脱离回答只有 0.09，**8 倍差距**。

**R7 在 workflow 时代能用**，是因为 `sources` 存的是**摘要**（`docName + excerpt`，几十字），不是全文。而 Agent 的工具输出是**完整检索结果**（几百到几千字），量级完全不同。

### 3.2 结论

> **Agent 版规则一律用「短文本做分母」**：判断回答是否忠于资料 → `overlap(answer, toolOutput)`；判断检索是否相关 → `overlap(question, toolOutput)`。

同理，A3 实测：`overlap(question, toolOutput)` = 0.4615（正常召回），说明分母用 question（13 token）时数值健康。

---

## 4. 新增规则 A1–A8（Agent 版）

### 4.1 A1 该查没查

**判定**：整轮没有调用任何检索工具，却给出了具体而非拒答的回答。

```java
if (run.toolCallCount == 0
        && answer.length() >= 100
        && !REFUSAL.matcher(answer).find()
        && !answer.contains("通用知识")
        && !answer.contains("未查询")) {
    result.add("A1 本轮未调用任何工具却给出具体回答，需核对是否存在应检索未检索；"
             + "若属通用知识问答可忽略。");
}
```

**数据来源**：`run.toolCallCount`（`t_agent_run`）+ `answer`。

**注意**：这条**高误报**（闲聊、通用知识、身份问答都会命中）。文案里必须写明「若属通用知识问答可忽略」，且**只作为线索，不能作为结论**。建议默认关闭，需要时再开。

### 4.2 A2 检索执行失败

```java
for (var n : nodes) {
    if (!"TOOL".equals(n.nodeType()) || !"search_knowledge".equals(n.nodeName())) continue;
    if ("ERROR".equals(n.status()) || "FAILED".equals(n.status())) {
        result.add("A2 知识检索工具执行失败（status=" + n.status() + "），"
                 + "回答可能是在无资料情况下生成，优先核查检索异常原因。");
        break;
    }
}
```

**数据来源**：`TOOL` 节点 `status`。`KnowledgeSearchTool` 失败时返回 `ToolResultState.ERROR`，落库为 `FAILED`。

### 4.3 A3 检索结果与问题不相关（召回噪音）

```java
if (searchOutput != null && !searchOutput.isBlank()
        && tokens(question).size() >= 3
        && searchOutput.length() >= 100
        && overlap(question, searchOutput) < 0.15) {     // 实测正常值 0.46
    result.add("A3 检索结果与问题的词面重合仅 "
             + pct(overlap(question, searchOutput)) + "（正常召回通常 > 30%），"
             + "疑似召回跑偏；词面比较不能判定主题是否相关，需人工核对。");
}
```

**阈值依据**：实测正常召回 0.4615，取 0.15 作为下限留足余量。

### 4.4 A4 回答脱离检索资料（疑似幻觉）

```java
if (searchOutput != null && searchOutput.length() >= 100
        && answer.length() >= 80
        && overlap(answer, searchOutput) < 0.25) {       // 实测：忠实 0.72 / 脱离 0.09
    result.add("A4 回答与检索资料的词面重合仅 "
             + pct(overlap(answer, searchOutput)) + "（有依据的回答通常 > 50%），"
             + "疑似未依据资料作答，需人工核对忠实性。");
}
```

**阈值依据**：实测忠实 0.7179 / 脱离 0.0930，取 0.25 居中偏保守（宁可漏报不乱报）。

**与 R7 的区别**：R7 的分母是 evidence（长文本），A4 的分母是 answer（短文本）。**这是必须换的，不能照抄。**

### 4.5 A5 检索耗时异常

```java
if (searchNode != null && searchNode.durationMs() != null
        && searchNode.durationMs() > retrieveSlowThresholdMs) {      // 配置项，默认 3000
    result.add("A5 知识检索耗时 " + searchNode.durationMs() + " ms，"
             + "超过阈值 " + retrieveSlowThresholdMs + " ms，请核查检索性能。");
}
```

**替代 R6**。R6 原本查 `t_rag_trace_node` 算 P95，该表已废弃（`retrieveP95()` 退化为返回常量）。现在直接用 Agent 工具节点的真实耗时，**比原来更准确**（原来只能看 workflow 的检索节点，现在能看 Agent 每次工具调用）。

### 4.6 A6 检索 query 与用户问题漂移

```java
String query = extractQuery(searchNode.inputData());     // 从 inputPayload 取 query
if (query != null && tokens(question).size() >= 3
        && overlap(question, query) < 0.1) {
    result.add("A6 检索入参与原问题词面重合仅 "
             + pct(overlap(question, query)) + "，疑似模型改写 query 时语义漂移，需人工比对。");
}
```

**替代 R10**。workflow 有独立的 REWRITE 节点，Agent 没有——但 Agent 每次调 `search_knowledge` 都要模型自己生成 `query`，**这个 query 就是「改写结果」**，比 workflow 的改写更值得检查（因为它是模型现场造的）。

`extractQuery` 实现：

```java
private String extractQuery(String inputPayload) {
    if (inputPayload == null || inputPayload.isBlank()) return null;
    try {
        JsonNode node = json.readTree(inputPayload);
        JsonNode q = node.get("query");
        return q != null && !q.isNull() ? q.asText() : null;
    } catch (Exception ignored) { return null; }
}
```

### 4.7 A7 轮次/调用失控

```java
if (run.roundCount != null && run.roundCount > maxRounds) {          // 默认 6
    result.add("A7 本轮共 " + run.roundCount + " 个模型轮次，超过 " + maxRounds
             + "，疑似反复试错未收敛，请核查工具返回是否可用。");
}
if (sameToolRepeatCount > maxSameToolCalls) {                        // 同名工具调用 > 3 次
    result.add("A7 同一工具在一轮内被调用 " + sameToolRepeatCount
             + " 次，疑似反复检索未得到有效结果。");
}
```

**数据来源**：`t_agent_run.roundCount` + 节点 `toolCallId`/`callIndex` 统计。`application.yaml` 里 `agent.max-iters: 10`，取 6 作为预警线。

### 4.8 A8 检索返回空结果

```java
if (searchNode != null && ("DONE".equals(searchNode.status()) || "SUCCESS".equals(searchNode.status()))
        && (searchOutput == null || searchOutput.isBlank() || EMPTY_HINT.matcher(searchOutput).find())) {
    result.add("A8 检索执行成功但返回内容为空或含「未找到」提示，"
             + "说明知识库可能未覆盖该问题。");
}
```

`EMPTY_HINT` 建议：

```java
private static final Pattern EMPTY_HINT =
    Pattern.compile("未查询到|没有找到|未找到|暂无相关|知识库中未");
```

---

## 5. 规则分流：按 messageType 决定跑哪套

改造 `BadCaseRules.evaluate()`，把 R4/R5/R6/R7/R8/R10（workflow 专属）与 A1–A8（Agent 专属）按类型分流：

````java
public List<String> evaluate(BadCaseReport r, long retrieveP95) {
    List<String> result = new ArrayList<>();
    boolean isAgent = "AGENT".equals(r.getMessageType());
    boolean isRag = "RAG".equals(r.getMessageType());

    // 通用规则（两套都跑）
    if (nodes.stream().anyMatch(n -> "ERROR".equals(n.status()))) result.add("R1 ...");
    if ("INTERRUPTED".equals(r.getMessageStatus())) result.add("R2 ...");
    if ("NORMAL".equals(r.getMessageStatus()) && answer.length() >= 200
            && !ENDING.matcher(answer).find() && !answer.endsWith("```")) result.add("R9 ...");

    if (isRag) {
        // 原 R3–R8、R10 保持不动（workflow 下线后自然不再触发）
    }
    if (isAgent) {
        evaluateAgentRules(r, result);      // A1–A8
    }

    if (result.isEmpty()) result.add("未命中可用的确定性异常线索，请人工检查回答与证据；这不代表回答正确。");
    // ... evidenceNotes 逻辑保持不变
    return result;
}
````

**为什么要分流而不是直接替换**：`RagBadCaseMessageProvider` 若仍存在（workflow 下线方案里已列删除），保留 RAG 分支可让两套规则共存；删除后 `isRag` 分支永不进入，等于自动失效，无需额外清理。

---

## 6. R3 死规则的处置

`BadCaseRules` 第 43 行：

```java
if("REJECTED".equals(r.getMessageStatus())) result.add("R3 请求被拒绝，优先核查限流及准入条件。");
```


`AgentMessageStatus` 只有 `NORMAL / INTERRUPTED / AWAITING_CONFIRM`，**永不命中**。

**建议**：本轮**保留但不删**，加注释说明：

```java
// R3 仅对 workflow（rag 侧 MessageStatus）有效；Agent 侧 AgentMessageStatus 无此取值，
// 该分支在 engine.type=agent 下永不命中，随 RagBadCaseMessageProvider 一并清理。
if("REJECTED".equals(r.getMessageStatus())) result.add("R3 ...");
```

理由：删掉会让 `MessageStatus`（rag 侧枚举，含 REJECTED）对应的行为丢失语义；等 workflow 代码彻底删除时一并处理更安全。

---

## 7. 配置项新增

`BadCaseProperties` 追加：

```java
/** A3 检索相关性下限（实测正常召回 ≈0.46，低于此值视为疑似跑偏） */
private double retrieveOverlapMin = 0.15;
/** A4 回答忠实度下限（实测忠实 ≈0.72、脱离 ≈0.09，低于此值视为疑似幻觉） */
private double answerOverlapMin = 0.25;
/** A5 工具耗时阈值（覆盖原 retrieveSlowThresholdMs 语义） */
private long toolSlowThresholdMs = 3000;
/** A7 单轮最大模型轮次，超过即预警 */
private int maxRounds = 6;
/** A7 同名工具单轮最大调用次数 */
private int maxSameToolCalls = 3;
/** A1 是否启用「该查没查」检测（误报较高，默认关闭） */
private boolean enableNoToolCallRule = false;
```

⚠️ `retrieveSlowThresholdMs` 现有字段保留（`retrieveP95(fallback)` 仍在用），新增 `toolSlowThresholdMs` 避免语义混淆；或直接复用前者，由 Codex 按实际调用点决定。

---

## 8. 实施顺序

**阶段 1：补数据缺口（前置，不做则 A6 无法实现）**

1. `TraceNodeSummary` 加 `inputData` 字段
2. `AgentBadCaseMessageProvider` 两处构造补参数
3. 编译验证 + 取一条真实 run 确认 `inputData` 里有 `query`

**阶段 2：新增 A1–A8**

1. `BadCaseProperties` 加配置项
2. `BadCaseRules` 加 `evaluateAgentRules()`
3. `evaluate()` 改为按 `messageType` 分流
4. 单元测试：构造 AgentBadCaseReport，逐条验证 A2–A8 触发

**阶段 3：阈值校准（用真实数据）**

1. 取线上最近 50 条 Agent 会话（含正常与点踩）
2. 跑规则，统计各条命中率
3. 人工抽检 20 条，核对误报/漏报
4. 按实际分布微调阈值

**阶段 4：上线观察**

1. 先只写日志不建单（`enabled=false`）
2. 观察一周命中分布
3. 再开启建单

---

## 9. 风险与注意

1. **A1 误报率高**：闲聊、通用知识、身份问答都会命中「未调用工具但给了具体回答」。默认关闭，需要时手动开。
2. **A4 阈值要按真实数据校准**：0.25 是基于构造样本的估算，真实检索结果长度差异大，必须用线上数据验证。
3. **`tokens()` 的二元组特性**：中文按字符二元组切，长文本 token 集合会很大。所有涉及长文本的 overlap 都要注意分母选择（见第 3 节）。
4. **词面重合 ≠ 语义相关**：所有 A 规则都必须带「词面比较不能判定主题是否相关」「需人工核对」这类免责说明，**不能作为结论**。这是原 R5/R7 文案已经做对的地方，要保持。
5. **不要删 R1/R2/R9**：这三条在 Agent 下是有效的，且零误报（都是确定性状态判断）。
6. **`AgentBadCaseMessageProvider` 的 blocks 分支拿不到工具入参**：`AgentBlock` 只有 `result` 没有入参。若审计数据不可用（`captureStatus` 非完整），A6 会退化——此时应标记 `evidenceNotes` 说明「入参不可用，A6 未执行」，而不是静默跳过。

---

## 10. 验证清单

- [ ] 构造 Agent 消息：工具调用失败 → 命中 A2
- [ ] 构造检索结果与问题完全无关 → 命中 A3（overlap < 0.15）
- [ ] 构造回答与检索结果无关 → 命中 A4（overlap < 0.25）
- [ ] 构造检索耗时 5 秒 → 命中 A5
- [ ] 构造模型 query 与原问题无关 → 命中 A6
- [ ] 构造 8 轮模型调用 → 命中 A7
- [ ] 构造检索返回「未查询到」 → 命中 A8
- [ ] 正常 Agent 问答（有检索、回答忠实）→ **不命中任何规则**（关键：不能误报）
- [ ] R1/R2/R9 回归：原有触发场景仍能命中
- [ ] 线上 3 条历史 bad case 重跑，观察是否能从「全兜底」变为有具体线索
