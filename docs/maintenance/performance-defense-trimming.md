# 性能优化任务书：削减防御性开销

> 面向执行者（Codex）的施工单。本文与 `docs/maintenance/degradation-observability-refactor.md` 并存，两者不冲突：
>
> - 那份解决「出故障时看不见」
> - 这份解决「平时跑得慢」
>
> 同一处代码可能两份都会碰（例如 `LightRagClient` 的 catch 块），改造时先完成本文的 P0，再做那份的 T0，避免两次改同一行。

- 文档版本：v1
- 编写日期：2026-10-29
- 代码基线：同 `degradation-observability-refactor.md`
- 核心判断：**本项目的性能损耗，主要来自「防御动作本身要发远程调用、要等超时、要占线程」，而不是 `if` 判断**。因此「砍防御」与「提速」在多数位置是同一件事

---

## 1. 目标与硬约束

### 1.1 目标

1. 消除压在请求主链路上的**纯损耗**（发了没人用的 LLM 调用、等了没人要的超时）。
2. 修正**超时层级错配**，让被放弃的请求尽快释放线程。
3. 为「优化是否真的生效」建立可复跑的度量方式。

### 1.2 硬约束（违反即返工）

| 编号 | 约束 |
|---|---|
| C1 | **不得降低召回与答案质量**（P1 除外，P1 必须先有评测基线）。P0 全部为零质量损失项。 |
| C2 | **保留所有超时机制本身**，只调整取值与层级关系。删掉超时会让最慢通道决定整体延迟，与本文目标相反。 |
| C3 | **保留所有降级分支**（`LightRagClient` 等的 `catch → 空结果`）。它们防的是「下游故障打挂检索」这一更大损失。 |
| C4 | **不要用 `CompletableFuture.cancel(true)` 来释放线程**。见 2.2，对 `supplyAsync` 的阻塞任务它不生效，改了等于没改，还会让人误以为问题已解决。 |
| C5 | 任何超时取值变更，必须同时改配置与 `application.yaml`，并在 PR 描述里给出「改前 / 改后」对照表。 |
| C6 | 性能改动**必须成对提供度量证据**：改动前后的同一组请求的端到端耗时。不接受「我觉得快了」。 |
| C7 | 与 `degradation-observability-refactor.md` 的硬约束 C1-C8 同样适用（尤其是不改降级行为、不动 `AgentRunHandle` 与 `MultiChannelRetrievalEngine.withTimeout` 的**语义**——取值可以调，语义不能动）。 |
| C8 | 分阶段提交，P0 / P1 / P2 各自独立，便于单独回滚。 |

---

## 2. 基线事实（已核实，先读再动手）

### 2.1 一次检索工具调用的构成

`KnowledgeSearchFacade.search()` 的串行链路：

```
rewriteWithSplit()  →  RetrievalEngine.retrieve()  →  llmService.chat()
   [1 次 LLM]              [向量化 + 多通道并行 + 精排]        [1 次 LLM]
```

主 Agent 拿到工具结果后还要再生成一次终答，所以**一轮问答通常含 3 次 LLM 调用**，其中 1 次（rewrite）在 Agent 场景下是纯损耗。

### 2.2 超时层级错配（本次最重要的发现）

实测配置：

| 层 | 位置 | 当前值 |
|---|---|---|
| 引擎通道超时 | `SearchChannelProperties:115` / `application.yaml:193` (`rag.search.channels.timeout-ms`) | **15,000 ms** |
| OkHttp 读超时 | `rag/config/HttpClientConfig.java:56` | 30,000 ms |
| OkHttp 整体超时 | `rag/config/HttpClientConfig.java:57` | 45,000 ms |
| Milvus 连接超时 | `rag/config/MilvusConfig.java:65` | 10,000 ms |

**层级是反的**：外层 15s 就放弃结果，但内层请求被允许跑到 30s / 45s 才断开。

后果非常具体：`MultiChannelRetrievalEngine.withTimeout()` 在 15s 时返回空结果，**但那一线程要再占用 15~30 秒才真正空闲**。注释里写的「只放弃结果、不中断执行，任务仍在池内跑完」就是这个状态。并发一上来，线程池被这些「已经没人要结果」的任务占满，正常请求开始排队，P99 反而被推高。

这里有个常见误判要提前排除：**`future.cancel(true)` 解决不了这个问题**。`CompletableFuture.supplyAsync` 提交的任务不响应中断（它不是 `FutureTask`），`cancel(true)` 只会让 future 变成 cancelled 状态，底层阻塞在 `OkHttp.execute()` 或 ES/Milvus 阻塞调用上的线程照跑不误。真正能释放线程的只有一件事：**让底层请求自己先超时退出**。

### 2.3 精排与闸门的顺序

后处理链 `getOrder()`：

```
Deduplication(1) → Fusion(5) → Rerank(10) → EvidenceGate(15) → MetadataEnrichment(20)
```

`candidateLimit` 条全量送精排（远程调用），拿到 `rerankScore` 后 `EvidenceGate` 再按 `min-rerank-score` 砍掉大部分。等于先把贵的算完再筛。顺序本身是必然的（闸门依赖精排分），可优化的是**送进精排的条数**。

---

## 3. P0：零质量损失，可立即执行

### P0-1 跳过 Agent 链路无意义的 LLM 改写

**位置**：`rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/rewrite/MultiQuestionRewriteService.java`，方法 `rewriteWithSplit(String, List<ChatMessage>)` 在第 **78-88** 行

**证据**：`KnowledgeSearchFacade` 的类注释已写明——

> 不带任何会话历史：主 Agent 已消解过指代，合成阶段也只依据本次证据

而 `KnowledgeSearchFacade:60` 却仍调用 `rewriteWithSplit(query, List.of())`，进入 `callLLMRewriteAndSplit`，发一次 FAST 档 LLM 请求。**注释已论证不需要，代码还是防了一遍**；而且传空历史时改写模型拿不到上下文，质量上也拿不到收益。

**改法（推荐选项 A：显式入口，零隐式契约）**

不要用「history 是否为空」来推断「调用方已经消解过指代」——那是隐式约定，将来任何新调用方传空历史都会被静默跳过改写，很难排查。改为新增一个语义明确的入口：

1. `QueryRewriteService` 增加一个方法，`MultiQuestionRewriteService` 实现它：

   ```java
   /**
    * 供 Agent 工具链路使用：调用方已消解指代并有意收窄查询，不再交给 LLM 改写，
    * 只做术语归一化与规则拆分，省掉一次串行往返
    */
   default RewriteResult rewriteForAgent(String userQuestion) {
       return rewriteWithSplit(userQuestion, List.of());
   }
   ```

   `MultiQuestionRewriteService` 中覆写为：

   ```java
   @Override
   public RewriteResult rewriteForAgent(String userQuestion) {
       String normalized = queryTermMappingService.normalize(userQuestion);
       return new RewriteResult(normalized, ruleBasedSplit(normalized));
   }
   ```

2. `KnowledgeSearchFacade:60` 改为调用 `rewriteForAgent(query)`，并把该行上方的注释同步为「不喂历史，且不再走 LLM 改写」。

3. **`EvalController:63` 保持调用 `rewriteWithSplit(question, List.of())` 不变**。这样评测口径与改造前完全一致，基线可继续复用，也不会出现「分数变了分不清是优化还是退化」。

**选项 B（改动更小，但不推荐）**：在 `rewriteWithSplit` 内按 `CollUtil.isEmpty(history)` 跳过 LLM。优点是只改一处；缺点是把「空历史 = 已消解指代」这个约定埋进方法内部，且会让 `EvalController` 的评测口径一并改变。若选 B，必须在方法 javadoc 里显式声明该约定，并在 PR 里说明评测口径变化。

**无论选哪个**：开关都要可配。

1. `RAGConfigProperties` 新增开关。该类是 `@Data @Configuration` 风格、逐字段用 `@Value` 注入（不是嵌套对象），照此写：

   ```java
   /**
    * Agent 工具链路是否跳过 LLM 查询改写
    * <p>
    * Agent 工具不传历史：主 Agent 已消解指代并有意收窄查询，再走一次 LLM 改写
    * 既拿不到上下文也无增益，只增加一次串行往返
    * 默认值：{@code true}
    */
   @Value("${rag.query-rewrite.skip-for-agent:true}")
   private Boolean rewriteSkipForAgent;
   ```

   同时在 `bootstrap/src/main/resources/application.yaml` 的 `rag.query-rewrite` 下补上该键，与 `enabled` 并列。
2. 选项 A 下 `KnowledgeSearchFacade` 的判断：

   ```java
   RewriteResult rewriteResult = ragConfigProperties.getRewriteSkipForAgent()
           ? queryRewriteService.rewriteForAgent(query)
           : queryRewriteService.rewriteWithSplit(query, List.of());
   ```

   **必须可配**，否则线上出问题只能回滚代码。
3. 保留原方法上的 `@RagTraceNode(name = "query-rewrite-and-split")`；新方法若也想进 trace，另起一个节点名，不要复用。

**顺带清理（可选）**：`MultiQuestionRewriteService:72-74` 的单参 `rewriteWithSplit(String)` 与 `QueryRewriteService.rewrite(String)` 经全项目检索**无任何调用方**，属死代码。可在本次一并删除，减少「哪个入口该用」的困惑。

**需要改的调用点**

| 位置 | 处理 |
|---|---|
| `rag/service/KnowledgeSearchFacade.java:60` | 改调 `rewriteForAgent(query)`，本次目标 |
| `rag/eval/EvalController.java:63` | **不动**（选项 A 下评测口径保持不变，基线可继续复用） |

**验收**

- 抓一次 Agent 问答的日志，`RAG用户问题查询改写+拆分` 这条不再出现（该日志在 `callLLMRewriteAndSplit` 内）。
- 工具调用耗时下降，降幅约等于一次 FAST 档 LLM 往返。
- 答案内容与改造前**逐字对比**（同一问题、同一知识库），差异应仅为措辞微调，无可答内容丢失。
- 评测接口 `/rag/eval` 的分数与改造前**完全一致**（因为没走新路径）；若不一致，说明改动误伤了公共路径。

---

### P0-2 修正超时金字塔

这是本次收益最大、也最容易改错的一项。目标：**让每一层都在上一层放弃之前自行退出**。

**改法：把超时改成严格递减的内外金字塔**

推荐值（对话场景，可按实际 FAST 档耗时微调）：

| 层 | 配置项 | 现值 | 建议值 | 理由 |
|---|---|---|---|---|
| 单通道整体超时 | `rag.search.channels.timeout-ms` | 15,000 | **3,000** | 用户等不了 15s 才降级；3s 内给不出结果的通道对答案贡献也有限 |
| OkHttp `callTimeout` | `HttpClientConfig:57` | 45,000 | **2,500** | 必须小于通道超时，留 500ms 给结果融合 |
| OkHttp `readTimeout` | `HttpClientConfig:56` | 30,000 | **2,000** | 必须小于 callTimeout |
| OkHttp `connectTimeout` | `HttpClientConfig:54` | 10,000 | **1,000** | 连不上就是连不上，10s 只会拖长失败 |
| Milvus 连接超时 | `MilvusConfig:65` | 10,000 | **1,000** | 同上 |

关键不变式，请写进注释：

```
connectTimeout < readTimeout < callTimeout < channels.timeout-ms < 整体请求预算
```

**为什么这样就能释放线程**：底层 OkHttp 在 2.5s 自己抛 `InterruptedIOException` 并返回，`supplyAsync` 的任务随之结束、线程归还池子。此时 `withTimeout` 的 3s 兜底甚至不会被触发——它从「唯一手段」退化为「最后保险」，这才是超时该有的位置。

**不要做的事**（重申 C4）

- 不要给 `CompletableFuture` 加 `cancel(true)` 了事，见 2.2。
- 不要把 `withTimeout` 整个删掉，它是最后一道保险。
- 不要为了「稳」把通道超时保留在 15s 再单独降 OkHttp 超时——那样只是让失败更快，通道仍然等满 3s 才降级，P99 不变。

**分档建议（可选，但收益明显）**

不同通道的重要性和延迟分布不同，值得给不同预算。`SearchChannel` 已具备 `isEnabled(context)` 与 `getName()`，可扩展一个 `timeoutMs()` 默认方法：

| 通道 | 建议预算 | 依据 |
|---|---|---|
| `VectorSearchChannel` | 3,000 | 主力通道，必须给足 |
| `KeywordSearchChannel` | 1,500 | ES 通常更快，慢说明异常 |
| `GraphSearchChannel` | 1,500 | LightRAG 是可选增强，慢就该放弃 |
| `WebSearchChannel` | 2,500 | 外部网络，波动大 |

改法是给 `SearchChannel` 加 `default long timeoutMs(SearchChannelProperties props)`，`MultiChannelRetrievalEngine` 取用通道自己的值，缺省回落到全局配置。**这属于可选增强，先做全局统一值、验证有效后再分档**，避免一次改两件事。

**验收**

1. 用 `ab` / `wrk` 或项目 `scripts/` 下的压测脚本打 50 并发，记录改造前后 P50 / P95 / P99 与线程池活跃数。
2. 人为让一个通道变慢（在 `LightRagClient` 里 `Thread.sleep(10000)`），确认：
   - 该通道 3s 被放弃（日志「超过通道级超时」）；
   - **OkHttp 线程在 2.5s 左右就已释放**（看线程 dump 或 OkHttp `Dispatcher` 的 running 数回落）；
   - 整体检索耗时不再等于最慢通道耗时。
3. 正常链路（无慢通道）的耗时不得变差——若变差，说明 3s 预算对小概率的慢查询太紧，回调到 4s 再测。

---

### P0-3 热路径归因日志降级

**位置**（检索与对话热路径共 33 处 `log.info`，重点两处）

- `rag/core/retrieval/postprocessor/RerankPostProcessor.java` 的 `logScoreSpread()` 与 `logAttribution()`
- `rag/core/retrieval/MultiChannelRetrievalEngine.java` 的「启用的检索通道」「融合后条数」等

`logScoreSpread` 每次请求要跑 3 次 `stream()`（size / max / min）再打一行，`logAttribution` 同样。这些是「为了事后排查好看」的留痕，但从不实时消费。

**改法**

1. 归因类日志降到 `debug`。
2. 保留**带 traceId / 错误信息**的那几条在 `info`（排障真正会用的）。
3. 若确实需要生产观测，用采样（如 `ThreadLocalRandom.current().nextInt(100) == 0`）而不是全量。

**验收**：改造后单次检索的信息类日志条数从当前 5-10 条降到 2 条以内；`grep -c "检索归因"` 在 info 级别下为 0。

**风险**：零。唯一代价是事后排查要多开 debug 级别，建议在 PR 描述里写明「需要排障时如何临时打开」。

---

## 4. P1：先测后上（有质量风险）

### P1-1 精排粗筛前置

**位置**：`rag/core/retrieval/postprocessor/` 链路，在 `FusionPostProcessor`(order=5) 与 `RerankPostProcessor`(order=10) 之间插入一道按融合分的粗筛。

**现状**：`candidateLimit` 条全量送精排。精排是远程调用，成本近似线性于条数。

**改法**：新增 `FusionScorePrefilterPostProcessor`，`getOrder()` 取 7-8，按融合分保留前 N%（建议从 50% 起步，可配 `rag.search.fusion.prefilter-ratio`），且设置**下限保护**（至少保留 `contextTopK` 条，避免极小候选池时误杀）。

**必须先做的事**

1. 跑评测基线：`GET /rag/eval?question=...`（`EvalController:59`），固定一组问题，记录当前分数。
2. 改后同组复跑，对比。**判定标准：分数下降超过 2% 则放弃本项**，保留全量精排。
3. 若分数稳定，再用压测确认耗时下降。

**风险**：会掉召回。这是本文唯一一处「用质量换速度」的改动，所以单独成档、必须过评测。**在评测跑通之前不要合入。**

---

## 5. P2：顺手清理（不影响对话延迟）

### P2-1 防御性快照瘦身

**位置**：19 处 `BeanUtil.copyProperties`，典型为「改前快照」用于 diff / 审计：

- `knowledge/service/impl/KnowledgeBaseServiceImpl.java:150/192/233`
- `knowledge/service/impl/KnowledgeDocumentServiceImpl.java:212/441/485`
- `knowledge/service/impl/KnowledgeChunkServiceImpl.java:194/250/290`
- `ingestion/service/impl/IngestionPipelineServiceImpl.java:110/165`

**改法**：快照只为比对字段变化，不需要全字段深拷贝。改为只取参与 diff 的字段（或直接 `clone()` 浅拷贝 + 记录变更字段集合）。这些都在管理 API，**优先级最低，可最后做**。

### P2-2 修复 pipeline 列表 N+1

**位置**：`ingestion/service/impl/IngestionPipelineServiceImpl.java:144-148`

```java
voPage.setRecords(result.getRecords().stream()
        .map(each -> toVO(each, fetchNodes(each.getId())))   // 每行一次查询
        .toList());
```

一页 20 条 = 21 次查询。**改法**：先收集当页所有 `id`，一次 `selectIn` 拿到节点，再按 `pipelineId` 分组组装。

同类问题请在 `KnowledgeDocumentServiceImpl` 与 `AgentProfileAdminServiceImpl` 的分页方法里自查一遍。

---

## 6. 度量与验收

### 6.1 必须先建立基线

性能改动最大的坑是「改完感觉快了，但不知道快在哪、有没有副作用」。开工前先做：

1. **固定一组测试问题**（建议 10-20 条，覆盖：单跳问答、多跳、需要工具、纯闲聊、知识库无答案）。
2. **记录端到端耗时**：从 `POST /agent/v1/chat` 到收到 `DONE` 事件的耗时，逐条记录。
3. **记录 LLM 调用次数**：给 `LLMService.chat` 加一个临时计数器（或从现有日志里数「模型调用」条数），这是最直接的「省了几次往返」证据。
4. **记录线程池活跃数**：压测期间采样 `ragContextExecutor` 等池的 active count。

基线数据存到 `docs/maintenance/performance-baseline-20261029.md`（新建），后续每次改动往里追加一列。

### 6.2 验收清单

```bash
# 1. 编译与单测
./mvnw -q -DskipTests compile
./mvnw -q test

# 2. 确认没有引入 cancel 误用（C4）
grep -rn --include="*.java" "cancel(true)" rag/src/main/java agent/src/main/java
# 预期：仅出现在真正需要中断的非阻塞场景，检索链路内不应新增

# 3. 确认超时金字塔成立（人工核对配置）
grep -n "connectTimeout\|readTimeout\|callTimeout" rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/HttpClientConfig.java
grep -n "timeout-ms" bootstrap/src/main/resources/application.yaml
```

### 6.3 收益预期（写完基线后填实际值）

| 项 | 预期方向 | 实际 |
|---|---|---|
| P0-1 工具调用耗时 | 下降约 1 次 FAST 档 LLM 往返 | 待测 |
| P0-2 高并发 P99 | 明显下降（线程不再被僵尸任务占满） | 待测 |
| P0-2 单通道异常时的整体耗时 | 由「最慢通道耗时」变为「3s 上限」 | 待测 |
| P0-3 日志量 | 单请求 info 条数降 60% 以上 | 待测 |
| P1-1 精排耗时 | 下降约 50%（输入减半） | 待评测 |

**不要凭本文的百分比对外汇报**，一律以 6.1 的基线实测为准。不同机器、不同知识库规模、不同 FAST 档模型，绝对值差异很大。

---

## 7. 不砍清单（避免一刀切）

| 保留 | 理由 |
|---|---|
| 各通道超时机制本身 | 删掉会让最慢通道决定整体延迟 |
| `withTimeout` 的 `exceptionally` 分支 | 它是最后保险，P0-2 只是让它退居二线 |
| `LightRagClient` / `EsKeywordIndexService` 的降级返回 | 防的是下游故障打挂检索 |
| `AgentAuditWriter` 的异步队列（`offer/start/close`） | 已经是异步的，是正确写法 |
| `PromptTemplateLoader` 的 `ConcurrentHashMap` 缓存 | 已经在缓存，是正确写法 |
| `CitationMarkup.INLINE_CITATION` | 已是 `static final`，不是热点 |
| 流式桥 `onTextDelta` → `appendTextBlock` | 已是 O(1) 追加，`textOf()` 只在收尾调用，不是 O(n²) |

**判断标准**：砍的是「再保险一点」的冗余动作；留的是「防的是更大损失」的兜底。

---

## 8. 提交建议

| 顺序 | commit message | 阶段 |
|---|---|---|
| 1 | `perf(rag): 空历史时跳过 LLM 查询改写，省一次串行往返` | P0-1 |
| 2 | `perf(rag): 修正超时金字塔，避免被放弃的请求继续占用线程` | P0-2 |
| 3 | `perf(rag): 热路径归因日志降为 debug` | P0-3 |
| 4 | `perf(rag): 精排前增加融合分粗筛（需评测通过）` | P1-1，独立评审 |
| 5 | `refactor(knowledge): 快照只保留 diff 字段` | P2-1 |
| 6 | `perf(ingestion): 修复 pipeline 列表 N+1 查询` | P2-2 |

前 3 个建议一起评审合入并附基线对比；第 4 个必须单独评审且附评测报告。
