# 降级可观测性改造任务书

> 面向执行者（Codex）的施工单。批量修改前请完整读第 1、2 节，第 2 节纠正了三处与当前代码不符的常见假设，按错的假设改会返工。

- 文档版本：v1
- 编写日期：2026-10-29
- 代码基线：`agent` / `rag` / `infra-ai` / `system` / `framework` / `mcp-server` 主分支，生产代码 629 文件 / 62,970 行
- 关联分析结论：本项目的问题不是防御性编程数量过多（`catch` 227 个 / 6 万行，密度正常），而是防御的位置全部集中在「消音」一侧，没有一处落在「报信」一侧，导致降级不可计数、不可归因

---

## 1. 目标与硬约束

### 1.1 目标

1. 让每一次「catch 之后返回兜底值」都留下可查询的痕迹（落表 + 结构化日志）。
2. 让 bad case 归因系统能够先判断「这次回答差是不是因为降级」，再谈模型与提示词。
3. 修掉异常包装丢失 `cause` 的写法，使 `GlobalExceptionHandler` 已有的根因分支真正生效。

> **这个痛点作者本人已经写进注释了。** `agent/.../tool/KnowledgeSearchTool.java:108` 原文：
>
> ```java
> // RAG 逐层降级，取消到这里多半不是异常而是一份空结果，只在 catch 里判会漏掉
> ```
>
> 说明「逐层降级导致上层无法区分故障与空结果」是已知问题，只是当时用「再不济也给用户一句提示」绕过去了。本次改造是把这句注释指向的缺口真正堵上，不是新增理论风险。

### 1.2 硬约束（违反即返工）

| 编号 | 约束 |
|---|---|
| C1 | **不改变任何降级行为**。改造前后，同样的故障输入必须产生同样的兜底返回值和同样的业务结果。本次只增加「记录」，不增加「拦截」，不改变重试、超时、熔断的既有取值。 |
| C2 | **不删除既有的降级分支**。`LightRagClient` 等处的 `catch → 返回空结果` 是刻意设计，保留。 |
| C3 | 触碰任何文件前，先读该文件已有注释。本项目注释用于解释「为什么这样写」，允许改代码，但**不允许静默删除解释设计意图的注释**；行为变更必须在注释中同步说明理由。 |
| C4 | 遵循目标文件的既有代码风格。注意本项目内风格不统一：`knowledge`、`ingestion` 等模块是常规展开风格；`badcase` 包内存在高度压缩的单行风格（如 `AgentRuntimeBadCaseDetector`）。改造时与所在文件保持一致，不要顺手重排格式。 |
| C5 | 禁止引入 Micrometer / Actuator 依赖。`pom.xml` 当前没有任何指标依赖，bad case 归因是表驱动扫描，落表即可满足需求。指标能力属可选项，见 T2。 |
| C6 | 禁止改动 `AgentRunHandle`（4 处 `catch`）与 `MultiChannelRetrievalEngine.withTimeout`。它们的注释已说明「打断失败不能挡断流」「注销失败不能挡释放锁」「只放弃结果不中断执行」，属于资源生命周期与超时语义的正确防御。 |
| C7 | 数据库变更必须同时更新 `resources/database/schema_pg.sql`（全量结构）与 `resources/database/upgrades/v2.0.0/` 下的增量脚本，遵循 `docs/database/postgres.md` 的命名规则。 |
| C8 | 分阶段提交，每个任务一个 commit，便于单独回滚。不要把所有改动压成一次提交。 |

---

## 2. 事实校正（重要，先读再动手）

以下三点与直觉或与前期口头讨论不一致，但均已在当前代码中核实。

> 另有一处更隐蔽的事实（关系到整条链是否成立）：项目**不存在 conversationId 上下文**，且 Reactor 未透传 TTL，详见 T0-2b。它决定了降级记录能不能关联到会话，请务必先读那一节再动手写 Recorder。

### 2.1 `ServiceException` / `ClientException` 没有两参构造器

现状（`framework/src/main/java/com/nageoffer/ai/starlineagent/framework/exception/`）：

```java
// ServiceException
public ServiceException(String message)
public ServiceException(IErrorCode errorCode)
public ServiceException(String message, IErrorCode errorCode)
public ServiceException(String message, Throwable throwable, IErrorCode errorCode)

// ClientException —— 同样只有这四个
```

基类 `AbstractException(String message, Throwable throwable, IErrorCode errorCode)` 内部调用 `super(message, throwable)`，**三参构造器是能正确保留 `cause` 的**。

结论：不存在 `new ServiceException("msg", e)` 这种写法，直接写会编译失败。必须先按 T0-1 补两参构造器，再进行 T0-4 的批量替换。

### 2.2 项目已移除 OTel，只有轻量 `RagTraceContext`

`resources/database/upgrades/v2.0.0/261024_02_otel_trace_retire.sql` 表明 OTel 已退役，`agent` 模块的 `trace` 包已不存在。

现存能力仅 `framework/src/main/java/com/nageoffer/ai/starlineagent/framework/trace/RagTraceContext.java`，提供：

```java
getTraceId() / setTraceId(String)
getTaskId()  / setTaskId(String)
currentNodeId() / pushNode / popNode / depth()
```

**它没有存放任意属性的能力**（没有 span attributes）。因此：

- 不要设计「往 span 写 `degraded=true` 属性」的方案，无处可写。
- `RagTraceContext` 只在 RAG 链路被设置（`RagTraceNode` 注解所在位置），**Agent 链路拿不到 traceId**。降级明细表的 `trace_id` 列必须允许为 `NULL`，不能把它当主关联键。
- 关联键的优先级应为：`conversation_id`（必有）> `trace_id`（可能为空）> `run_id`（Agent 链路有）。

### 2.3 bad case 是插件式 detector 架构，不要改现有 detector

现状：

```java
// framework/.../badcase/api/BadCaseDetector.java
public interface BadCaseDetector {
    String detectorCode();
    default void checkpoint(BadCaseScanContext context) {}
    DetectorScanResult scan(BadCaseScanContext context);
    record BadCaseScanContext(Instant since, Instant until, int limit, boolean dryRun) {}
    record DetectorScanResult(List<BadCaseSignal> signals, int scanned) {}
}
```

已有实现 `AgentRuntimeBadCaseDetector`（`detectorCode()` 返回 `"agent-runtime"`），通过 `AgentBadCaseScanReader` + `JdbcTemplate` 查询游标表 `t_bad_case_detector_cursor` 增量扫描。

结论：**新增一个独立 detector**（`detectorCode()` 返回 `"degradation"`），不要去改 `AgentRuntimeBadCaseDetector` 的判定链。多 detector 并行是既有设计，新增即可自动纳入扫描。

可用的归因枚举（`rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseService.java:31`）：

```java
ROOT_CAUSES = {"RETRIEVAL_MISS","RETRIEVAL_NOISE","INTENT","HALLUCINATION",
               "FORMAT","PERF","OTHER","TOOL_EXECUTION","BUSINESS_RULE","DELIVERY"}
FIX_ACTIONS = {"MAPPING","INTENT_NODE","KNOWLEDGE_BASE","PROMPT","RERANK",
               "NONE","TOOL","BUSINESS_RULE","DELIVERY","EVAL_DATASET"}
```

---

## 3. 任务清单

依赖顺序（从左到右必须串行，括号内可并行）：

```
T0-1 → T0-2 → T0-2b → (T0-3, T0-5) → T0-6
T0-4（独立，可与 T0-1 并行）
T1-1 / T1-2 / T1-3（T0 全部合入后开始，三者互相独立）
```

其中 **T0-2b 是整条链的咽喉**：关联键取不到，T0-6 的 detector 就关联不上会话，改造退化为「攒了一堆没人看的指标」。不要跳过它的验证步骤。

### T0-1 补异常类两参构造器

**文件**

- `framework/src/main/java/com/nageoffer/ai/starlineagent/framework/exception/ServiceException.java`
- `framework/src/main/java/com/nageoffer/ai/starlineagent/framework/exception/ClientException.java`

**改法**

各新增一个两参构造器，委托到已有三参构造器，保持默认错误码语义不变：

```java
public ServiceException(String message, Throwable throwable) {
    this(message, throwable, BaseErrorCode.SERVICE_ERROR);
}
```

```java
public ClientException(String message, Throwable throwable) {
    this(message, throwable, BaseErrorCode.CLIENT_ERROR);
}
```

**注意**：`ClientException` 现有 `ClientException(String message, IErrorCode errorCode)`。新增 `(String, Throwable)` 不会与之产生歧义（`Throwable` 与 `IErrorCode` 无继承关系），但请确认编译通过。

**验收**：`./mvnw -q -DskipTests compile` 通过；写一个临时单测确认 `new ServiceException("x", new IOException("y")).getCause()` 非空（或直接在 T0-4 后的日志里验证），验完删除临时测试。

---

### T0-2 新增统一降级记录出口

**新增文件**：`framework/src/main/java/com/nageoffer/ai/starlineagent/framework/observe/DegradationRecorder.java`

**接口**

```java
/**
 * 降级统一记录出口
 *
 * 设计意图：本项目的降级分支遍布检索、改写、解析各层，原先只写一行 log.warn，
 * 故障因此变得不可计数、不可归因。所有「catch 后返回兜底值」的分支都应经此出口，
 * 保证每一次降级都留下可查询痕迹。
 *
 * 记录器自身绝不向上抛异常：可观测性组件不能反噬业务主流程，
 * 这里允许吞掉记录过程中的异常，且是项目内唯一允许静默吞异常的位置。
 */
public interface DegradationRecorder {

    /**
     * 记录一次降级，返回调用方原本要返回的兜底值，便于调用点机械替换。
     *
     * @param scene     业务场景，如 rag.retrieve、rag.rewrite、ingest.parse
     * @param component 具体组件，如 lightrag、es、mineru
     * @param cause     触发降级的异常，允许为 null（表示主动标记无有效结果）
     * @param fallback  调用方原本的兜底返回值
     */
    <T> T record(String scene, String component, Throwable cause, T fallback);

    /** 无异常但需标记本次无有效结果，如通道被配置禁用 */
    void mark(String scene, String component, String reason);
}
```

**实现要求**（`framework/.../observe/DefaultDegradationRecorder.java`，`@Component`）

1. 关联键采集策略见下方 T0-2b，**不要在这一步凭直觉写死**。
2. 落库必须异步，绝不能拖慢检索热路径。优先复用项目已有的线程池配置；若没有合适的池，用 `Schedulers.boundedElastic()`（项目已依赖 Reactor）提交，不要新建裸 `Executors`。
3. 落库失败时 `log.warn` 一行即可，不要重试、不要上抛。
4. 结构化日志格式固定为一行，便于 grep：

```
degradation scene={} component={} exception={} traceId={} conversationId={}
```

**验收**：接口与实现编译通过；在 T0-3 完成后，人为制造一次 `LightRagClient` 异常，确认日志出现上述格式且表中有对应行。

#### T0-2b 关联键可得性验证与补齐（必做，决定 T0-6 是否成立）

**先看事实**（已核实，不要再猜）：

| 事实 | 位置 | 含义 |
|---|---|---|
| 项目有 TTL 上下文基座 | `framework/context/UserContext.java`、`framework/trace/RagTraceContext.java`，均用 `TransmittableThreadLocal` | 机制存在 |
| RAG 的 5 个业务线程池已用 TTL 包装 | `rag/config/ThreadPoolExecutorConfig.java` 第 59/78/97/116/135 行 `TtlExecutors.getTtlExecutor` | RAG 内部跨池能透传 |
| **没有 Reactor 调度钩子** | 全项目 `grep onScheduleHook` 无结果 | `Schedulers.boundedElastic()` 上的 TTL **不透传** |
| `UserContext` 只在 HTTP 拦截器设置 | `system/.../UserContextInterceptor.java:75` | 异步线程取不到登录态 |
| **不存在 conversationId 上下文** | 只有 `UserContext`（登录态）与 `RagTraceContext`（traceId/taskId/节点栈） | 会话 ID 无处可取 |

而 Agent 侧调用链是：`KnowledgeSearchTool.callAsync` → `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` → `knowledgeSearchFacade.search(query)`，该工具方法内部**不持有 conversationId**（签名只有 query）。所以降级发生在 RAG 深处时，`conversation_id` 默认必然是 `NULL`。

**执行步骤**

1. **先量，不要先改**。在 `DefaultDegradationRecorder.record()` 里临时打印 `RagTraceContext.getTraceId()`、以及任何你打算作为 conversationId 来源的值，跑一次真实问答（走 Agent 链路，即 `engine.type=agent`），观察是否为 null。
2. 若 `traceId` 也为 null，说明 `AgentRunTracer` 已随 OTel 退役、且 Reactor 未透传，属于预期情况。此时按第 3 步补。
3. **补齐上下文**。已核实 `param.getRuntimeContext()` 内**没有** conversationId 常量（工具侧只有工具屏蔽等属性），所以「从工具里顺手捞一下」这条路走不通，不要在 `KnowledgeSearchTool` 里尝试。唯一现实路径是：

   **新增 `framework/observe/DegradationContext`**（`TransmittableThreadLocal`，字段 `conversationId` / `runId`），并在 Reactor 全局装一个调度钩子做 TTL 透传：

   ```java
   Schedulers.onScheduleHook("ttl-context", runnable -> {
       Object captured = TransmittableThreadLocal.Transmitter.capture();
       return () -> {
           Object backup = TransmittableThreadLocal.Transmitter.replay(captured);
           try {
               runnable.run();
           } finally {
               TransmittableThreadLocal.Transmitter.restore(backup);
           }
       };
   });
   ```

   该钩子在配置类里初始化一次。设置点在 `AgentChatServiceImpl.streamChat(question, conversationId, emitter)` 入口（方法签名已持有 `conversationId`），在订阅上游**之前** `set`，在 `doFinally` 中 `remove`，避免线程复用时串号。

   **副作用提示**：此钩子会让 `UserContext` 一并透传到 Reactor 线程。这通常是期望行为（异步链路也能取到登录态），但属行为面变更，请在 PR 描述中注明，并顺带确认不会影响任何依赖「异步线程无登录态」的既有判断。

   若评估后认为全局钩子风险过大，可退让为「只把 `conversationId` 记在 `RagTraceContext` 的旁路字段上」——但那需要改 `RagTraceContext` 的职责边界，等于把 trace 上下文当成业务上下文用，**不推荐**。宁可暂时接受 `conversation_id` 为 null，只拿「按场景统计降级频次」这半收益，也不要把语义搞混。

4. 无论哪个方案，`record()` 都必须**允许关联键为 null**，不能因为取不到上下文就抛异常或跳过写入。表里已有独立索引，null 行仍可用于「按场景统计降级频次」这一半收益。

**验收**：走一次 Agent 链路的真实问答（期间制造一次 `LightRagClient` 失败），`t_degradation_record` 中该行 `conversation_id` **非 null**。若为 null，T0-6 必然失效，不要继续往下做。

---

### T0-3 改造 15 个检索/改写降级点

统一改法（以 `LightRagClient` 为例）：

```java
// 改前 —— rag/core/graph/LightRagClient.java:103
} catch (Exception e) {
    log.warn("LightRAG 检索失败，降级为空结果: {}", e.getMessage());
    return GraphEvidence.empty();
}

// 改后
} catch (Exception e) {
    return degradationRecorder.record("rag.retrieve", "lightrag", e, GraphEvidence.empty());
}
```

原 `log.warn` 的内容由 Recorder 统一输出，调用点不再重复打日志。

**需要改造的确切位置**（行号为编写时基线，动手前请用 `grep -n "catch (Exception" <file>` 复核）

| 文件 | 行 | 所在方法 | scene / component 建议 | 原兜底值 |
|---|---|---|---|---|
| `rag/core/graph/LightRagClient.java` | 103 | `retrieveByScope` | `rag.retrieve` / `lightrag` | `GraphEvidence.empty()` |
| 同上 | 131 | `fetchGraph` | `rag.graph` / `lightrag-fetch-graph` | `null` |
| 同上 | 172 | `fetchLabels` | `rag.graph` / `lightrag-fetch-labels` | `List.of()` |
| 同上 | 205 | `insertText` | `rag.graph` / `lightrag-insert` | 无返回（`void`，改用 `mark`） |
| 同上 | 273 | `deleteMatching` | `rag.graph` / `lightrag-delete-matching` | 无返回（`void`，改用 `mark`） |
| `rag/core/keyword/EsKeywordIndexService.java` | 85 | `rag.index` / `es-bulk` | 无返回（`mark`） |
| 同上 | 106 | `rag.index` / `es-delete-doc` | 无返回（`mark`） |
| 同上 | 121 | `rag.index` / `es-delete-chunk` | 无返回（`mark`） |
| 同上 | 143 | `rag.index` / `es-delete-chunks` | 无返回（`mark`） |
| 同上 | 161 | `rag.index` / `es-delete-collection` | 无返回（`mark`） |
| 同上 | 209 | `rag.index` / `es-ensure-index` | 无返回（`mark`） |
| `rag/core/rewrite/MultiQuestionRewriteService.java` | 103 | `rag.rewrite` / `llm` | `fallback`（保留原变量） |
| 同上 | 205 | `rag.rewrite` / `llm-parse` | `null`（解析失败） |
| `rag/core/vector/strategy/CollectionParallelRetriever.java` | 85 | `rag.retrieve` / `collection` | 见原代码 |
| 同上 | 114 | `rag.retrieve` / `collection-parallel` | `List.of()` |

**特别注意 `MultiQuestionRewriteService:103`**：该处已有 `RewriteResult fallback` 变量，且注释写明「档位内多候选已提供传输容错，不再跨档升级」。改造时**必须保留这条注释**，只把 `log.warn` + `result = fallback` 替换为 `result = degradationRecorder.record(..., fallback)`，不要动档位逻辑。

`EsKeywordIndexService` 的多数方法返回 `void`，用 `mark(...)` 重载即可；若某处 `catch` 里需要区分「索引不存在」（`isNotFound(e)` 已判定为正常情况）与真实故障，**只在真实故障分支记录**，`isNotFound` 为真的分支保持静默。

**特别注意 `CollectionParallelRetriever` 两处，性质不同，别一起改**：

- **第 114 行（`retrieveOne`）**：catch 内已经是 `log.error("在 collection {} 中检索失败，错误: {}", collectionName, e.getMessage(), e)`，**带完整堆栈**，是项目里少数写对的降级日志。改造时保留这条 `log.error`（它带 collection 维度和堆栈，Recorder 无法替代），只在其后再补一句 `record(...)`。
- **第 85 行（`executeParallelRetrieval`）**：catch 内是 `failureCount++`，说明该方法已经在区分「部分集合失败」与「全部失败」。这个计数器是既有的降级统计雏形，**必须保留**，`record(...)` 与之并存，不要用 Recorder 替换 `failureCount`，也不要改变后续基于 `failureCount` 的判定逻辑（可能有「全失败才抛异常」的分支，改动会变行为，违反 C1）。

**验收**

- 15 处全部改造，无残留的裸 `log.warn(...降级...)` + `return 兜底` 组合。
- 行为不变：改造前后跑同一组检索单测，结果一致。

---

### T0-4 修复异常包装丢失 cause（16 处 + 4 处特殊）

> 下表是精确匹配 `"..." + e.getMessage()` 写法得到的 16 处，可直接机械替换。动手前先跑一次下面的自查命令，确认没有新增遗漏；若命中数多于 16，以命令结果为准，全部按同一规则处理。
>
> ```bash
> grep -rn --include="*.java" -E 'throw new (Service|Client)Exception\("[^"]*" ?\+ ?e\.getMessage\(\)\)' \
>   rag/src/main/java agent/src/main/java infra-ai/src/main/java system/src/main/java
> ```
>
> 另有若干处 `catch` 后 new 异常时既没拼 message 也没传 cause，信息丢失更彻底，已单列为下方「特殊 4 处」。这两类之外若还发现其他 `catch` 内包装，按同样原则处理：**有 cause 必传 cause**。

**通用改法**

```java
// 改前
} catch (Exception e) {
    throw new ServiceException("网络请求失败: " + e.getMessage());
}

// 改后 —— 保留 message 文案，补上 cause
} catch (Exception e) {
    throw new ServiceException("网络请求失败: " + e.getMessage(), e);
}
```

**标准 16 处**（`e.getMessage()` 拼接型）

| 文件（相对 `rag/src/main/java/com/nageoffer/ai/starlineagent/`） | 行 |
|---|---|
| `core/parser/excel/ExcelDocumentParser.java` | 107 |
| `core/parser/image/ImageDocumentParser.java` | 158 |
| `core/parser/mineru/MinerUClient.java` | 151 |
| `core/parser/mineru/MinerUClient.java` | 209 |
| `core/parser/mineru/MinerUResultUnpacker.java` | 183 |
| `core/parser/TikaDocumentParser.java` | 76 |
| `ingestion/service/impl/IngestionTaskServiceImpl.java` | 133 |
| `ingestion/strategy/fetcher/FeishuFetcher.java` | 153 |
| `ingestion/util/HttpClientHelper.java` | 82 |
| `ingestion/util/HttpClientHelper.java` | 116 |
| `ingestion/util/HttpClientHelper.java` | 137 |
| `knowledge/handler/RemoteFileFetcher.java` | 121 |
| `knowledge/handler/RemoteFileFetcher.java` | 156 |
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | 548 |
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | 894 |
| `knowledge/support/IngestionSpecCodec.java` | 116 |

**特殊 4 处**（`e.getMessage()` 都没拼，信息丢失更严重，需单独判断）

| 位置 | 现状 | 处理 |
|---|---|---|
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java:342` | `throw new ServiceException("读取文件内容失败：docId=" + id)` | 补 `, e`。这是读文件失败，根因（OSS 403 / 磁盘满 / 路径不存在）必须保留 |
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java:855` | `throw new ClientException("读取待预览文件失败")` | 补 `, e` |
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java:518` | `catch (Exception e) → ClientException("指定的Pipeline不存在: " + id)` | **见 T1-1，此处的 catch 整体要删，不要在这里补 cause** |
| `knowledge/service/impl/KnowledgeDocumentServiceImpl.java:826` | 同上（重复代码） | **同上** |

**验证方式（必须做）**：改造后在本地构造一次失败（例如把 `minio`/OSS 地址改成不可达），触发一次文档解析，确认日志里出现嵌套堆栈且 `GlobalExceptionHandler` 走了 `ex.getCause() != null` 分支（该分支打的是完整 `ex` + `ex.getCause()`），而不是「手拼 5 层栈」分支。

**验收**：`grep -rn --include="*.java" "Exception(\"[^\"]*\" + e.getMessage())" rag/src/main/java` 结果为空；上述手工验证通过。

---

### T0-5 建降级明细表

**新增脚本**：`resources/database/upgrades/v2.0.0/261029_01_degradation_record.sql`
（同日若无其他脚本可省略 `_01_`，按 `docs/database/postgres.md` 规则确认）

**建表 DDL**

```sql
CREATE TABLE IF NOT EXISTS t_degradation_record (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    scene           VARCHAR(64)  NOT NULL,
    component       VARCHAR(64)  NOT NULL,
    reason          VARCHAR(255),
    exception_type  VARCHAR(255),
    exception_msg   VARCHAR(1024),
    trace_id        VARCHAR(64),
    conversation_id VARCHAR(20),
    run_id          VARCHAR(64),
    fallback_kind   VARCHAR(32),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_degradation_trace
    ON t_degradation_record (trace_id);
CREATE INDEX IF NOT EXISTS idx_degradation_conversation
    ON t_degradation_record (conversation_id);
CREATE INDEX IF NOT EXISTS idx_degradation_time
    ON t_degradation_record (create_time);
```

**说明**

- `trace_id` 允许 NULL（原因见 2.2）。
- 不建外键：降级可能发生在文档摄取等无会话场景，关联不存在不能阻挡写入。
- 不做分区、不建保留策略，先观察数据量再定。若担心增长，T2 再议。

**同步**：把同一段 DDL 追加进 `resources/database/schema_pg.sql`，位置紧邻 `t_bad_case` 相关表，保持文件内表定义顺序的既有习惯。

**写入实现**：在 `DefaultDegradationRecorder` 中用 `JdbcTemplate`（项目 bad case 侧已用 `JdbcTemplate`，可直接沿用该选型），`id` 生成方式与项目其他表保持一致（参考 `BadCaseService` 的 ID 生成逻辑，不要自创）。

**验收**：新库执行 `schema_pg.sql` 后表存在；旧库执行增量脚本后表存在；`insert` 一条后三个索引可命中。

---

### T0-6 新增降级 detector 并接入归因

**新增文件**：`agent/src/main/java/com/nageoffer/ai/starlineagent/agent/badcase/DegradationBadCaseDetector.java`

**要求**

1. `detectorCode()` 返回 `"degradation"`。
2. 仿照 `AgentRuntimeBadCaseDetector` 的写法：注入 `AgentBadCaseScanReader`，用 `read("degradation", select, context)` 增量扫描 `t_degradation_record`，靠 `t_bad_case_detector_cursor` 断点续扫。
3. 每个降级行产出一条 `BadCaseSignal`。构造器参数顺序（15 个，`record` 定义见 `framework/.../badcase/api/BadCaseSignal.java`）：

```java
new BadCaseSignal(
    "DEGRADATION",              // source
    scene + ":" + component,    // ruleCode
    "MEDIUM",                   // confidence：降级不等于答案一定错，取 MEDIUM
    "S3",                       // severity：参考 agent-runtime 的 S2/S4 分级，降级取 S3
    "CONVERSATION",             // subjectType
    conversationId,             // subjectId
    null,                       // messageId：降级发生在消息粒度之前，可能为空
    conversationId,
    null,                       // userId：表中未存，留空
    runId,                      // runId
    "degradation:" + id,        // occurrenceKey：保证幂等
    "检索降级：" + scene + "/" + component,
    createTime.toInstant(),
    "degradation-v1",           // detectorVersion
    Map.of("scene", scene, "component", component,
           "fallbackKind", fallbackKind,
           "exceptionType", String.valueOf(exceptionType))
);
```

4. **不要**在 detector 里直接修改 `t_bad_case.root_cause`。detector 只负责产出 signal 供人工/自动评审分诊。若后续确需自动映射，映射建议为：`rag.retrieve` → `RETRIEVAL_MISS`，`rag.index` → `RETRIEVAL_NOISE`，`ingest.parse` → `TOOL_EXECUTION`，`llm` 超时 → `PERF`。此映射为评审参考，不写进代码硬逻辑。

**验收**

1. 本地起服务，制造一次 `LightRagClient` 失败（把 `rag.graph` 指向不通地址）。
2. 触发一次检索。
3. 调用 bad case 扫描接口（`dryRun=false`）。
4. 确认 `t_bad_case_candidate_pool`（或对应候选表）出现 `source=DEGRADATION` 的记录，且 `ruleCode` 正确。
5. 重复扫描一次，确认不产生重复候选（`occurrenceKey` 幂等生效）。

---

### T1-1 删除重复兜底并把基础设施故障放出去

**文件**：`rag/src/main/java/com/nageoffer/ai/starlineagent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

**现状问题**：`ProcessMode` 校验逻辑在第 509-522 行与第 817-830 行重复两遍，且外层用 `catch (Exception)` 把一切异常翻译成 `ClientException("指定的Pipeline不存在")`。而 `IngestionPipelineServiceImpl.get():132` 内部已有：

```java
Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
```

即内层已给出精确业务异常，外层再兜一次，会把 DB 连接失败、jsonb 反序列化错误、Mapper 配置错误全部伪装成「用户参数错误」，导致 4xx 监控看不到真实故障。

**改法**

1. 抽一个私有方法（命名参考文件内既有风格，如 `resolveProcessModeConfig`），供两处调用，消除重复。
2. 删除外层 `try { pipelineService.get(id) } catch (Exception e) { throw new ClientException(...) }`，直接调用。`get()` 为空时它自己会抛 `ClientException("未找到流水线")`，语义更准确。
3. 若希望保留「指定的Pipeline不存在 + ID」这条更友好的文案，正确做法是在 `IngestionPipelineServiceImpl.get()` 里调整异常文案，**而不是**在外层用宽泛 catch 重写。

**验收**

- 两处重复代码合并为一处。
- 该文件内 `catch (Exception` 数量从 6 降到 5 或更少。
- 用不存在的 pipelineId 请求，仍返回 4xx 且提示「流水线不存在」类文案（文案可能由内层决定，确认前端可接受）。
- 手工验证：断开数据库后请求该接口，应返回 5xx 而非 4xx。

---

### T1-2 用 ArchUnit 固化约束

**前置**：确认 `pom.xml` 是否已有 ArchUnit 依赖；没有则加 `test` scope 依赖，版本与项目 Spring Boot 版本兼容。

**新增测试**：`framework/src/test/java/.../architecture/DegradationArchitectureTest.java`（若 framework 无 test 目录则放 `rag/src/test`，以能覆盖全部模块为准，注意 ArchUnit 需扫描多个包的类路径）

**规则**

1. **禁止 service 层宽泛捕获后静默降级**：`..service..` 包下的类，若出现 `catch (Exception|Throwable)`，块内必须出现对 `DegradationRecorder` 的调用，否则违规。
2. **禁止异常包装丢 cause**：除 `..framework..` 与 `..observe..` 包外，不允许 `new ServiceException(...)` / `new ClientException(...)` 出现在 `catch` 块中却不传 `Throwable` 实参。
3. **记录器豁免**：`..framework.observe..` 包整体豁免以上两条（Recorder 自身允许吞异常，见 T0-2 注释）。

**实现提示**：第 1、2 条用 `ArchCondition` 扫描方法体内的 `try/catch` 需要读字节码，ArchUnit 原生能力有限。务实做法：用 ArchUnit 覆盖「禁止 `catch` 后仅调用 `Logger.warn`」（可通过 `noClasses().should().callMethod(Logger.class, "warn", String.class)` 做粗粒度约束，配合白名单），再补一个基于源码文本的轻量单测（扫描 `src/main/java` 下文件内容，正则检出 `catch (Exception` 后 5 行内既无 `degradationRecorder` 也无 `throw`）。**两种都行，要求是违规时构建失败**，不要做成只在 report 里告警。

**验收**

- 故意在某个 service 里加一个裸 `catch (Exception e) { log.warn("x"); }`，构建失败。
- 恢复后构建通过。
- 白名单文件（`AgentRunHandle`、`MultiChannelRetrievalEngine`、`DefaultDegradationRecorder`）不误报。

---

### T1-3 清理冗余集合判空

**范围**：全项目 53 处 `x == null || x.isEmpty()` / `x != null && !x.isEmpty()`。

**分两类处理，不要一刀切删除**

| 类别 | 判断标准 | 处理 |
|---|---|---|
| 冗余 | 校验的是本方法内 `new` 出来的集合，或上游方法签名已用 `@NotNull` / `@NotEmpty` 声明 | 删除判空，直接使用 |
| 必要 | 校验跨模块/跨进程入参，或来源是第三方返回值、JSON 反序列化结果 | 保留，并考虑在接口签名上补 `@NotEmpty` 把契约前移 |

**已知的三层连续冗余示例**（同一份 `blocks` 被判空三次）：

- `rag/core/chunk/ChunkingService.java:58`
- `rag/core/chunk/blockaware/BlockAwareChunkerDispatcher.java:70`
- `rag/core/chunk/model/ChunkAssembler.java:45`

保留最外层（`ChunkingService`，真正的入口契约），内层两处删除。若内层方法可被其他路径调用，改为 `Assert.notEmpty(...)` 显式声明前置条件，比静默 `return` 更能暴露调用错误。

**验收**：改动处均有注释或断言说明契约来源；相关 chunk 单测全绿。

---

## 4. 全局验收

按顺序执行，每步必须通过：

```bash
# 1. 编译
./mvnw -q -DskipTests compile

# 2. 单测（重点看 chunk / parser / retrieval / badcase）
./mvnw -q test

# 3. 残留检查：应无输出
grep -rn --include="*.java" "Exception(\"[^\"]*\" + e.getMessage())" rag/src/main/java agent/src/main/java

# 4. 残留检查：列出 catch(Exception) 后 5 行内含 log.warn 的位置，逐条人工确认
#    预期结果：白名单（AgentRunHandle / MultiChannelRetrievalEngine / DefaultDegradationRecorder）
#    之外，不存在「只 log.warn 既不 throw 也不 record」的分支
grep -rn --include="*.java" -A5 "catch (Exception" agent/src/main/java rag/src/main/java \
  | grep "log.warn" | wc -l

# 5. 该检查在 T1-2 完成后由架构测试自动接管，命令行统计仅作参考
```

**端到端验证（最重要，别跳过）**

1. 把 `rag.graph` 指向一个不通的地址。
2. 启动服务，发一句明确依赖知识库的问题（参考 `resources/regression/` 下已有用例）。
3. 检查三件事：
   - 服务**正常回答**（降级未改变行为，C1 约束）；
   - `t_degradation_record` 有 `scene=rag.retrieve, component=lightrag` 的记录；
   - 跑 bad case 扫描后，该会话被标记为 `source=DEGRADATION`。

> 这项验证是本次改造唯一的收益兑现点。如果第 3 步不成立，说明 T0-6 没接上，前面的改造只是在攒没人看的指标。

---

## 5. 提交建议

| 顺序 | commit message 建议 |
|---|---|
| 1 | `feat(framework): 为业务异常补两参构造器以保留 cause` |
| 2 | `feat(framework): 新增 DegradationRecorder 统一降级记录出口` |
| 3 | `feat(observe): 补会话上下文以确保降级记录可关联到会话`（T0-2b；含 Reactor 调度钩子，须在 PR 描述注明 `UserContext` 一并透传的行为变更） |
| 4 | `refactor(rag): 检索与改写降级点统一走 DegradationRecorder` |
| 5 | `fix: 修复异常包装丢失 cause 导致根因栈不可见` |
| 6 | `feat(db): 新增 t_degradation_record 降级明细表` |
| 7 | `feat(badcase): 新增 degradation detector 接入降级归因` |
| 8 | `refactor(knowledge): 合并重复的 ProcessMode 校验，放行基础设施异常` |
| 9 | `test(arch): 用架构测试固化降级与异常包装约束` |
| 10 | `refactor(chunk): 清理冗余集合判空，契约前移至断言` |

第 1-7 属 T0，建议一次评审合入（T0-2b 与 T0-6 必须一起验）；T1 三项各自独立提交，可分批。

前 6 个属 T0，建议一次评审合入；T1 三项各自独立提交，可分批。

---

## 6. 附录：现状基线（复跑用）

改造完成后用同一组命令复跑，对比数字：

```bash
# catch 总数
grep -rn --include="*.java" "catch (" agent/src/main/java rag/src/main/java \
  infra-ai/src/main/java system/src/main/java framework/src/main/java mcp-server/src/main/java | wc -l

# 宽泛捕获
grep -rn --include="*.java" "catch (Exception\|catch (Throwable" agent/src/main/java \
  rag/src/main/java infra-ai/src/main/java system/src/main/java framework/src/main/java \
  mcp-server/src/main/java | wc -l

# 指标依赖（改造后仍应为 0，本次不引入）
grep -rn "micrometer\|actuator" --include="pom.xml" . | grep -v local-backups | wc -l
```

编写时基线：

| 指标 | 值 |
|---|---|
| 生产代码 | 629 文件 / 62,970 行 |
| `catch` 总数 | 227 |
| `catch (Exception\|Throwable)` | 130 |
| `catch → log.warn → 继续` | 34 |
| `catch → 返回默认值` | 25+ |
| 包装丢 `cause` | 16（`e.getMessage()` 型）+ 4（特殊） |
| 指标打点 | 0 |

改造后预期：`catch` 总数略降（T1-1 删除 1-2 处），宽泛捕获中「有 `record` 调用」的占比从 0 提升到 T0-3 覆盖的 15 处，包装丢 `cause` 归零。

---

## 7. 暂不执行（T2，等 T0/T1 稳定后另议）

记录在此供决策，**本轮不要做**：

1. **引入 Micrometer 指标**。当前无依赖，且归因链路走表，引入收益不明显。若将来接入 Prometheus，再为 `DegradationRecorder` 补 `Counter`。
2. **`@Degradable` 注解 + 编译期白名单**。把「哪些故障可以容忍」变成可评审清单，但需要先积累一段真实降级数据，才知道白名单该收多紧。
3. **降级结果带 `reason` 枚举**（`TIMEOUT` / `ERROR` / `EMPTY`）打通到 `MultiChannelRetrievalEngine` 的融合统计。该引擎第 201 行注释已指出「超时放弃」与「秒回无料」处置方向相反，本次表里已有 `exception_type` 字段可先支撑查询，无需改动融合逻辑。
4. **降级明细的保留与归档策略**。观察真实写入量后再定。
