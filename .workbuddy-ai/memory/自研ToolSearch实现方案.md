# 自研 ToolSearch 实现方案（CallTool 代理模式）

> 目标：在 starlineAgent 里实现类似 Claude Code 的「工具按需加载」，让工具数量增长时上下文不膨胀。
>
> 核查日期：2026-10-25。基于 `AgentToolCatalog` / `McpToolProxy` / `AgentMcpClients` / `KnowledgeSearchTool` 实地核实。

---

## 0. 先说清一件事：你走不了 Claude 的原生方案

Claude 的 ToolSearch 依赖一个 **API 层面的特性 `defer_loading`**：

```json
{
  "name": "search_artist_events",
  "input_schema": { ... },
  "defer_loading": true      // ← 只有 API 支持才生效
}
```

它的作用是：**API 服务端把该工具的参数 schema 从上下文里摘掉，只保留名字**，模型调 ToolSearch 后由 API 自动展开 `tool_reference`。

**你的模型是 `deepseek-flash`（OpenAI 兼容端点），没有这个字段。** 传了也会被忽略，全部 schema 照样进上下文。

所以只有两条路：

| 路线 | 做法 | 代价 |
|---|---|---|
| **A. 换模型** | 用原生支持 `defer_loading` 的 Claude 模型 | 换供应商、成本上升、中文能力需重测 |
| **B. 代理模式**（本文方案） | 自己实现 `tool_search` + `tool_call` 两个元工具 | **失去 API 层 schema 强校验** |

下面走 B。

---

## 1. 代理模式的核心：只注册 2 个元工具

```
Toolkit 里只有：
  ├── search_knowledge        （原有，不变）
  ├── memory_flush            （原有，不变）
  ├── tool_search   ← 新增：检索工具定义
  └── tool_call     ← 新增：代理调用任意 MCP 工具
```

**所有 MCP 工具都不直接注册**，而是藏在 `tool_call` 后面。

```
模型 → tool_search("演出 开票")
     ← 命中 search_artist_events 的完整 schema（文本形式）

模型 → tool_call({tool_name: "search_artist_events",
                  arguments: {artist: "薛之谦", freshness: "month"}})
     ← 真实工具的执行结果
```

**这就是 Claude Code 在没有 `defer_loading` 时的降级方案**（其源码里对应的就是 `CallTool` 代理）。

---

## 2. 关键代码

### 2.1 `McpToolRegistry` —— 工具索引

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * MCP 工具索引，供 tool_search 检索
 * <p>
 * 只存元信息（name / description / schema 文本），不持有连接；
 * 连接仍由 AgentMcpClients 管，调用时才现取
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class McpToolRegistry {

    private final AgentMcpClients mcpClients;
    private final IntentNodeRegistry intentNodeRegistry;

    /** toolId → 可检索的索引项，启动后懒加载一次 */
    private volatile Map<String, Indexed> index;

    public record Indexed(
            String toolId,
            String displayName,
            String description,
            String schemaText,      // 完整 JSON Schema 的可读形式，返回给模型用
            boolean readOnly,
            boolean needsConfirm) {}

    /**
     * 只索引「意图树授权的工具」，与现有 resolveMcpTools 口径一致
     * <p>
     * 索引在首次访问时构建；意图树变更后由 AgentToolCatalog 的 fingerprint 变化间接触发重建
     */
    public Map<String, Indexed> all() {
        Map<String, Indexed> current = index;
        if (current == null) {
            synchronized (this) {
                current = index;
                if (current == null) {
                    current = build();
                    index = current;
                }
            }
        }
        return current;
    }

    private Map<String, Indexed> build() {
        Map<String, Indexed> result = new LinkedHashMap<>();
        for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) {
            String toolId = node.getMcpToolId() == null ? null : node.getMcpToolId().trim();
            if (StrUtil.isBlank(toolId) || AgentToolCatalog.RESERVED_TOOL_NAMES.contains(toolId)) {
                continue;
            }
            AgentMcpClients.RemoteTool remote = mcpClients.get(toolId);
            if (remote == null) {
                continue;
            }
            Tool definition = remote.definition();
            boolean readOnly = definition.annotations() != null
                    && Boolean.TRUE.equals(definition.annotations().readOnlyHint());
            String description = StrUtil.isNotBlank(node.getDescription())
                    ? node.getDescription()
                    : StrUtil.emptyIfNull(definition.description());
            result.put(toolId, new Indexed(
                    toolId,
                    StrUtil.isNotBlank(node.getName()) ? node.getName() : toolId,
                    description,
                    renderSchema(definition.inputSchema()),
                    readOnly,
                    node.isRequireConfirm() || !readOnly));
        }
        log.info("MCP 工具索引构建完成, size={}", result.size());
        return Map.copyOf(result);
    }

    /** 意图树变更后清索引；由 AgentToolCatalog 在 fingerprint 变化时调用 */
    public void invalidate() {
        index = null;
    }

    /** JSON Schema → 模型能直接读的文本，与 Claude Code 的 <functions> 编码思路一致 */
    private String renderSchema(JsonSchema schema) {
        if (schema == null) {
            return "{}";
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

### 2.2 `ToolSearchTool` —— 检索工具

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * 工具检索：让模型按需取回工具定义
 * <p>
 * 检索策略（对齐 Claude 内置变体）：精确选择 + BM25 关键词，不做向量检索——
 * 工具描述本来就是人写的结构化文本，关键词匹配可解释、零依赖、无额外延迟
 */
@Slf4j
@RequiredArgsConstructor
public class ToolSearchTool implements AgentTool {

    public static final String TOOL_NAME = "tool_search";
    public static final String DISPLAY_NAME = "工具检索";

    private static final String QUERY_PARAM = "query";
    private static final int MAX_RESULTS = 10;

    private static final String DESCRIPTION = """
            检索可用的工具定义。可用工具的参数结构默认未加载，调用前需要先用本工具取回。
            
            三种查询形式：
            1. 精确选取：select:工具名[,工具名...]  例：select:search_artist_events,current_date
            2. 关键词检索：自然语言或关键词  例：演出 开票
            3. 强制包含：词前加 +  例：+slack send  （slack 必须出现在工具名里）
            
            重要：一次查询尽量包含本次任务需要的全部工具（逗号分隔），
            不要一个工具查一次——每次查询都是一个完整往返。
            """;

    private final McpToolRegistry registry;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        QUERY_PARAM, Map.of(
                                "type", "string",
                                "description", "查询串。select:名字列表 精确选取；否则按关键词检索")),
                "required", List.of(QUERY_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolExecutionTracker.track(param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String callId = Optional.ofNullable(param.getToolUseBlock())
                .map(ToolUseBlock::getId).orElse(null);
        String query = Optional.ofNullable(param.getInput())
                .map(input -> input.get(QUERY_PARAM))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .orElse("");

        if (StrUtil.isBlank(query)) {
            return result(callId, "请提供查询串。精确选取用 select:工具名，例如 select:search_artist_events", true);
        }

        Map<String, McpToolRegistry.Indexed> all = registry.all();
        List<McpToolRegistry.Indexed> hits = query.startsWith("select:")
                ? selectExact(all, query.substring("select:".length()))
                : keywordSearch(all, query);

        if (hits.isEmpty()) {
            return result(callId, """
                    未找到匹配的工具。
                    可用工具：%s
                    可改用 select:工具名 精确选取。""".formatted(
                    all.keySet().stream().sorted().collect(Collectors.joining("、"))), true);
        }
        return result(callId, render(hits), false);
    }

    /** select:toolA,toolB —— 名字精确匹配，大小写不敏感 */
    private List<McpToolRegistry.Indexed> selectExact(Map<String, McpToolRegistry.Indexed> all, String names) {
        List<McpToolRegistry.Indexed> hits = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            McpToolRegistry.Indexed found = all.get(name);
            if (found == null) {
                // 容错：忽略大小写再试一次
                found = all.values().stream()
                        .filter(i -> i.toolId().equalsIgnoreCase(name))
                        .findFirst().orElse(null);
            }
            if (found != null && !hits.contains(found)) {
                hits.add(found);
            }
        }
        return hits;
    }

    /**
     * 关键词检索：+ 前缀表示必含，其余按命中次数排序
     * <p>
     * 检索范围对齐 Claude：工具名 + 描述，不含参数（参数在返回结果里给全）
     */
    private List<McpToolRegistry.Indexed> keywordSearch(Map<String, McpToolRegistry.Indexed> all, String query) {
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.startsWith("+") && token.length() > 1) {
                required.add(token.substring(1));
            } else {
                optional.add(token);
            }
        }
        record Scored(McpToolRegistry.Indexed item, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (McpToolRegistry.Indexed item : all.values()) {
            String nameText = (item.toolId() + " " + item.displayName()).toLowerCase(Locale.ROOT);
            String bodyText = (nameText + " " + item.description()).toLowerCase(Locale.ROOT);
            // 必含词必须出现在工具名里，否则淘汰
            if (!required.stream().allMatch(nameText::contains)) {
                continue;
            }
            int score = 0;
            for (String word : optional) {
                if (nameText.contains(word)) {
                    score += 3;          // 名字命中权重更高
                } else if (bodyText.contains(word)) {
                    score += 1;
                }
            }
            if (score > 0 || required.size() > 0) {
                scored.add(new Scored(item, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(s -> s.item().toolId()))
                .limit(MAX_RESULTS)
                .map(Scored::item)
                .toList();
    }

    /** 与提示词顶部工具定义同构的文本编码，模型读到的格式一致 */
    private String render(List<McpToolRegistry.Indexed> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 个工具，参数结构如下：\n\n");
        for (McpToolRegistry.Indexed item : hits) {
            sb.append("### ").append(item.toolId());
            if (!item.toolId().equals(item.displayName())) {
                sb.append("（").append(item.displayName()).append("）");
            }
            sb.append('\n');
            sb.append(item.description()).append('\n');
            sb.append("只读: ").append(item.readOnly() ? "是" : "否");
            if (item.needsConfirm()) {
                sb.append("（调用前需用户确认）");
            }
            sb.append('\n');
            sb.append("参数结构:\n").append(item.schemaText()).append("\n\n");
        }
        sb.append("调用方式：tool_call({tool_name: \"工具名\", arguments: {参数}})。")
          .append("一次可调一个工具；多个工具请分多次调用。");
        return sb.toString().trim();
    }

    private ToolResultBlock result(String callId, String text, boolean error) {
        return ToolResultBlock.builder()
                .id(callId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(error ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
```

### 2.3 `ToolCallTool` —— 代理调用（含确认闸门）

```java
package com.nageoffer.ai.starlineagent.agent.tool;

/**
 * 工具代理调用
 * <p>
 * 所有 MCP 工具都从这里走。继承 McpTool 复用 AgentScope 的连接与结果转换，
 * 但把「调哪个工具」从方法名变成了参数——这是代理模式与直接注册的唯一区别
 */
@Slf4j
public class ToolCallTool extends McpTool {

    public static final String TOOL_NAME = "tool_call";
    public static final String DISPLAY_NAME = "工具调用";

    private static final String ARG_TOOL_NAME = "tool_name";
    private static final String ARG_ARGUMENTS = "arguments";

    private static final String DESCRIPTION = """
            调用一个工具。工具的参数结构需要先用 tool_search 取回。
            
            参数：
            - tool_name：工具名，必须来自 tool_search 的返回结果
            - arguments：该工具的参数对象，结构见 tool_search 返回的「参数结构」
            
            注意：
            1. 必须先 tool_search 再 tool_call，否则不知道参数结构
            2. 一次只调一个工具，不要在一次 arguments 里塞多个工具的意图
            3. 标了「调用前需用户确认」的工具，调用会被挂起等待用户批准
            """;

    private final McpToolRegistry registry;
    private final AgentMcpClients mcpClients;
    private final Map<String, Object> parameters;

    public ToolCallTool(McpToolRegistry registry, AgentMcpClients mcpClients) {
        // 父类需要 name/description/schema/client；client 传 null，实际调用在 callAsync 里现取
        super(TOOL_NAME, DESCRIPTION, buildSchema(), null, null, null, null, false);
        this.registry = registry;
        this.mcpClients = mcpClients;
        this.parameters = buildSchema();
    }

    private static Map<String, Object> buildSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        ARG_TOOL_NAME, Map.of(
                                "type", "string",
                                "description", "工具名，必须来自 tool_search 的返回结果"),
                        ARG_ARGUMENTS, Map.of(
                                "type", "object",
                                "description", "该工具的参数对象，结构见 tool_search 返回的「参数结构」")),
                "required", List.of(ARG_TOOL_NAME, ARG_ARGUMENTS),
                "additionalProperties", false);
    }

    /**
     * 关键：确认闸门从「工具级」下沉到「参数级」
     * <p>
     * 直接注册时，每个 McpToolProxy 自己知道自己要不要确认；
     * 代理模式下所有调用都叫 tool_call，只能从 toolInput 里读目标工具名再判断。
     * 好在 checkPermissions 的方法签名带着 toolInput，这条路是通的
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
                                                     PermissionContextState context) {
        String target = toolInput == null ? null : String.valueOf(toolInput.get(ARG_TOOL_NAME));
        if (StrUtil.isBlank(target) || "null".equals(target)) {
            return Mono.just(PermissionDecision.allow("未指定目标工具，放行到 callAsync 里报错"));
        }
        McpToolRegistry.Indexed item = registry.all().get(target);
        if (item == null) {
            return Mono.just(PermissionDecision.allow("未知工具，放行到 callAsync 里报错"));
        }
        return Mono.just(item.needsConfirm()
                ? PermissionDecision.ask("工具 " + item.displayName() + " 执行前需要你确认")
                : PermissionDecision.allow("该工具无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolExecutionTracker.track(param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String callId = Optional.ofNullable(param.getToolUseBlock())
                .map(ToolUseBlock::getId).orElse(null);
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();

        String target = input.get(ARG_TOOL_NAME) instanceof String s ? s.strip() : null;
        if (StrUtil.isBlank(target)) {
            return error(callId, "缺少参数 tool_name。请先 tool_search 取回工具定义");
        }

        McpToolRegistry.Indexed indexed = registry.all().get(target);
        if (indexed == null) {
            // 不在授权清单里：可能是模型自己编的工具名，也可能是意图树没配
            log.warn("tool_call 收到未授权工具名, toolName={}", target);
            return error(callId, "工具「" + target + "」不在可用列表中。请先 tool_search 确认可用工具");
        }

        AgentMcpClients.RemoteTool remote = mcpClients.get(target);
        if (remote == null) {
            log.warn("工具在索引里但连接已断, toolName={}", target);
            return error(callId, "工具「" + indexed.displayName() + "」当前不可用，请稍后重试");
        }

        Object rawArgs = input.get(ARG_ARGUMENTS);
        if (!(rawArgs instanceof Map<?, ?> argMap)) {
            return error(callId, "参数 arguments 必须是对象。请参考 tool_search 返回的参数结构");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) argMap;

        // 定位：这里失去了 API 层的 schema 校验，自己补一层最小校验
        String validation = validateAgainstSchema(args, indexed);
        if (validation != null) {
            return error(callId, validation);
        }

        try {
            Map<String, Object> meta = Optional.ofNullable(param.getRuntimeContext())
                    .map(ctx -> ctx.get(McpMeta.class))
                    .map(McpMeta::entries)
                    .orElseGet(Map::of);
            CallToolResult result = remote.client()
                    .callTool(target, args, meta)
                    .block(Duration.ofSeconds(60));
            if (result == null) {
                return error(callId, "工具「" + indexed.displayName() + "」没有返回结果，请稍后重试");
            }
            ToolResultBlock converted = McpContentConverter.convertCallToolResult(result);
            // 结果块的 name 用真实工具名，审计与前端才能看清调了什么
            return converted.withName(target);
        } catch (Exception e) {
            if (TaskCancellation.isCancellation(e)) {
                return interrupted(callId);
            }
            // 异常细节只进日志，与 McpToolResults.failure 的口径一致
            log.error("代理工具调用异常, toolName={}", target, e);
            return error(callId, "工具「" + indexed.displayName() + "」调用失败，请稍后重试");
        }
    }

    /**
     * 最小参数校验：只查必填和类型，不做完整 JSON Schema 校验
     * <p>
     * 代理模式最大的代价就是丢了这层校验，补一个够用的版本：
     * 宁可在 tool_call 里拒绝并给出明确提示，也不要让错误参数进到真实工具再报错
     */
    private String validateAgainstSchema(Map<String, Object> args, McpToolRegistry.Indexed indexed) {
        JsonSchema schema = mcpClients.get(indexed.toolId()).definition().inputSchema();
        if (schema == null || schema.required() == null) {
            return null;
        }
        List<String> missing = schema.required().stream()
                .filter(field -> !args.containsKey(field) || args.get(field) == null)
                .toList();
        if (!missing.isEmpty()) {
            return "缺少必填参数：" + String.join("、", missing)
                    + "。参数结构：\n" + indexed.schemaText();
        }
        // additionalProperties=false 的严格模式：拒绝未知字段，防止模型幻觉参数
        if (Boolean.FALSE.equals(schema.additionalProperties()) && schema.properties() != null) {
            List<String> unknown = args.keySet().stream()
                    .filter(key -> !schema.properties().containsKey(key))
                    .toList();
            if (!unknown.isEmpty()) {
                return "存在未知参数：" + String.join("、", unknown)
                        + "。可用参数：" + String.join("、", schema.properties().keySet());
            }
        }
        return null;
    }

    private ToolResultBlock error(String callId, String text) {
        return ToolResultBlock.builder()
                .id(callId).name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(ToolResultState.ERROR).build();
    }

    private ToolResultBlock interrupted(String callId) {
        return ToolResultBlock.builder()
                .id(callId).name(TOOL_NAME)
                .output(TextBlock.builder().text("用户已停止，本次工具调用未完成").build())
                .state(ToolResultState.INTERRUPTED).build();
    }
}
```

### 2.4 接入 `AgentToolCatalog`

```java
/** 新增配置开关，默认关 */
private final AgentMcpProperties properties;

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

    if (properties.isToolSearchEnabled()) {
        // 代理模式：只挂两个元工具，MCP 工具都不直接注册
        toolkit.registerAgentTool(new ToolSearchTool(toolRegistry));
        toolkit.registerAgentTool(new ToolCallTool(toolRegistry, mcpClients));
    } else {
        // 现状：直接注册（工具少时更优）
        catalog.bindings.forEach(binding -> toolkit.registerAgentTool(new McpToolProxy(binding)));
    }
    return toolkit;
}
```

配置：

```yaml
agent:
  mcp:
    tool-search-enabled: false      # 工具 >10 个再打开
```

**注意：`ResolvedCatalog.fingerprint()` 也要带上这个开关**，否则改了配置不会重建 Agent。加一个字段即可。

---

## 3. 必须先想清楚的四个代价

### ① 失去 API 层 schema 强校验

直接注册时，API 拿到完整 schema，会**自动校验并修正**模型生成的参数——类型错、缺必填、幻觉字段，都在 API 层被拦。

代理模式下 `arguments` 是个通用对象，**API 什么都不知道**。只能靠自己写 `validateAgainstSchema`。

我上面的实现补了「必填 + 未知字段」两项，但**类型校验、enum 校验、嵌套对象校验都没做**。要补全得引一个 JSON Schema 校验库（如 `networknt/json-schema-validator`）。

### ② 确认卡的 UX 会退化

直接注册时，确认卡渲染的是「`search_artist_events` 的 `artist`、`city` 参数」。

代理模式下，确认卡渲染的是「`tool_call` 的 `tool_name`、`arguments`」——**`arguments` 是一个嵌套 JSON，没法按字段展开**。

要修就得改前端 `AgentConfirmSettlement` 的渲染逻辑：识别 `tool_name` 参数，回查目标工具的 `fieldLabels` 再渲染。**这是一处额外工作量。**

### ③ 审计节点会失真

`t_agent_run_node.node_name` 记的是 `MCP 工具名`。代理模式下会记成 `tool_call`，**所有工具调用在审计里都是同一个名字**。

bad case 规则 A2（工具失败）、A5（耗时）会失去区分度。

修法：在 `ToolResultBlock.withName(target)` 的同时（我代码里已做），还要在 `AgentToolExecutionFacts` 里额外记一个 `proxiedToolName`。这块要动审计链路。

### ④ 多一次往返

```
直接注册：模型 → 调工具 → 结果
代理模式：模型 → tool_search → 模型 → tool_call → 结果
```

**每轮多一次完整 LLM 往返**（几百毫秒到 2 秒）。工具用得少时，这次往返的开销可能超过省下的 token 成本。

这就是 Claude Code 要搞 `tst-auto` 自适应的原因。

---

## 4. 我的建议：现在别做，但可以留个开关

### 为什么现在别做

| 项 | 你的情况 |
|---|---|
| 工具数 | 4 个 |
| schema 总 token | 约 1-2k（占上下文 0.2%） |
| 代理模式省下的 | **约 1k token** |
| 代理模式付出的 | 一次额外往返 + schema 校验手写 + 确认卡要改 + 审计要改 |

**省 1k token，换四个改造点和一次往返延迟——不划算。**

### 什么信号出现才做

任一条满足：

1. **MCP 工具超过 10 个**
2. **工具 schema 总计超过 10k tokens**（用 `tiktoken` 量一下）
3. **发现模型选错工具的比例明显上升**（查 `t_agent_run_node` 里工具选择的分布）
4. **要接入第三方 MCP server**（工具数量不受你控制）

### 如果现在就想要

做成**默认关闭的实验特性**：

```yaml
agent:
  mcp:
    tool-search-enabled: false
```

代码完整实现但不启用，等规模到了打开开关即可。这样：
- 不承担当前运行时的代价
- 未来不用临时开发
- 可以先写测试验证两条路径行为一致

---

## 5. 分阶段落地（若决定做）

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **1** | `McpToolRegistry` + `ToolSearchTool`，只做检索不接管调用 | 单测：`select:` 精确选取、关键词检索、必含词过滤都正确 |
| **2** | `ToolCallTool` + `checkPermissions` 参数级判定 | 写工具走确认卡，只读工具直接过 |
| **3** | `validateAgainstSchema` 补必填与未知字段校验 | 缺参数时返回可读提示而不是真实工具的 500 |
| **4** | 改造确认卡前端渲染（识别 `tool_name` 回查 fieldLabels） | 确认卡上能看到真实参数的中文字段名 |
| **5** | 审计记录 `proxiedToolName` | `t_agent_run_node` 能区分实际调用的是哪个工具 |
| **6** | A/B 对比：同一数据集跑直接注册 vs 代理模式 | 断言通过率不下降、token 明显下降、延迟可接受 |

**第 4、5 阶段是纯成本，没做好就不该上线**——否则用户体验（确认卡看不懂）和可观测性（审计分不清）都会退化。

---

## 6. 一个更轻的替代：只做「描述瘦身」

如果目标只是省 token，有个改动量小得多的做法：

**把长 description 压成一句话，详细说明移到 `server instructions` 或知识库。**

看你的 `search_artist_events` description——大约 400 字，约 600 tokens。而它的核心工作内容，模型其实只需要知道：

```
查询艺人近期演出安排，返回结构化场次（含来源可信度与时效标记）。
不适用：艺人生气、出道时间、代表作等个人资料。
```

**约 50 tokens。省下 550。**

详细的「sourceType 含义」「isStale 怎么用」「UNVERIFIED 不能作提醒依据」这些——**改成 system prompt 里的通用规则**（一份，所有工具共享），而不是每轮跟着工具定义重复传。

**这才是当前性价比最高的优化：省 550 tokens，改动是纯文本，零代码风险。**

---

## 7. 结论

> **技术上完全可行**，代码量约 500 行（`McpToolRegistry` + `ToolSearchTool` + `ToolCallTool` + 接入改造）。
>
> **但当前不值得做**：省 1k token，代价是一次额外往返 + 四处改造。
>
> **触发条件是工具数到 10 个以上。** 你现在 4 个，而且还在删工具。
>
> **真正该先做的两件事**：
> 1. 把 `search_artist_events` 那 400 字 description 压到 50 字，详细规则移到 system prompt（纯文本改动，立刻省 550 tokens）
> 2. 把 starline 的意图节点建起来（现在一个都没有，模型看不到任何工具）
