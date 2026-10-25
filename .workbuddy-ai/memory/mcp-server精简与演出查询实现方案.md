# mcp-server 精简改造 + 演出查询工具实现

> 目标：把 mcp-server 从「24 个示例工具」精简为「3 个通用工具 + 1 个演出查询工具」，并实现 `search_artist_events` 支撑「薛之谦最近有什么演出」这类问题。
>
> 核查日期：2026-10-25。所有文件路径、行号、依赖关系均经源码实地核实。**未修改任何项目文件。**

---

## 0. 先说影响面：这不是只改 mcp-server

删掉 bit/enterprise 工具，**mcp-server 模块之外还有一批东西依赖它们**。如果不一起处理，会留下「意图树配了工具但服务端没有」和「回归套件跑不起来」两类残留。

### 0.1 依赖矩阵（已核实）

| 依赖方 | 依赖内容 | 删工具后的后果 |
|---|---|---|
| `resources/initializer/bit-selection/intents/` | 约 20 个节点指向 `query_order`/`search_product`/`create_order` 等 | 变成 `unavailableToolIds`，每次启动打 warn，不阻塞 |
| `resources/initializer/enterprise-knowledge-base/intents/` | 约 10 个节点指向 `leave_query`/`meeting_room_query`/`asset_query` 等 | 同上 |
| `resources/initializer/bit-selection/biz-data/*.sql` | 灌 `starlineagent_bit` 库的 7 个 SQL | 无害，但库和脚本变成死资产 |
| `resources/regression/agent-memory-evaluation/datasets/bit-selection.json` | 4 个 case 全用比特严选知识库 | **评测跑不了**（要查的知识库和工具都没了） |
| `resources/regression/agent-compaction/` | 断言 `query_order` 返回订单内容 | **回归失败** |
| `resources/regression/agent-concurrency/` | 断言 `search_product`/`query_order`/`query_cart` 覆盖 | **回归失败** |
| `resources/regression/agent-memory-audit/` | 用比特严选人设 | 历史结果仍在，重跑不了 |
| `agent/src/test/.../AgentToolCatalogTest.java` | fixture 用 `sales_query` | 只是字符串 fixture，**不受影响** |
| `agent/src/test/.../McpToolProxyTest.java` | fixture 用 `query_order` | 同上，不受影响 |
| `agent/src/test/.../AgentStreamEventBridgeTest.java` | fixture 用 `query_order` | 同上，不受影响 |

**关键结论**：`agent` 模块的测试用的是**字符串常量当 fixture**，不依赖真实工具，删除后测试照常通过。真正受影响的是 `resources/regression/` 下的三套回归。

### 0.2 两个方案

| | 方案 A：只精简 mcp-server（推荐先做） | 方案 B：连数据集一起清理 |
|---|---|---|
| 改动范围 | mcp-server + 意图树新增 | A + 删 bit-selection/enterprise 初始化器 + 三套回归 |
| 工作量 | 约 1.5 天 | A + 2~3 天 |
| 风险 | 低，随时可回滚（git revert） | 高，回归套件要重写断言或删除 |
| 遗留 | 意图树有 30 个失效节点（只打 warn） | 干净 |
| 适合 | 先跑通演出查询链路 | 项目彻底转向星序业务时 |

**建议先做 A，跑通演出查询后再决定要不要做 B。** 下面的方案主体是 A，并在第 9 节列出 B 的清单。

---

## 1. 删除清单（mcp-server）

### 1.1 删除整个 `executor/bit/` 目录（13 个工具 + 3 个支撑类）

```
mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/
├── BitAfterSaleApplyMcpExecutor.java      apply_after_sale      (写)
├── BitAfterSaleQueryMcpExecutor.java      query_after_sale      (读)
├── BitCartQueryMcpExecutor.java           query_cart            (读)
├── BitCartUpdateMcpExecutor.java          update_cart_item      (写)
├── BitCouponQueryMcpExecutor.java         query_coupons         (读)
├── BitCouponRules.java                    —（内部规则类）
├── BitLogisticsQueryMcpExecutor.java      query_logistics       (读)
├── BitOrderAddressMcpExecutor.java        change_address        (写)
├── BitOrderCancelMcpExecutor.java         cancel_order          (写)
├── BitOrderCreateMcpExecutor.java         create_order          (写)
├── BitOrderPayMcpExecutor.java            pay_order             (写)
├── BitOrderQueryMcpExecutor.java          query_order           (读)
├── BitProductSearchMcpExecutor.java       search_product        (读)
├── BitTicketSubmitMcpExecutor.java        submit_ticket         (写)
├── BitOrderReleaser.java                  —（库存/券归还）
├── BitPendingOrderTimeoutTask.java        —（@Scheduled 超时释放）
└── BitToolSupport.java                    —（打码/排版/取参）
```

> 注意：`BitPendingOrderTimeoutTask` 带 `@Scheduled`，但 `McpServerApplication` **没有 `@EnableScheduling`**，所以它当前本来就没在跑。删除不影响运行行为。

### 1.2 删除整个 `executor/enterprise/` 目录（8 个工具）

```
Enterprise/
├── AssetMcpExecutor.java                  asset_query           (读)
├── AssetRenewalMcpExecutor.java           asset_renewal_submit  (写)
├── LeaveMcpExecutor.java                  leave_query           (读)
├── LeaveApplyMcpExecutor.java             leave_submit          (写)
├── MeetingRoomMcpExecutor.java            meeting_room_query    (读)
│                                          meeting_room_book     (写)  ← 一个类两个工具
├── SalesMcpExecutor.java                  sales_query           (读)
└── TicketMcpExecutor.java                 ticket_query          (读)
```

> 这 8 个工具**完全不依赖数据库**，都是内存假数据（`RandomUtil` / 固定 Map 生成）。删除零副作用。

### 1.3 删除 bit 专属的配置与数据层

```
mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/bit/
├── BitDataSourceConfig.java      # 数据源 + MyBatis + 事务模板
├── BitProperties.java            # starlineagent.bit.* 配置
└── BitSchemaInitializer.java     # 启动期建库建表

mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/
├── entity/       (10 个 DO)
├── mapper/       (9 个 Mapper)
├── result/       (5 个查询结果类)
└── handler/      (2 个：BitMetaObjectHandler、JsonbStringTypeHandler)

mcp-server/src/main/resources/db/bit-schema.sql
```

**⚠️ 注意**：`dao/handler/JsonbStringTypeHandler.java` 是通用工具类（名字带 Jsonb 但不含 bit 依赖），删除前确认没有其他引用。如果演出查询工具将来要存库，可以保留它。**第一期演出查询不建表，所以一起删。**

### 1.4 删完后 mcp-server 只剩

```
mcp/
├── McpServerApplication.java
├── config/
│   ├── McpServerConfig.java          ← 保留（含 readOnlyHint 强制校验）
│   └── McpToolAnnotations.java       ← 保留
└── executor/
    ├── McpToolSchema.java            ← 保留
    ├── McpToolResults.java           ← 保留
    ├── McpToolException.java         ← 保留
    ├── common/
    │   ├── CurrentDateMcpExecutor.java   current_date    (读)
    │   ├── WeatherMcpExecutor.java       weather_query   (读，⚠️ 假数据)
    │   └── YouComSearchMcpExecutor.java  youcom_search   (读，需 YDC_API_KEY)
    └── starline/                    ← 新增
        └── ArtistEventSearchMcpExecutor.java  search_artist_events (读)
```

---

## 2. 依赖清理（pom.xml)

当前 pom 有 5 个依赖，删完 bit 后可以砍掉 3 个：

```xml
<!-- 保留 -->
spring-boot-starter-webmvc      ← MCP 用 Streamable HTTP 需要
io.modelcontextprotocol.sdk:mcp ← 协议实现
agentscope-core (test scope)    ← 测试用
cn.hutool:hutool-all            ← MapUtil / StrUtil / JSONUtil

<!-- 可删除（都只为 bit 业务库服务） -->
spring-boot-starter-jdbc        ← 删：没有数据源了
mybatis-plus-spring-boot4-starter ← 删：没有 mapper 了
org.postgresql:postgresql       ← 删：不连库了
```

**⚠️ 关键**：`spring-boot-starter-jdbc` 必须删。留着但没配 `spring.datasource.url`，Spring Boot 启动会报 `Failed to determine a suitable driver class`。这是删数据源后最常见的启动失败原因。

**改造后的 pom 依赖段**：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp</artifactId>
        <version>${mcp-sdk.version}</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>
</dependencies>
```

---

## 3. application.yml 改造

```yaml
server:
  address: 127.0.0.1
  port: 9099

spring:
  application:
    name: starlineagent-mcp-server

starlineagent:
  artist-event-search:
    # 官方票务平台域名白名单（命中即 OFFICIAL，含子域名）
    official-domains:
      - damai.cn
      - maoyan.com
      - showstart.com
      - piaoxingqiu.com
      - 228.com.cn
    # 检索跨度对应的前后天数
    window:
      week:  { past: 7,   future: 30 }
      month: { past: 30,  future: 90 }
      year:  { past: 90,  future: 365 }
    # 发布时间超过多少小时算过期
    stale-hours: 168
    # 单次返回上限
    max-items: 10
```

**删掉原来的整个 `starlineagent.bit` 段**（`datasource` + `pending-order`）。

---

## 4. 保留的三个通用工具（含一个警告）

| 工具 | 实现 | 状态 |
|---|---|---|
| `current_date` | `LocalDate.now(clock)` 真实时间 | ✅ 可直接生产使用 |
| `youcom_search` | HTTP 调 You.com，需 `YDC_API_KEY` | ✅ 真实检索，但要配 Key |
| `weather_query` | **`new Random(date.toEpochDay()*31 + city.hashCode())` 生成假数据** | ⚠️ 见下 |

```java
// WeatherMcpExecutor:196-200 —— 这是当前实现
private WeatherData generateWeatherForDate(String city, LocalDate date) {
    double[] coords = CITY_COORDINATES.get(city);
    long seed = date.toEpochDay() * 31 + city.hashCode();
    Random random = new Random(seed);
    // 用 seed 生成温度、湿度、风力……
}
```

**它是确定性假数据**：同一天同一城市结果稳定，看起来像真的，但和真实天气毫无关系。

**处置建议（三选一）**：

| 选项 | 做法 | 适合 |
|---|---|---|
| a. 保留但**不挂意图节点** | 代码留着，意图树不配 → 模型看不到 | **推荐**。等接入真实天气 API 再启用 |
| b. 保留并在 description 里标注 | 加「演示数据，非真实天气」 | 能接受演示误导 |
| c. 一起删掉 | 归入第 1 节删除清单 | 演出场景不需要天气 |

**不要选 b**。模型会照 description 转述，用户看到「明天北京 26℃ 有雨」会当真的——演出当天带不带伞是真实决策。

---

## 5. 演出查询工具实现

### 5.1 新增 `WebSearchClient` 抽象

不把业务逻辑绑死在某个搜索供应商上。当前用 You.com（已有 Key），将来换元宝搜索只改一个实现类。

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

/**
 * 网页检索的抽象，屏蔽供应商差异
 * 实现类只负责"发请求 + 把响应拍平成 RawResult"，不做任何业务判定
 */
public interface WebSearchClient {

    /**
     * @param query     检索词
     * @param freshness 时间范围 day/week/month/year，null 表示不限
     * @param count     期望条数（实现类可自行截断）
     */
    List<RawResult> search(String query, String freshness, int count);

    /**
     * 供应商原始结果拍平后的最小字段集
     * publishTime / siteName 可能为 null——不同供应商给的字段不同，缺失是常态
     */
    record RawResult(String title, String url, String excerpt,
                     Instant publishTime, String siteName) {
    }
}
```

### 5.2 You.com 实现（复用现有 HTTP 模式）

参考 `YouComSearchMcpExecutor.doSearch()` 的写法，用 `java.net.http.HttpClient`，**不引入新依赖**。

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

@Slf4j
@Component
@ConditionalOnProperty(name = "YDC_API_KEY")
@RequiredArgsConstructor
public class YouComWebSearchClient implements WebSearchClient {

    private static final String ENV_API_KEY = "YDC_API_KEY";

    /** 可测试性：单元测试指向本地 stub */
    String apiUrl = "https://ydc-index.io/v1/search";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<RawResult> search(String query, String freshness, int count) {
        String apiKey = System.getenv(ENV_API_KEY);
        if (StrUtil.isBlank(apiKey)) {
            throw new McpToolException("联网检索未配置：请设置环境变量 YDC_API_KEY");
        }
        try {
            StringBuilder url = new StringBuilder(apiUrl)
                    .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&count=").append(count);
            if (StrUtil.isNotBlank(freshness)) {
                url.append("&freshness=").append(freshness);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-API-Key", apiKey)
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 不回显响应体，避免泄露账号信息
                throw new McpToolException("检索服务返回异常状态码: " + response.statusCode());
            }
            return parse(response.body());
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            log.error("联网检索失败, query={}", query, e);
            throw new McpToolException("联网检索暂时不可用");
        }
    }

    /** web 与 news 两段合并；除 title/url 外全部防御式读取 */
    private List<RawResult> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body).path("results");
        List<RawResult> results = new ArrayList<>();
        collect(results, root.path("web"));
        collect(results, root.path("news"));
        return results;
    }

    private void collect(List<RawResult> out, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            out.add(new RawResult(
                    item.path("title").asText(""),
                    item.path("url").asText(""),
                    resolveExcerpt(item),
                    parsePublishTime(item),      // ← 字段可能不存在，返回 null
                    null));                      // You.com 不给 site_name，由 URL 推
        }
    }

    private String resolveExcerpt(JsonNode item) {
        String description = item.path("description").asText("");
        if (!description.isBlank()) {
            return description;
        }
        JsonNode snippets = item.path("snippets");
        return snippets.isArray() && !snippets.isEmpty() ? snippets.get(0).asText("") : "";
    }

    /**
     * You.com 的发布时间字段名不稳定，按可能的名字逐一试；都拿不到返回 null
     * ⚠️ 实施时先打印一次真实响应确认字段名，不要凭猜
     */
    private Instant parsePublishTime(JsonNode item) {
        for (String field : List.of("page_age", "published_time", "publish_time", "age")) {
            String raw = item.path(field).asText("");
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignored) {
                // "3 days ago" 这类相对时间不解析，返回 null 由上层标"无法判断"
            }
        }
        return null;
    }
}
```

### 5.3 配置类

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

@Data
@Component
@ConfigurationProperties(prefix = "starlineagent.artist-event-search")
public class ArtistEventSearchProperties {

    /** 官方域名白名单，含子域名匹配 */
    private List<String> officialDomains = List.of(
            "damai.cn", "maoyan.com", "showstart.com", "piaoxingqiu.com", "228.com.cn");

    /** 检索跨度对应的前后天数 */
    private Map<String, Window> window = Map.of(
            "week",  new Window(7, 30),
            "month", new Window(30, 90),
            "year",  new Window(90, 365));

    /** 发布时间超过多少小时算过期；检索场景取 7 天 */
    private long staleHours = 168;

    /** 单次返回上限 */
    private int maxItems = 10;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Window {
        private int past;
        private int future;
    }
}
```

### 5.4 主执行器

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArtistEventSearchMcpExecutor {

    private static final String TOOL_ID = "search_artist_events";

    private static final String DESCRIPTION =
        "查询某艺人近期的演出安排：演唱会、巡演、见面会的场次、城市、场馆、演出日期、"
      + "开票时间与售票状态。内部先联网检索，再标注每条来源的 sourceType"
      + "（OFFICIAL 官方渠道 / UNVERIFIED 未核实）、isStale（信息是否过期）、"
      + "saleStatus（售票状态），返回结构化列表。"
      + "\n\n适用：用户问「某人最近有什么演出」「某人今年有演唱会吗」「某场什么时候开票」"
      + "「在哪个城市办」「票还买得到吗」等，涉及具体演出安排的问题。"
      + "\n\n不适用：艺人个人资料（生日、出道时间、代表作、获奖、感情状况）、"
      + "娱乐八卦、歌曲歌词、与演出无关的资讯——这类问题直接回答，不要调用本工具。"
      + "\n\n重要："
      + "① total=0 表示本次检索没有找到可信结果，不代表该艺人没有演出安排，"
      + "必须如实说明并建议关注官方渠道，绝不能编造场次、城市或时间；"
      + "② sourceType=UNVERIFIED 只能作为未核实线索陈述，不得作为结论，"
      + "更不得作为创建提醒的依据；"
      + "③ isStale=true 时必须向用户说明该信息发布时间较早、可能已变化。"
      + "\n\n涉及「最近」「今年」「下个月」等相对时间时，先调用 current_date 确认当前日期。"
      + "\n\n本工具只查询，不创建提醒。用户要设提醒请用创建提醒的工具。";

    /** 噪音标记：出现这些词且不含演出语境，说明只是借用艺人名 */
    private static final List<String> NOISE_MARKERS = List.of(
            "同款", "翻唱", "模仿秀", "应援", "周边", "壁纸", "头像", "歌词", "表情包");

    /** 演出语境标记 */
    private static final List<String> EVENT_MARKERS = List.of(
            "演唱会", "巡演", "音乐会", "见面会", "开票", "门票", "场馆", "站");

    private final WebSearchClient searchClient;
    private final ArtistEventSearchProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public McpServerFeatures.SyncToolSpecification artistEventSearchToolSpecification() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("artist", "艺人姓名，中文常用名，如「薛之谦」。"
                        + "用户说昵称或错别字时按最接近的正式艺人名传入，如「小薛」传「薛之谦」"))
                .optional(string("city", "限定城市，如「北京」「上海」；用户没提城市就不要传"))
                .optional(string("freshness", "检索跨度：week(近7天+未来30天)、"
                        + "month(近30天+未来90天，默认)、year(近90天+未来365天)")
                        .options(List.of("week", "month", "year"))
                        .defaultTo("month"))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name(TOOL_ID)
                        .description(DESCRIPTION)
                        .inputSchema(inputSchema)
                        .annotations(McpToolAnnotations.READ_ONLY)
                        .build())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String artist = StrUtil.trimToNull(MapUtil.getStr(args, "artist"));
            if (artist == null) {
                return McpToolResults.error("请提供艺人姓名");
            }
            String city = StrUtil.trimToNull(MapUtil.getStr(args, "city"));
            String freshness = StrUtil.blankToDefault(MapUtil.getStr(args, "freshness"), "month");
            if (!properties.getWindow().containsKey(freshness)) {
                return McpToolResults.error("freshness 参数不合法，可选值：week、month、year");
            }

            String query = buildQuery(artist, city);
            int fetchCount = properties.getMaxItems() * 3;   // 多抓一些，过滤后仍有余量
            List<WebSearchClient.RawResult> raw = searchClient.search(query, freshness, fetchCount);

            Instant now = Instant.now();
            List<EventItem> items = new ArrayList<>();
            int filtered = 0;
            for (WebSearchClient.RawResult result : raw) {
                if (!isAboutArtist(result, artist)) {
                    filtered++;
                    continue;
                }
                items.add(toEventItem(result, now));
            }
            items.sort(Comparator
                    .comparing((EventItem item) -> phaseRank(item.relativePhase()))
                    .thenComparing(item -> trustRank(item.sourceType()))
                    .thenComparing(EventItem::eventDate, Comparator.nullsLast(Comparator.naturalOrder())));
            if (items.size() > properties.getMaxItems()) {
                items = new ArrayList<>(items.subList(0, properties.getMaxItems()));
            }

            return McpToolResults.success(objectMapper.writeValueAsString(
                    buildResponse(artist, query, freshness, items, filtered, raw.size(), now)));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("演出查询", e);
        }
    }

    // ===== ① 实体相关性 =====

    /**
     * 标题+摘要里必须出现艺人名，且不能只是"借用艺人名"的噪音
     * <p>
     * 只做丢弃，不做打分——一旦有分数，模型就会按分数排序做判断，下沉就白做了
     */
    private boolean isAboutArtist(WebSearchClient.RawResult result, String artist) {
        String text = StrUtil.emptyIfNull(result.title()) + " "
                + StrUtil.emptyIfNull(result.excerpt());
        if (!text.contains(artist)) {
            return false;
        }
        boolean hasNoise = NOISE_MARKERS.stream().anyMatch(text::contains);
        boolean hasEvent = EVENT_MARKERS.stream().anyMatch(text::contains);
        return !hasNoise || hasEvent;
    }

    // ===== ②③④ 三层判定 =====

    private EventItem toEventItem(WebSearchClient.RawResult result, Instant now) {
        String domain = extractDomain(result.url());
        String sourceType = isOfficial(domain) ? "OFFICIAL" : "UNVERIFIED";
        String text = StrUtil.emptyIfNull(result.title()) + " " + StrUtil.emptyIfNull(result.excerpt());

        StaleMark stale = judgeStale(result.publishTime(), now);

        return new EventItem(
                result.title(),
                classifyPhase(text, now),          // relativePhase
                extractCity(text),
                null,                               // venue：文本抽取不可靠，留空让模型读 excerpt
                extractEventDate(text),             // 抽不到为 null
                extractSaleStartTime(text),         // 抽不到为 null
                extractSaleStatus(text),
                sourceType,
                domain,
                result.url(),
                result.publishTime(),
                stale.stale(),
                stale.reason(),
                clip(result.excerpt(), 300));
    }

    /** 域名提取：解析失败返回空串，不抛异常 */
    private String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** 白名单匹配：精确相等或子域名后缀 */
    private boolean isOfficial(String domain) {
        if (StrUtil.isBlank(domain)) {
            return false;
        }
        return properties.getOfficialDomains().stream()
                .anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    /**
     * 发布时间超过阈值算过期
     * <p>
     * 拿不到发布时间时返回 isStale=false 但给出原因——不能默认过期（全量不可用），
     * 也不能默认为新（会误导）
     */
    private StaleMark judgeStale(Instant published, Instant now) {
        if (published == null) {
            return new StaleMark(false, "来源未提供发布时间，无法判断时效");
        }
        long hours = Duration.between(published, now).toHours();
        if (hours > properties.getStaleHours()) {
            return new StaleMark(true,
                    "该信息发布于 " + (hours / 24) + " 天前，可能已变化");
        }
        return new StaleMark(false, null);
    }

    /** 从文本猜演出是否已过；抽不到返回 TBD */
    private String classifyPhase(String text, Instant now) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate date = extractEventDate(text);
        if (date == null) {
            return "TBD";
        }
        if (date.isBefore(today)) {
            return "PAST";
        }
        return date.isAfter(today) ? "UPCOMING" : "ONGOING";
    }

    /** 兜底：从 yyyy-MM-dd / yyyy年M月d日 形态里取第一个日期 */
    private LocalDate extractEventDate(String text) {
        Matcher iso = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})").matcher(text);
        if (iso.find()) {
            return safeDate(iso.group(1), iso.group(2), iso.group(3));
        }
        Matcher cn = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日").matcher(text);
        if (cn.find()) {
            return safeDate(cn.group(1), cn.group(2), cn.group(3));
        }
        return null;
    }

    private LocalDate safeDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String extractSaleStartTime(String text) {
        Matcher matcher = Pattern.compile(
                "(20\\d{2}[-年]\\d{1,2}[-月]\\d{1,2}日?)\\s*(\\d{1,2}:\\d{2})").matcher(text);
        return matcher.find() ? matcher.group(1) + " " + matcher.group(2) : null;
    }

    /**
     * 售票状态抽取；UNKNOWN 是正常高频返回值，不要为了"看起来完整"去猜
     */
    private String extractSaleStatus(String text) {
        if (text.contains("售罄") || text.contains("已抢完") || text.contains("已售完")) {
            return "SOLD_OUT";
        }
        if (text.contains("已结束") || text.contains("已落幕")) {
            return "CLOSED";
        }
        if (text.contains("待开票") || text.contains("即将开票") || text.contains("预约")) {
            return "PENDING";
        }
        if (text.contains("售票中") || text.contains("立即购买") || text.contains("立即预订")) {
            return "ON_SALE";
        }
        return "UNKNOWN";
    }

    private String extractCity(String text) {
        // 从已知城市表里找第一个命中的，避免正则误抽
        for (String city : CITY_NAMES) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private static final List<String> CITY_NAMES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安",
            "重庆", "长沙", "天津", "苏州", "郑州", "青岛", "大连", "厦门", "昆明",
            "哈尔滨", "三亚", "合肥", "福州", "济南", "沈阳", "长春", "石家庄",
            "太原", "南昌", "贵阳", "南宁", "兰州", "海口", "乌鲁木齐", "呼和浩特");

    private String buildQuery(String artist, String city) {
        return city == null
                ? artist + " 演唱会 开票 官方"
                : artist + " " + city + " 演唱会 开票 官方";
    }

    // ===== 组装返回 =====

    private Map<String, Object> buildResponse(String artist, String query, String freshness,
                                              List<EventItem> items, int filtered,
                                              int rawCount, Instant now) {
        ArtistEventSearchProperties.Window window = properties.getWindow().get(freshness);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artist", artist);
        response.put("query", query);
        response.put("searchedAt", now.atZone(ZoneId.of("Asia/Shanghai")).toString());
        response.put("searchedRange", Map.of(
                "from", today.minusDays(window.getPast()).toString(),
                "to", today.plusDays(window.getFuture()).toString()));
        response.put("total", items.size());
        response.put("items", items);
        response.put("notes", buildNotes(items, filtered, rawCount, freshness, window));
        return response;
    }

    /**
     * notes 是防编造的最后一道防线：只返回 total=0 的空壳，模型很可能开始"发挥"
     */
    private List<String> buildNotes(List<EventItem> items, int filtered, int rawCount,
                                    String freshness, ArtistEventSearchProperties.Window window) {
        List<String> notes = new ArrayList<>();
        if (rawCount == 0) {
            notes.add("本次检索没有返回任何结果，可能是检索服务异常或关键词未命中。");
            notes.add("请如实告知未查到，不要据此推断该艺人没有演出。");
            return notes;
        }
        long official = items.stream().filter(i -> "OFFICIAL".equals(i.sourceType())).count();
        long upcoming = items.stream()
                .filter(i -> "UPCOMING".equals(i.relativePhase())).count();
        if (items.isEmpty()) {
            notes.add("检索到 " + rawCount + " 条结果，但均与「该艺人的演出」无关，已全部过滤。");
            notes.add("这不代表该艺人没有演出安排：可能尚未官宣、已结束，或本次检索未覆盖。");
            notes.add("请如实告知未查到，并建议关注艺人官方微博与主办方公告，不要编造场次、城市或时间。");
        } else {
            notes.add("检索 " + rawCount + " 条，保留 " + items.size() + " 条"
                    + (filtered > 0 ? "（过滤 " + filtered + " 条与演出无关的结果）" : "")
                    + "；其中官方渠道 " + official + " 条。");
            if (upcoming == 0) {
                notes.add("保留的结果中没有「即将到来」的场次，可能均已结束。"
                        + "若确实如此，可以告知用户近期暂无新安排并说明最近一场的时间，不要只说查不到。");
            }
            notes.add("检索区间：" + LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(window.getPast())
                    + " 至 " + LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(window.getFuture())
                    + "。若用户需要更远期的安排，可放宽检索跨度。");
        }
        return notes;
    }

    private int phaseRank(String phase) {
        return switch (phase) {
            case "UPCOMING" -> 0;
            case "ONGOING" -> 1;
            case "TBD" -> 2;
            default -> 3;          // PAST 排最后
        };
    }

    private int trustRank(String sourceType) {
        return "OFFICIAL".equals(sourceType) ? 0 : 1;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private record StaleMark(boolean stale, String reason) {}

    /** 返回条目；字段为 null 表示未能从文本抽取，模型读 excerpt 自行判断 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EventItem(
            String title,
            String relativePhase,
            String city,
            String venue,
            LocalDate eventDate,
            String saleStartTime,
            String saleStatus,
            String sourceType,
            String domain,
            String sourceUrl,
            Instant publishedAt,
            boolean isStale,
            String staleReason,
            String excerpt) {}
}
```

---

## 6. 意图树改造

### 6.1 星序演出意图树

```sql
-- ========== 顶层域（非叶子，仅组织） ==========
INSERT INTO t_intent_node (id, intent_code, name, level, parent_code, description,
                           kind, sort_order, enabled)
VALUES ('2001', 'STARLINE', '星序演出服务', 0, NULL,
        '艺人演出信息查询、开票提醒管理', 0, 10, 1);

-- ========== 演出查询：MCP 叶子，只读免确认 ==========
INSERT INTO t_intent_node (id, intent_code, name, level, parent_code, description,
                           mcp_tool_id, require_confirm, kind, sort_order, enabled)
VALUES ('2002', 'ARTIST_EVENT_QUERY', '演出查询', 1, 'STARLINE',
        '查询某艺人近期的演出安排：场次、城市、场馆、演出日期、开票时间与售票状态。'
     || '适用：问某人最近有什么演出、什么时候开票、在哪个城市办、票还能不能买。'
     || '不适用：艺人生日、出道时间、代表作、八卦等个人资料，这类直接回答不要调用。'
     || '返回含 sourceType（OFFICIAL 官方 / UNVERIFIED 未核实）与 isStale（是否过期）；'
     || 'total=0 不代表该艺人没有演出，不得编造场次。',
        'search_artist_events', 0, 2, 20, 1);

-- ========== 当前日期：通用 MCP 叶子，演出查询依赖它 ==========
INSERT INTO t_intent_node (id, intent_code, name, level, parent_code, description,
                           mcp_tool_id, require_confirm, kind, sort_order, enabled)
VALUES ('2003', 'STARLINE_CURRENT_DATE', '当前日期', 1, 'STARLINE',
        '查询当前日期与星期，把今天、明天、这周、下个月这类相对说法换算成明确日期。'
     || '涉及「最近」「今年」「下个月」等相对时间时必须先调用本工具。',
        'current_date', 0, 2, 30, 1);

-- ========== 通用联网检索：兜底查资料 ==========
INSERT INTO t_intent_node (id, intent_code, name, level, parent_code, description,
                           mcp_tool_id, require_confirm, kind, sort_order, enabled)
VALUES ('2004', 'STARLINE_WEB_SEARCH', '联网检索', 1, 'STARLINE',
        '通用联网搜索，返回带来源链接和摘录的网页与新闻结果。'
     || '用于查询演出之外的通用信息；查询艺人演出请优先用「演出查询」工具。',
        'youcom_search', 0, 2, 40, 1);
```

> `weather_query` 故意**不配意图节点**（见第 4 节：它是假数据）。代码保留，等接入真实天气 API 后再加节点。

### 6.2 禁用指向已删工具的旧节点（方案 A 必做）

不删数据集，但把这些节点置为 `enabled=0`，避免每次启动刷 warn：

```sql
-- bit 工具节点（13 个工具对应的节点）
UPDATE t_intent_node SET enabled = 0, update_time = CURRENT_TIMESTAMP
WHERE kind = 2 AND deleted = 0 AND mcp_tool_id IN (
    'search_product','query_cart','update_cart_item','query_coupons',
    'create_order','pay_order','cancel_order','change_address',
    'query_order','query_logistics','apply_after_sale','query_after_sale','submit_ticket'
);

-- enterprise 工具节点（8 个工具）
UPDATE t_intent_node SET enabled = 0, update_time = CURRENT_TIMESTAMP
WHERE kind = 2 AND deleted = 0 AND mcp_tool_id IN (
    'asset_query','asset_renewal_submit','leave_query','leave_submit',
    'meeting_room_query','meeting_room_book','sales_query','ticket_query'
);
```

### 6.3 ⚠️ 必须清 Redis 缓存

```bash
redis-cli -h 127.0.0.1 -p 6379 DEL "starlineagent:intent:tree"
```

**为什么必须做**：意图树缓存在 Redis，key 是 `starlineagent:intent:tree`，**TTL 7 天**（`IntentTreeCacheManager.CACHE_EXPIRE_DAYS`）。只有走 `IntentTreeServiceImpl` 的增删改才会自动清缓存；**直接执行 SQL 绕过了 Service，缓存不会清**。

不清的后果：**新工具最长 7 天不出现，旧设置最长 7 天不变，且没有任何报错。**

清完之后 `ReActAgentProvider` 会因 `catalog.fingerprint()` 变化自动重建 ReActAgent，不用重启应用。

---

## 7. 测试

### 7.1 单元测试骨架

```java
class ArtistEventSearchMcpExecutorTest {

    private final ArtistEventSearchProperties properties = new ArtistEventSearchProperties();

    /** 白名单命中：精确与子域名都算官方 */
    @Test
    void officialDomainMatchesSubdomain() {
        // detail.damai.cn → OFFICIAL
        // damai.cn        → OFFICIAL
        // mp.weixin.qq.com → UNVERIFIED
    }

    /** 噪音过滤：同款/歌词类结果被丢弃 */
    @Test
    void dropsNoiseResults() {
        // "薛之谦同款外套" → 丢弃
        // "薛之谦演唱会开票" → 保留
        // "薛之谦同款但他演唱会开票了" → 保留（含演出语境）
    }

    /** 空结果必须给 notes，不能只返回 total=0 */
    @Test
    void emptyResultCarriesNotes() {
        // searchClient 返回空列表 → response.notes 非空且包含"不代表"
    }

    /** 拿不到发布时间：isStale=false 但 staleReason 说明无法判断 */
    @Test
    void missingPublishTimeIsUncertainNotStale() {
        // publishTime=null → isStale=false, staleReason="来源未提供发布时间，无法判断时效"
    }

    /** 检索跨度三个取值都同时向前向后 */
    @Test
    void everyFreshnessLooksBothDirections() {
        // week  → from=today-7,  to=today+30
        // month → from=today-30, to=today+90
        // year  → from=today-90, to=today+365
    }
}
```

### 7.2 验证「先打印真实响应」

**阶段 1 不要直接写 `parsePublishTime`**。先加一行日志把 You.com 原始 JSON 打出来：

```java
log.info("You.com 原始响应: {}", response.body());   // 临时，验证完删掉
```

确认三件事：

1. 有没有发布时间字段？字段名叫什么？
2. `results.web` / `results.news` 的实际结构？
3. 有没有 `site_name` 之类可直接用的域名信息？

**不要凭猜写字段名**——这是 v1 方案里明确记录过的坑。

---

## 8. 验收清单

### 8.1 删除部分

- [ ] `executor/bit/` 与 `executor/enterprise/` 目录已删除
- [ ] `config/bit/` 与 `dao/` 目录已删除
- [ ] `resources/db/bit-schema.sql` 已删除
- [ ] pom 里 `spring-boot-starter-jdbc`、`mybatis-plus-spring-boot4-starter`、`postgresql` 已删除
- [ ] `application.yml` 里 `starlineagent.bit` 整段已删除
- [ ] **应用能启动**（重点：删了 jdbc starter 才不会报 `Failed to determine a suitable driver class`）
- [ ] 启动日志里没有 `unavailableToolIds` 的大量 warn

### 8.2 演出查询

- [ ] 问「薛之谦最近有什么演出」→ 调 `search_artist_events`，返回结构化 `items`
- [ ] 结果里 `damai.cn` 的条目 `sourceType=OFFICIAL`
- [ ] 结果里 `mp.weixin.qq.com` 的条目 `sourceType=UNVERIFIED`
- [ ] 「同款」「歌词壁纸」类结果被过滤掉
- [ ] 返回里有 `searchedRange {from,to}` 与 `items[].relativePhase`
- [ ] 查一个查不到的艺人 → 返回 `notes`，模型如实说没查到、不编造
- [ ] 只有已结束场次时，模型说「近期暂无新安排，上一场是 X 月 X 日」而不是「查不到」
- [ ] `notes` 里检测到"没有即将到来的场次"时会给出该说法

### 8.3 边界与负向

- [ ] 问「薛之谦生日是什么时候」→ **不调用**本工具（description 边界生效）
- [ ] 问「帮我设个提醒」→ **不调用**本工具（走提醒工具）
- [ ] 用户说「最近」→ 模型先调 `current_date` 再调本工具
- [ ] 用户说「小薛」→ 模型传 `artist=薛之谦`
- [ ] `freshness` 传非法值 → 返回明确错误而不是静默走默认

### 8.4 装配与可观测

- [ ] `/agent/v1/meta` 的 `mcpToolCount` = 3（current_date / youcom_search / search_artist_events）
- [ ] 意图树里 4 个新节点 `kind=2` 且**无 children**（非叶子会让工具静默消失）
- [ ] **执行完 SQL 后清了 Redis `starlineagent:intent:tree`**
- [ ] 提问后 `t_agent_run_node` 出现 `nodeName=search_artist_events`
- [ ] bad case 规则 A2（工具失败）/ A5（耗时）/ A8（空结果）能对它生效

---

## 9. 方案 B 的额外清单（暂不执行）

如果决定彻底清理数据集，在方案 A 之上还要：

```
删除目录：
  resources/initializer/bit-selection/
  resources/initializer/enterprise-knowledge-base/
  resources/regression/agent-compaction/
  resources/regression/agent-concurrency/
  resources/regression/agent-memory-audit/

修改：
  resources/regression/agent-memory-evaluation/datasets/bit-selection.json
    → 换成星序演出场景的数据集（建议新建 starline-events.json）
  resources/regression/agent-memory-evaluation/README.md
    → 更新"先初始化比特严选数据"那段
  resources/regression/README.md
    → 移除三个已删套件的条目
  resources/deployment/README.md:42
    → 更新"MCP 为比特严选工具"的说明

数据库：
  DROP DATABASE starlineagent_bit;
  删除 t_intent_node 里所有指向已删工具的节点（不只是 enabled=0）
```

**风险提示**：`agent-memory-evaluation` 是目前**唯一能验证 Agent 记忆/压缩能力的回归套件**，删掉 bit 数据集等于砍掉这条验证链路。**建议先新建星序数据集补齐，再删旧的**——不要先删后补。

---

## 10. 实施顺序

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **1** | 删 bit + enterprise + dao + config/bit + schema.sql；改 pom、application.yml | 应用启动成功，`/agent/v1/meta` 显示 3 个工具 |
| **2** | 新增 `WebSearchClient` + `YouComWebSearchClient`，先只打印真实响应 | 能拿到 You.com 原始 JSON，确认字段名 |
| **3** | 写 `ArtistEventSearchMcpExecutor`（先只做白名单打标）+ 单元测试 | 单测通过；能返回 `sourceType` |
| **4** | 补实体过滤 + 空结果 notes + 时效 + 售票状态 | 「同款」被过滤；查不到时有 notes |
| **5** | 建 4 个意图节点 + 禁用旧节点 + **清 Redis** | 模型能看到并调用新工具 |
| **6** | 端到端验证 + 观察一周真实问题 | 13 条验收清单全过 |
