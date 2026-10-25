# MCP 调用改造方案：Tool RAG + 懒加载，意图树回归纯知识路由

> **目标**
>
> 1. MCP 工具改由独立注册表管理，**不再挂在意图树上**
> 2. 引入 **Tool RAG（语义 + 关键词召回）+ 懒加载（按需给 schema）** 控制上下文
> 3. 删除 `resolveMcpTools()` 的「意图树 ∩ 服务端」取交集逻辑
> 4. 清理 workflow 时代的 MCP 残留
> 5. MCP 调用完全走 Agent 流（模型自主选择，不经意图分类）
>
> **核查日期**：*2026*-10-25。基于 `AgentToolCatalog` / `McpToolProxy` / `AgentMcpClients` / `DefaultIntentClassifier` / `AgentSkillAdminServiceImpl` / `IntentTreeServiceImpl` 源码实地核实。**本次未修改任何项目文件。**

---

## 0. 两处必须先纠正的事实

### 0.1 「workflow 调用 MCP 的原来的逻辑」——不存在

我 grep 了 `rag/`、`framework/`、`system/`、`starline/` 四个模块，**零处**引用 `McpClient` / `callTool` / `McpToolProxy`。

**workflow 时代的 MCP 调用代码已经被删干净了。** 现在只剩一处**死条件**：

```java
// DefaultIntentClassifier.java:161-164
List<IntentNode> candidates = data.leafNodes.stream()
        .filter(node -> node.isKB()
                || (orchestrationProperties.getMode() == OrchestrationMode.WORKFLOW && node.isSystem()))
        .toList();
```

因为 `application.yaml` 里 `engine.type: agent` 是唯一入口，`WORKFLOW` 分支永远不成立。**这是要清理的，但它只是两行条件，不是「一套逻辑」。**

### 0.2 有一处真实依赖被漏掉了：技能管理

`AgentSkillAdminServiceImpl` **两处**依赖 `listMcpToolNodes()`：

```java
// 第 209 行：给技能管理页下拉框提供可选工具
public List<AgentSkillToolOptionVO> listToolOptions() {
    for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) {
        options.add(... .toolId(node.getMcpToolId()) ...);
    }
}

// 第 248 行：校验技能绑定的工具是否合法
private List<String> validateToolIds(List<String> toolIds) {
    intentNodeRegistry.listMcpToolNodes().forEach(node -> available.add(...));
}
```

**注释写得很清楚**：

> 工具必须是意图树里已启用的 MCP 节点：这批工具才是接入方真正开放给 Agent 的

**这是本次改造最大的隐性成本**：去掉意图树 MCP 节点后，技能与工具的绑定必须重新指向新注册表，否则「技能解锁工具」链路（`AgentSkillMaskingMiddleware`）会失效。

---

## 1. 目标架构

### 1.1 三层职责重新划分

| 层           | 数据源                         | 职责                         | 谁读                        |
| ----------- | --------------------------- | -------------------------- | ------------------------- |
| **意图树**     | `t_intent_node`（仅 kind=0/1） | 知识库检索路由：这个问题查哪个 Collection | `DefaultIntentClassifier` |
| **MCP 注册表** | `t_mcp_tool`（新表）            | 工具元数据：描述、schema、确认策略、常驻标记  | `McpToolRegistry`         |
| **运行时注入**   | 注册表 + 召回结果                  | 每次请求决定哪几个工具进 Toolkit       | `AgentToolCatalog`        |

### 1.2 为什么召回 + 懒加载要拆成两层

这是本方案的关键设计，两者解决不同问题，**不能合并**：

```
Tool RAG（召回）  →  从 N 个工具里挑出相关的 3-8 个
                     ↓
懒加载（按需 schema）→  这 3-8 个里，先只给名字，模型真要用再调 tool_search 取 schema
                     ↓
最终注册          →  只把命中且被请求的工具定义交给模型
```

**如果只做召回不做懒加载**：命中的 8 个工具 schema 全进上下文，8 × 120 token ≈ 1k，比全量 60 个（8k）省，但仍不彻底。  
**如果只做懒加载不做召回**：60 个工具名仍要全给，名字虽短但"选择面"没缩小，选错率不降。

**两者组合**：上下文从 8k 降到 600（名字）+ 按需 2 个 schema（240），同时候选面从 60 缩到 8。

### 1.3 一个反直觉但必须的设计：常驻工具

**永远保留 2-3 个高频工具不参与召回、直接全 schema 注册。**

理由：召回本身要一次 embedding + 一次 LLM 判断，如果用户问的恰好是最高频场景（查演出），这个开销纯属浪费。

```yaml
agent:
  mcp:
    tool-rag:
      resident-tools:        # 常驻：直接全 schema 注册，不参与召回
        - search_artist_events
        - current_date
      recall-top-k: 5        # 召回多少个
      recall-min-score: 0.35 # 低于此分不召回
      lazy-load: true        # 召回的是否延迟加载 schema
```

---

## 2. 数据表设计

### 2.1 新增 `t_mcp_tool`

```sql
CREATE TABLE IF NOT EXISTS t_mcp_tool (
    id                VARCHAR(20)  NOT NULL PRIMARY KEY,
    tool_id           VARCHAR(128) NOT NULL,          -- MCP 工具名，全局唯一

    -- 展示与检索
    display_name      VARCHAR(64),
    description       TEXT         NOT NULL,          -- 用于向量化与关键词检索，也是给模型看的描述
    description_hash  VARCHAR(64),                    -- 描述指纹，变了才重建向量

    -- 策略
    require_confirm   SMALLINT     NOT NULL DEFAULT 0, -- 0=不加严（仍受服务端 readOnlyHint 约束）1=强制确认
    resident          SMALLINT     NOT NULL DEFAULT 0, -- 1=常驻，不参与召回
    dispatchable      SMALLINT     NOT NULL DEFAULT 1, -- 0=不暴露给模型（保留但停用）

    -- 治理
    enabled           SMALLINT     NOT NULL DEFAULT 1,
    sort_order        INTEGER      NOT NULL DEFAULT 900,
    remark            VARCHAR(255),
    create_by         VARCHAR(20),
    update_by         VARCHAR(20),
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT uk_mcp_tool UNIQUE (tool_id)
);

CREATE INDEX IF NOT EXISTS idx_mcp_tool_enabled
    ON t_mcp_tool (enabled, deleted);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_resident
    ON t_mcp_tool (resident, enabled) WHERE deleted = 0;

COMMENT ON TABLE t_mcp_tool IS 'MCP 工具注册表，与意图树解耦；工具授权与召回都以本表为准';
COMMENT ON COLUMN t_mcp_tool.description IS '既是召回检索语料，也是发给模型的工具描述；改这里不用改代码';
COMMENT ON COLUMN t_mcp_tool.description_hash IS 'description 的 SHA-256，用于判断是否需要重建向量';
COMMENT ON COLUMN t_mcp_tool.resident IS '1=常驻注册不参与召回，用于高频工具省掉一次召回开销';
```

### 2.2 新增 `t_mcp_tool_vector`

向量单独一表，避免主表变宽、也便于更换 embedding 模型时重建。

```sql
CREATE TABLE IF NOT EXISTS t_mcp_tool_vector (
    tool_id        VARCHAR(128) NOT NULL PRIMARY KEY,
    model_id       VARCHAR(64)  NOT NULL,     -- 哪个模型算的，换模型要全量重建
    dimension      INTEGER      NOT NULL,
    embedding      BYTEA        NOT NULL,     -- float[] 序列化，1024 维约 4KB
    description_hash VARCHAR(64) NOT NULL,    -- 与主表对齐，不一致则视为脏数据
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_mcp_tool_vector IS 'MCP 工具描述向量；工具量小（几十个）放 pg 即可，不必上 pgvector';
```

> **为什么不用 pgvector**：工具量级是几十个，`BYTEA` 存 float[] + Java 内存算余弦就够。上 pgvector 需要装扩展、建索引，收益为零。

### 2.3 现有意图树数据迁移

```sql
-- 把现有 kind=2 节点迁到新表（保留人工写过的 description 与 require_confirm）
INSERT INTO t_mcp_tool (id, tool_id, display_name, description, require_confirm,
                        resident, dispatchable, enabled, sort_order, remark)
SELECT
    id,
    mcp_tool_id,
    name,
    COALESCE(NULLIF(TRIM(description), ''),
             '（待补充描述，当前用服务端 description）'),
    require_confirm,
    CASE WHEN mcp_tool_id IN ('search_artist_events', 'current_date') THEN 1 ELSE 0 END,
    1,
    enabled,
    sort_order,
    'migrated from t_intent_node kind=2'
FROM t_intent_node
WHERE kind = 2 AND deleted = 0 AND mcp_tool_id IS NOT NULL
ON CONFLICT (tool_id) DO NOTHING;

-- 意图树里只保留知识库与系统节点
UPDATE t_intent_node SET deleted = 1, update_time = CURRENT_TIMESTAMP
WHERE kind = 2 AND deleted = 0;
```

**回滚脚本**（万一要退）：`t_intent_node` 只是软删，`deleted=0` 即可恢复；`t_mcp_tool` 直接 `DROP`。

---

## 3. 核心组件

### 3.1 `McpToolRegistry` —— 工具注册表

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * MCP 工具注册表：工具元数据的唯一来源，取代原意图树 kind=2 节点
 * <p>
 * 职责边界：只回答「有哪些工具、每个工具长什么样」，
 * 不参与召回决策（那是 ToolRagService 的事），也不参与工具选择（那是模型的事）
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class McpToolRegistry {

    private final McpToolMapper mapper;
    private final AgentMcpClients mcpClients;

    private volatile Map<String, Entry> cache;

    /**
     * @param schemaText 服务端 schema 的可读文本，懒加载时返回给模型
     */
    public record Entry(
            String toolId,
            String displayName,
            String description,
            String schemaText,
            boolean readOnly,
            boolean needsConfirm,
            boolean resident) {}

    public Map<String, Entry> all() {
        Map<String, Entry> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = build();
                    cache = current;
                }
            }
        }
        return current;
    }

    private Map<String, Entry> build() {
        List<McpToolDO> rows = mapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getEnabled, 1)
                .eq(McpToolDO::getDeleted, 0)
                .orderByAsc(McpToolDO::getSortOrder));

        Map<String, Entry> result = new LinkedHashMap<>();
        for (McpToolDO row : rows) {
            AgentMcpClients.RemoteTool remote = mcpClients.get(row.getToolId());
            if (remote == null) {
                // 注册表有但服务端没有：不阻塞启动，只记录，等下次服务端恢复
                log.warn("MCP 工具在注册表中但服务端未发现, toolId={}", row.getToolId());
                continue;
            }
            Tool definition = remote.definition();
            boolean readOnly = definition.annotations() != null
                    && Boolean.TRUE.equals(definition.annotations().readOnlyHint());
            result.put(row.getToolId(), new Entry(
                    row.getToolId(),
                    StrUtil.blankToDefault(row.getDisplayName(), row.getToolId()),
                    row.getDescription(),
                    renderSchema(definition.inputSchema()),
                    readOnly,
                    row.getRequireConfirm() != null && row.getRequireConfirm() == 1,
                    row.getResident() != null && row.getResident() == 1));
        }
        log.info("MCP 工具注册表加载完成, size={}", result.size());
        return Map.copyOf(result);
    }

    /** 注册表变更后由管理端调用 */
    public void invalidate() {
        cache = null;
    }

    public List<Entry> residentTools() {
        return all().values().stream().filter(Entry::resident).toList();
    }

    private String renderSchema(JsonSchema schema) {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

### 3.2 `ToolRagService` —— 召回（向量 + 关键词混合）

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * 工具召回：从注册表里挑出与当前问题相关的工具
 * <p>
 * 只做召回，不做决策——召回结果交给模型，由模型最终选择调哪个。
 * 语义相似 ≠ 该用这个工具（「帮我盯着开票」语义最像查询工具，实际该用创建提醒）
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class ToolRagService {

    private final McpToolRegistry registry;
    private final McpToolVectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final AgentMcpProperties properties;

    /**
     * @param question 用户本轮问题（已消解指代）
     * @return 召回的候选工具 + 常驻工具，常驻在前
     */
    public RecallResult recall(String question) {
        ToolRagConfig cfg = properties.getToolRag();
        List<McpToolRegistry.Entry> residents = registry.residentTools();

        if (!cfg.isEnabled() || StrUtil.isBlank(question)) {
            // 关闭或缺问题：退化为全量（保持改造前行为，便于灰度）
            List<McpToolRegistry.Entry> all = new ArrayList<>(registry.all().values());
            return new RecallResult(all, all, false);
        }

        // 候选池 = 全部 - 常驻（常驻已直接注册，不参与召回）
        Map<String, McpToolRegistry.Entry> candidates = new LinkedHashMap<>(registry.all());
        residents.forEach(e -> candidates.remove(e.toolId()));
        if (candidates.isEmpty()) {
            return new RecallResult(residents, List.of(), true);
        }

        Map<String, Double> scores = new LinkedHashMap<>();

        // ① 向量召回
        if (cfg.isVectorEnabled()) {
            try {
                float[] queryVector = toFloatArray(embeddingService.embed(question));
                vectorStore.topK(queryVector, candidates.keySet(), cfg.getRecallTopK() * 2)
                        .forEach((toolId, score) -> scores.merge(toolId, score, Double::sum));
            } catch (Exception e) {
                // 向量召回失败不能影响主链路：降级为纯关键词
                log.warn("工具向量召回失败，降级为关键词召回", e);
            }
        }

        // ② 关键词召回（BM25 近似：名字命中权重高）
        keywordScores(question, candidates).forEach((toolId, score) ->
                scores.merge(toolId, score, Double::sum));

        List<McpToolRegistry.Entry> recalled = scores.entrySet().stream()
                .filter(e -> e.getValue() >= cfg.getRecallMinScore())
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(cfg.getRecallTopK())
                .map(e -> candidates.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();

        // 一个都没召回时兜底给全量：宁可多花 token，也不能让模型无工具可用
        if (recalled.isEmpty()) {
            log.warn("工具召回为空，本轮退化为全量注册, question={}", StrUtil.preview(question));
            List<McpToolRegistry.Entry> all = new ArrayList<>(registry.all().values());
            return new RecallResult(all, all, false);
        }

        log.info("工具召回完成, question={}, 常驻={}, 召回={}",
                StrUtil.preview(question), residents.size(), recalled.size());
        return new RecallResult(residents, recalled, true);
    }

    /**
     * 关键词打分：工具名命中权重 3，描述命中权重 1
     * <p>
     * 名字是规范化的「动词_对象」结构，命中即强信号；描述容易误命中
     */
    private Map<String, Double> keywordScores(String question, Map<String, McpToolRegistry.Entry> candidates) {
        Set<String> tokens = tokenize(question);
        if (tokens.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (McpToolRegistry.Entry entry : candidates.values()) {
            String name = (entry.toolId() + " " + entry.displayName()).toLowerCase(Locale.ROOT);
            String body = (name + " " + entry.description()).toLowerCase(Locale.ROOT);
            double score = 0;
            for (String token : tokens) {
                if (name.contains(token)) {
                    score += 3;
                } else if (body.contains(token)) {
                    score += 1;
                }
            }
            if (score > 0) {
                scores.put(entry.toolId(), score);
            }
        }
        return scores;
    }

    /** 中文二元组 + 英文数字词，不引分词器 */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+")) {
            if (part.matches("[a-z0-9]{2,}")) {
                tokens.add(part);
            } else if (part.matches("[\\p{IsHan}]+")) {
                for (int i = 0; i + 1 < part.length(); i++) {
                    tokens.add(part.substring(i, i + 2));
                }
            }
        }
        tokens.removeAll(Set.of("什么", "怎么", "如何", "请问", "帮我", "一下", "可以"));
        return tokens;
    }

    public record RecallResult(
            List<McpToolRegistry.Entry> residents,
            List<McpToolRegistry.Entry> recalled,
            boolean recalledByRag) {}
}
```


### 3.3 `McpToolVectorStore` —— 内存向量索引

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * 工具向量索引：几十个工具，全放内存，检索时暴力算余弦
 * <p>
 * 不用 pgvector / Milvus：60 个工具 × 1024 维 ≈ 245KB，暴力点积微秒级，
 * 上向量库要额外运维、冷启动、索引同步，收益为零
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class McpToolVectorStore {

    private final McpToolVectorMapper mapper;
    private final EmbeddingService embeddingService;
    private final McpToolRegistry registry;

    /** toolId → 归一化后的向量 */
    private volatile Map<String, float[]> vectors;

    public Map<String, Double> topK(float[] queryVector, Set<String> candidates, int k) {
        Map<String, float[]> current = current();
        float[] normalizedQuery = normalize(queryVector);

        return current.entrySet().stream()
                .filter(e -> candidates.contains(e.getKey()))
                .map(e -> Map.entry(e.getKey(), (double) dot(normalizedQuery, e.getValue())))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 重建向量：只对 description_hash 变化的工具重新 embed
     * <p>
     * 挂到注册表刷新之后调用，而不是每次请求；正常情况下只有改描述时才触发
     */
    public void rebuildDirty() {
        Map<String, String> stored = mapper.selectAllHashes();   // toolId → hash
        List<McpToolDO> tools = mapper.selectAllEnabled();
        for (McpToolDO tool : tools) {
            String hash = sha256(tool.getDescription());
            if (hash.equals(stored.get(tool.getToolId()))) {
                continue;                    // 未变，跳过
            }
            float[] vector = normalize(toFloatArray(embeddingService.embed(tool.getDescription())));
            mapper.upsert(tool.getToolId(), embeddingModelId(), vector.length, toBytes(vector), hash);
            log.info("工具向量已重建, toolId={}", tool.getToolId());
        }
        vectors = null;                      // 下次访问重新加载
    }

    private Map<String, float[]> current() {
        Map<String, float[]> current = vectors;
        if (current == null) {
            synchronized (this) {
                current = vectors;
                if (current == null) {
                    current = mapper.selectAllVectors();
                    vectors = current;
                }
            }
        }
        return current;
    }

    private float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private double dot(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < len; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
```

### 3.4 `ToolSearchTool` —— 懒加载的 schema 加载器

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * 工具 schema 加载器：召回的工具只给名字，模型要用才取完整定义
 * <p>
 * 这是「懒加载」的入口。不注册这个工具的话，召回的工具名模型能看到但调不了
 */
@Slf4j
@RequiredArgsConstructor
public class ToolSearchTool implements AgentTool {

    public static final String TOOL_NAME = "tool_search";
    public static final String DISPLAY_NAME = "工具说明查询";

    private static final String DESCRIPTION = """
            取回工具的完整参数说明。以下工具已召回但参数结构未加载，调用前必须先取：
            
            %s
            
            查询方式：
            - select:工具名[,工具名...]  按名字精确取（推荐，可一次取多个）
            例：select:search_artist_events,current_date
            
            注意：一次查询把本次要用的工具都列上，分开查会多花往返时间。
            """;

    private final McpToolRegistry registry;
    private final List<String> pendingToolIds;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        String list = pendingToolIds.isEmpty()
                ? "（当前没有待加载的工具）"
                : pendingToolIds.stream()
                        .map(id -> "- " + id + "：" + shortDesc(id))
                        .collect(Collectors.joining("\n"));
        return DESCRIPTION.formatted(list);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "select:工具名[,工具名...] 精确取回参数结构")),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolExecutionTracker.track(param, () -> Mono.fromCallable(() -> {
            String callId = Optional.ofNullable(param.getToolUseBlock())
                    .map(ToolUseBlock::getId).orElse(null);
            String query = Optional.ofNullable(param.getInput())
                    .map(i -> i.get("query"))
                    .filter(String.class::isInstance).map(String.class::cast)
                    .map(String::strip).orElse("");

            if (StrUtil.isBlank(query)) {
                return error(callId, "请提供查询串，例如 select:search_artist_events");
            }

            List<McpToolRegistry.Entry> hits = resolve(query);
            if (hits.isEmpty()) {
                return error(callId, """
                        未找到匹配的工具。
                        本轮可加载：%s
                        请用 select:工具名 精确指定。""".formatted(String.join("、", pendingToolIds)));
            }

            StringBuilder sb = new StringBuilder("以下工具的参数结构已加载，现在可以直接调用：\n\n");
            for (McpToolRegistry.Entry hit : hits) {
                sb.append("### ").append(hit.toolId()).append('\n');
                sb.append(hit.description()).append('\n');
                sb.append("只读: ").append(hit.readOnly() ? "是" : "否").append('\n');
                sb.append("参数结构:\n").append(hit.schemaText()).append("\n\n");
            }
            return ToolResultBlock.builder()
                    .id(callId).name(TOOL_NAME)
                    .output(TextBlock.builder().text(sb.toString()).build())
                    .state(ToolResultState.SUCCESS).build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 只允许加载本轮召回的工具，避免模型凭空调用未授权工具 */
    private List<McpToolRegistry.Entry> resolve(String query) {
        if (!query.startsWith("select:")) {
            return List.of();
        }
        List<McpToolRegistry.Entry> hits = new ArrayList<>();
        Map<String, McpToolRegistry.Entry> all = registry.all();
        for (String raw : query.substring("select:".length()).split(",")) {
            String name = raw.trim();
            if (name.isEmpty() || !pendingToolIds.contains(name)) {
                continue;
            }
            McpToolRegistry.Entry entry = all.get(name);
            if (entry != null && !hits.contains(entry)) {
                hits.add(entry);
            }
        }
        return hits;
    }

    private String shortDesc(String toolId) {
        McpToolRegistry.Entry entry = registry.all().get(toolId);
        if (entry == null) {
            return "";
        }
        String desc = entry.description();
        return desc.length() <= 40 ? desc : desc.substring(0, 40) + "…";
    }

    private ToolResultBlock error(String callId, String text) {
        return ToolResultBlock.builder()
                .id(callId).name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(ToolResultState.ERROR).build();
    }
}
```

### 3.5 改造 `AgentToolCatalog`

```java
/**
 * 改造点：删除与意图树取交集，改为从注册表 + 召回结果构建
 */
public ResolvedCatalog resolve(Map<String, String> prompts, String question) {
    ToolRagService.RecallResult recall = toolRagService.recall(question);
    return new ResolvedCatalog(
            resolveKnowledgeToolDescription(prompts),
            resolveMemoryToolDescription(prompts),
            recall,
            skillRegistry.listEnabled());
}

public Toolkit buildToolkit(ResolvedCatalog catalog) {
    Toolkit toolkit = new Toolkit();
    toolkit.registerAgentTool(new KnowledgeSearchTool(
            catalog.knowledgeToolDescription, knowledgeSearchFacade));
    if (catalog.memoryToolDescription != null) {
        toolkit.registerAgentTool(new MemoryFlushTool(catalog.memoryToolDescription, memoryPipeline));
    }
    if (catalog.hasSkills) {
        toolkit.registerAgentTool(new SkillLoadTool(skillRegistry));
    }

    ToolRagConfig cfg = properties.getToolRag();

    // ① 常驻工具：直接全 schema 注册，不走懒加载
    catalog.recall().residents().forEach(entry ->
            toolkit.registerAgentTool(new McpToolProxy(toBinding(entry))));

    if (!cfg.isLazyLoad()) {
        // ② 懒加载关闭：召回的工具也直接注册
        catalog.recall().recalled().forEach(entry ->
                toolkit.registerAgentTool(new McpToolProxy(toBinding(entry))));
    } else {
        // ③ 懒加载开启：召回的工具只留名字，注册 tool_search 让模型自助加载
        List<String> pending = catalog.recall().recalled().stream()
                .map(McpToolRegistry.Entry::toolId).toList();
        if (!pending.isEmpty()) {
            toolkit.registerAgentTool(new ToolSearchTool(toolRegistry, pending));
        }
        // 懒加载模式下，实际调用通过 proxy 列表在 tool_search 后动态补注册
        // 见 3.6 的动态注册说明
    }
    return toolkit;
}
```

### 3.6 懒加载的动态注册问题（必须提前想清）

**这是本方案技术上最难的一处，也是很多人做到一半卡住的地方。**

`Toolkit` 是构建 `ReActAgent` 时传进去的，**一旦构建完成，工具列表就固定了**。模型调 `tool_search` 之后，我们没法在运行中往已有的 Toolkit 里加工具。

三条出路：

| 方案                         | 做法                                               | 代价                         |
| -------------------------- | ------------------------------------------------ | -------------------------- |
| **A. 两阶段重启**（不推荐）          | tool_search 后返回需要的信息，触发外部重新构建 Agent              | 一次对话要重建 Agent，状态难保         |
| **B. 全量注册 + 描述裁剪**（推荐）     | 召回的工具**都注册**进 Toolkit，但把非召回工具的 description 置空/缩短 | 简单，但只能省描述 token，省不了 schema |
| **C. tool_call 代理**（真正懒加载） | 只注册 `tool_search` + `tool_call`，所有调用走代理          | 失去 schema 强校验、确认卡要改、审计要改   |

**本方案推荐混合策略：**

```
常驻工具（2-3 个）  →  直接注册，全 schema，零开销
召回工具（3-5 个）  →  直接注册，全 schema（这是 Tool RAG 已省下的部分）
未召回工具          →  完全不注册（这是 Tool RAG 的收益）
```

**也就是说：只做 Tool RAG，不做真正的懒加载。**


理由很直接：

- Tool RAG 已经把 60 个工具缩到 8 个以内，**schema token 从 8k 降到 1k，省 87%**
- 真正的懒加载（方案 C）只能再省 1k 里的 700（留 2-3 个真用的），**边际收益小**
- 但方案 C 的成本很高（schema 校验、确认卡、审计三处改造）

**如果你仍要做方案 C**，`ToolSearchTool` + `ToolCallTool` 的完整实现见 `自研ToolSearch实现方案.md`，本文不重复。

**因此本方案的配置默认值**：

```yaml
agent:
  mcp:
    tool-rag:
      enabled: true
      lazy-load: false      # 默认关：只做召回，不做代理式懒加载
```

---

## 4. 需要同步改造的地方

### 4.1 `AgentSkillAdminServiceImpl`（必改，否则技能链路断）

```java
// 改造前
for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) { ... }

// 改造后
for (McpToolRegistry.Entry entry : toolRegistry.all().values()) {
    options.add(AgentSkillToolOptionVO.builder()
            .toolId(entry.toolId())
            .name(entry.displayName())
            .description(entry.description())
            .referencedBySkillName(ownerBySkill.get(entry.toolId()))
            .build());
}
```

`validateToolIds()` 同理，`available` 从 `toolRegistry.all().keySet()` 取。

### 4.2 `DefaultIntentClassifier`（清理死条件）

```java
// 删除 OrchestrationMode.WORKFLOW 分支
List<IntentNode> candidates = data.leafNodes.stream()
        .filter(IntentNode::isKB)      // 只保留知识库节点
        .toList();
```

同时删除 `orchestrationProperties` 字段（若不再使用）。

### 4.3 `IntentNodeRegistry`（收敛接口）

```java
public interface IntentNodeRegistry {
    IntentNode getNodeById(String id);

    // 删除：listMcpToolNodes()
    // 工具注册职责已移交 McpToolRegistry
}
```

连带删除：

- `DefaultIntentClassifier.listMcpToolNodes()`（第 113-120 行）
- `IntentNode.isMCP()` 保留（前端/迁移脚本可能还用），但不再有生产代码调用

### 4.4 `IntentTreeServiceImpl`（不再写 mcpToolId）

```java
// 第 152 行：kind=2 已废弃，mcpToolId 恒为 null
.mcpToolId(null)
.requireConfirm(0)
```

或更彻底：**拒绝创建 kind=2 节点**，返回明确错误引导用户去 MCP 工具管理页。

```java
if (Objects.equals(kind, IntentKind.MCP.getCode())) {
    throw new ClientException("MCP 工具已不在意图树管理，请到「MCP 工具」页面维护");
}
```

### 4.5 前端

| 文件                                           | 改动                                                                                     |
| -------------------------------------------- | -------------------------------------------------------------------------------------- |
| `pages/admin/intent-tree/IntentEditPage.tsx` | 删除 `kind=2` 选项（第 51 行）、删除 `mcpToolId` / `requireConfirm` 表单段（511-527 行）、删校验（286-287 行） |
| `pages/admin/intent-tree/IntentListPage.tsx` | 删除 kind=2 筛选项（第 61 行）                                                                  |
| **新增** `pages/admin/mcp-tools/`              | 工具管理页：列表、编辑描述、常驻开关、启停、**重建向量**按钮、召回测试框                                                 |

**召回测试框**这个功能很实用：输入一句话，显示召回了哪些工具及分数，用来调 `recall-min-score` 和描述。

### 4.6 初始化器

`resources/initializer/starline-events/intents/` 下**删除所有 kind=2 的 properties 文件**，改为 `mcp-tools.properties`：

```properties
tool.refs=search_artist_events,current_date,youcom_search

tool.search_artist_events.display-name=演出查询
tool.search_artist_events.description=查询艺人近期演出安排...
tool.search_artist_events.resident=true
tool.search_artist_events.require-confirm=false

tool.current_date.display-name=当前日期
tool.current_date.description=查询当前日期与星期...
tool.current_date.resident=true

tool.youcom_search.display-name=联网检索
tool.youcom_search.description=通用联网搜索...
tool.youcom_search.resident=false
```

---

## 5. 关键设计决策记录

### 5.1 向量只做召回，不做决策

**必须**。反例：

> 用户说「帮我盯着开票」
>
> - `search_artist_events` 描述里全是「开票」→ 向量相似度最高
> - `create_reminder` 描述里可能一个「开票」都没有 → 相似度低
>
> **但用户要的是后者。**

正确做法：召回同时包含这两个（topK=5 足够），**由模型判断**该调哪个。

这也是为什么 `recall-top-k` 不能设成 1 或 2 —— 至少 5，留出模型判断的空间。

### 5.2 关键词权重高于向量

`keywordScores` 里工具名命中 +3、描述命中 +1。

因为工具名是规范化的「动词_对象」结构（`create_reminder`、`search_artist_events`），**命中即是强信号**；而描述容易泛泛匹配（每个工具描述里都有「查询」「信息」这类词）。

### 5.3 召回为空时退化为全量

```java
if (recalled.isEmpty()) {
    log.warn("工具召回为空，本轮退化为全量注册");
    return new RecallResult(residents, all, false);
}
```

**宁可多花 token，也不能让模型无工具可用。** 召回失败是工程问题，不该让用户承受。

同理，向量召回异常时 `catch` 住降级为纯关键词，不阻断主链路。

### 5.4 常驻工具为什么必须有

召回要一次 embed + 打分，约 50-200ms。而「查演出」「查日期」这类问题占了大多数，每次都召回是浪费。

配 2-3 个常驻后：

- 高频问题：零召回开销，直接命中
- 低频问题：召回成本摊薄

**这正好对应 Claude Code 的「保留 3-5 个工具不延迟」建议。**

---

## 6. 实施阶段

| 阶段    | 内容                                                             | 出口条件                                     |
| ----- | -------------------------------------------------------------- | ---------------------------------------- |
| **1** | 建 `t_mcp_tool` + `t_mcp_tool_vector`，迁移现有 kind=2 节点            | 迁移脚本可重复执行；`t_mcp_tool` 行数 = 原 kind=2 节点数 |
| **2** | `McpToolRegistry` + 改造 `AgentToolCatalog` 读注册表（**先不做召回，等价全量**） | 模型能正常看到并调用全部工具，行为与改造前一致                  |
| **3** | `McpToolVectorStore` + `rebuildDirty()`，管理页加「重建向量」             | 向量写入成功；日志显示只重建变化的工具                      |
| **4** | `ToolRagService` 召回（先只做关键词，不做向量）                               | 召回日志合理；高频问题召回到正确工具                       |
| **5** | 开启向量召回（`recall-enabled: true`）                                 | 召回质量对比关键词有提升                             |
| **6** | 改 `AgentSkillAdminServiceImpl` 指向注册表                           | 技能管理页工具下拉正常，技能绑定生效                       |
| **7** | 清理死代码：`listMcpToolNodes`、WORKFLOW 分支、前端 kind=2                 | `grep -r listMcpToolNodes` 只剩测试          |
| **8** | 前端 MCP 工具管理页 + 召回测试                                            | 能改描述、改常驻、看召回分数                           |

**阶段 2 是关键分界点**：完成后行为与改造前等价，此时可以停下来观察，确认没退化再继续。

---

## 7. 验收清单

### 7.1 功能等价（阶段 2 后必须全过）

- [ ] 问「薛之谦最近有什么演出」→ 正常调 `search_artist_events`
- [ ] 问「今天几号」→ 正常调 `current_date`
- [ ] 写工具（如后续的 `create_reminder`）仍弹确认卡
- [ ] 技能解锁工具的链路正常（`load_skill` 后工具可用）
- [ ] `/agent/v1/meta` 的 `mcpToolCount` 与注册表启用数一致

### 7.2 召回质量（阶段 5 后）

- [ ] 问「薛之谦最近有什么演出」→ 召回含 `search_artist_events`
- [ ] 问「帮我盯着开票」→ **同时召回** `search_artist_events` 和 `create_reminder`（不是只召回第一个）
- [ ] 问「查一下场馆座位」→ 召回 `venue_info`（若存在）
- [ ] 问完全不相关的问题（如「你好」）→ 只注册常驻工具，不召回无关工具
- [ ] 召回为空时日志出现「退化为全量注册」，且模型仍能工作

### 7.3 意图树解耦

- [ ] `t_intent_node` 里 kind=2 全部 `deleted=1`
- [ ] 意图识别日志只显示 KB 节点打分，无 MCP 节点
- [ ] 新增 MCP 工具**不需要**清 Redis（这是本次改造的核心收益）
- [ ] 前端意图编辑页无 kind=2 选项

### 7.4 清理彻底性

```bash
# 应只剩测试文件
grep -rn "listMcpToolNodes" --include=*.java .

# 应为 0
grep -rn "OrchestrationMode.WORKFLOW" --include=*.java rag/src/main/

# 前端应为 0
grep -rn "kind === 2\|value: 2.*MCP" frontend/src/pages/admin/intent-tree/
```

---

## 8. 风险清单

| # | 风险                      | 触发条件                                  | 缓解                                               |
| - | ----------------------- | ------------------------------------- | ------------------------------------------------ |
| 1 | **技能链路断掉**              | 只改工具注册不改 `AgentSkillAdminServiceImpl` | 阶段 6 必须做；阶段 2 后先测技能                              |
| 2 | **召回漏掉关键工具**            | `recall-top-k` 太小或描述写得太差              | topK ≥ 5；召回为空退化为全量；管理页有召回测试                      |
| 3 | **向量重建漏触发**             | 改了描述但 `description_hash` 没更新          | 写入时统一在 service 层算 hash，不依赖调用方                    |
| 4 | **换 embedding 模型后向量全废** | 换模型但没重建                               | `t_mcp_tool_vector.model_id` 与配置不一致时启动告警并自动重建    |
| 5 | **召回延迟拖慢首字**            | 每次请求都 embed                           | 常驻工具 + 向量缓存；实测若超 200ms 则关 `vector-enabled` 只留关键词 |
| 6 | **懒加载做到一半卡住**           | 试图在运行时往 Toolkit 加工具                   | 本文 3.6 已说明：默认 `lazy-load: false`，只做召回            |
| 7 | **意图树历史节点误删**           | 迁移脚本直接物理删                             | 用 `deleted=1` 软删，保留回滚能力                          |

---

## 9. 一句话总结

> **这次改造的核心不是 "Tool RAG" 或 "懒加载"，而是「把工具授权从意图树里拿出来」。**
>
> Tool RAG 是附带的收益——因为工具独立成表后，才有地方挂向量、挂常驻标记、挂召回阈值。挂在意图树上做这些事，语义上就说不通。
>
> **真正的收益排序**：
>
> 1. 意图树回归本职，新增工具不用清 Redis、不用插节点（**工程收益最大**）
> 2. 召回让上下文与工具总数解耦（**规模收益**）
> 3. 懒加载（**边际收益最小，不建议先做**）

---

## 附：工作量估算

| 部分                                        | 预估      |
| ----------------------------------------- | ------- |
| 表结构 + 迁移脚本                                | 0.5 天   |
| `McpToolRegistry` + `AgentToolCatalog` 改造 | 1 天     |
| `McpToolVectorStore` + 向量重建               | 1 天     |
| `ToolRagService` 召回                       | 1 天     |
| `AgentSkillAdminServiceImpl` 改造           | 0.5 天   |
| 前端 MCP 工具管理页 + 召回测试                       | 1.5 天   |
| 死代码清理 + 验收                                | 0.5 天   |
| **合计**                                    | **6 天** |

**如果只做阶段 1-2 + 7（去掉召回和向量）**：约 2 天，就能拿到「意图树解耦」的全部工程收益。**建议先做这个，观察一周，再决定要不要上召回。**
