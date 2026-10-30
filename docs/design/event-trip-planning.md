# 普通出行与观演规划设计方案（从简入深）

> 面向执行者（Codex）的施工单。本文分三期，每期可独立上线、独立验收，**不要跨期提前实现**。
>
> - 版本：v2（2026-10-29，按用户的自然问路需求扩展）
> - 日期：2026-10-29
> - 关联：沿用现有 `mcp-server` 工具模式、Skill 渐进式披露、前端卡片渲染
> - 核心原则：**可查事实用工具、可算约束用代码、可编方案用模型**。三者不许混用

---

## 1. 设计前提（先读，决定后面所有取舍）

### 1.1 普通问路与观演倒推是两种意图

用户问「我从南京去上海怎么走」时，直接提供城市间路线；不要求用户先说明出行目的，也不把普通问路判为超出演出助手范围。用户问「去看上海演出几点出门」时，再按演出时间倒推。

本方案保留可验证的单程观演倒推，同时新增轻量的普通路线查询。它不扩展为旅游行程编排、景点推荐或多日游。

| 意图 | 工具 | 结果 |
|---|---|---|
| 城市/地址之间怎么走 | `plan_trip` | 高德公共交通路线概览、自驾路线及假设 |
| 为了赶演出几点出发 | `plan_event_trip` | 根据演出时间倒推最晚出发时间 |

### 1.2 三层职责划分（越界即返工）

| 层 | 内容 | 实现方式 | 禁止做法 |
|---|---|---|---|
| 可查事实 | 距离、耗时、天气、坐标、周边 POI | 调高德 API | 禁止模型估算距离和耗时 |
| 可算约束 | 提前到场量、倒推出发时间、末班车判断、跨天切换 | Java 纯函数 | 禁止交给模型心算 |
| 可编方案 | 怎么解释、给几个备选、语气、提醒事项 | 模型 | 禁止让模型编造车次号和时间 |

### 1.3 数据源的真实边界（最重要的一条，别跳过）

高德路径规划 Web 服务支持综合火车、公交、地铁的公共交通路线，也支持跨城规划；跨城请求需要起点城市 `city` 和终点城市 `cityd`。提供完整出发日期与时间时可以据此筛选路线，不提供时应省略日期和时间参数。该能力是路线规划，不提供车票库存或订票，也不查询航班时刻。[高德路径规划 API](https://lbs.amap.com/api/webservice/guide/api/direction)

这意味着：

| 场景 | 能否做 | 数据来源 |
|---|---|---|
| 自驾全程 | ✅ 完整支持 | 高德驾车路径规划 |
| 市内及跨城公共交通 | ✅ 路线规划 | 高德公交路径规划（火车、公交、地铁） |
| 具体班次筛选 | ✅ 需完整出发日期和时间 | 高德公交路径规划；结果不表示有票 |
| 机票与订票 | ❌ 不支持 | 本期不接入航班或票务服务 |

**绝对禁止的做法**：让模型凭训练知识给出「G1234 次 16:00 发车」。这是本功能最大的质量事故来源——用户按编造的车次去车站，结果没有这趟车。

### 1.4 城际火车路线与时刻的边界

| 路径 | 做法 | 建议 |
|---|---|---|
| 普通路线查询 | 高德综合公共交通路线；跨城补充 `cityd` | ✅ `plan_trip` 支持 |
| 按班次时刻筛选 | 用户给出完整出发日期和时间后传给高德 | ✅ 只描述接口实际返回结果，不代表余票 |
| 观演单程倒推 | 用户给出已确认的城际到达时间：「我 18:00 到无锡东」→ 系统算市内段 | ✅ `plan_event_trip` 支持 |
| 航班、库存、订票 | 当前没有对应数据源 | ❌ 不支持 |
| 模型凭知识给车次 | 不可验证 | ❌ 永不采用 |

没有具体出发时刻时，`plan_trip` 仍可返回路线概览，但不显示或声称具体车次、发车时刻及余票。观演倒推工具只有在用户提供已确认的城际到达地点和时间后才计算场馆市内段。

---

## 2. 现状盘点：能复用什么

| 已有能力 | 位置 | 在新功能里的角色 |
|---|---|---|
| 演出查询工具 | `mcp-server/.../executor/starline/ArtistEventSearchMcpExecutor.java` | 提供场馆名、演出时间——整个倒推的锚点 |
| 工具注册模式 | `@Bean` 返回 `SyncToolSpecification`，被 `McpServerConfig` 自动收集 | 新工具照此加，无需改注册代码 |
| Schema 构造 | `McpToolSchema.object().required(...).optional(...)` | 入参定义 |
| 结果封装 | `McpToolResults.success(json)` / `.error(msg)` | 出参统一 |
| 读写标注 | `McpToolAnnotations.READ_ONLY` / `.WRITE` | 本功能全部只读 |
| 条件装配 | `@Conditional(AmapApiKeyConfiguredCondition.class)` | 高德 Key 未配置时自动不注册出行工具 |
| 前端卡片渲染 | `ArtistEventSearch` 的 description 已说明「由前端渲染为卡片」 | Phase 3 行程卡片沿用同一机制 |
| Skill 渐进式披露 | `SkillLoadTool` + `AgentSkillMaskingMiddleware` | 行程手册做成 skill，避免一上来就教模型 |
| 确认机制 | `AgentConfirmPayload` | Phase 3 涉及订票/订房时使用 |

---

## 3. Phase 1：单程倒推（最小闭环）

### 3.1 目标与边界

**输入**：场馆（或艺人+城市）、演出开始时间、出发地、城际到达时间（可选）

**输出**：最晚出门时间 + 每一段的耗时与来源 + 显式假设

**做**：
- 自驾全程倒推
- 城际到达后，市内最后一段倒推
- 提前到场量可配（默认 30 分钟）

**不做**：住宿、返程、多天、景点、订票、预算

### 3.2 新增文件

```
mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/trip/
├── TripPlanMcpExecutor.java          工具定义与编排
├── TripTimeCalculator.java           纯计算，无外部依赖，可单测
├── TripPlanProperties.java           配置（默认提前量、模式开关）
├── AmapRouteClient.java              高德路径规划封装
├── AmapApiKeyConfiguredCondition.java 条件装配
└── model/
    ├── TripPlan.java                 结果对象
    ├── TripPlanInput.java            入参对象
    ├── TripLeg.java                  单段
    ├── GeoPoint.java                 坐标
    └── RouteProvider.java            路由查询接口（便于单测注入假数据）
```

### 3.3 核心纯函数（先写这个，再写工具）

**这是整个 Phase 1 的地基**，必须先实现并单测通过，再接工具。

```java
public interface RouteProvider {
    /**
     * 查询两点之间在指定出发时刻的通行耗时
     * 实现类走高德；单测注入假数据，保证计算逻辑与外部依赖解耦
     */
    TripLeg route(String originAddress, String destinationAddress, String mode, LocalDateTime departAt);
}
```

```java
public final class TripTimeCalculator {

    /**
     * 从演出开始时间倒推最晚出发时间
     *
     * 倒推链：演出开始 → 减提前到场量 → 到场时间 → 减市内段耗时 → 市内出发时间
     *        → 若提供城际段则继续减城际耗时 → 城际出发时间
     *
     * 不变量：latestDeparture 必须早于 eventStartTime，且每段耗时都来自 RouteProvider，
     * 任何一环拿不到真实耗时都必须抛异常而不是估算——估算的车次时间会让用户误车
     */
    public TripPlan calculate(TripPlanInput input, RouteProvider routeProvider);
}
```

`TripPlanInput` 字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `venueName` / `venueAddress` | 是 | 场馆名用于展示，地址用于查路线 |
| `eventStartTime` | 是 | ISO 本地时间，必须带日期 |
| `originAddress` | 是 | 出发地（用户所在城市/具体地址） |
| `intercityArrivalTime` | 否 | 城际到达时间；给了就算两段，没给就只算市内段 |
| `intercityArrivalPlace` | 否 | 城际到达地点（车站/机场），与上面成对出现 |
| `mode` | 是 | `driving` / `transit` / `walking` |
| `arrivalBufferMinutes` | 否 | 提前到场量，默认取配置 30 |

`TripPlan` 输出字段：

```java
record TripPlan(
    String venueName,
    LocalDateTime eventStartTime,
    LocalDateTime arriveVenueBy,      // = eventStart - buffer
    TripLeg localLeg,                 // 市内段，必有
    TripLeg intercityLeg,             // 城际段，无则为 null
    LocalDateTime latestDeparture,    // 最终结论
    List<String> assumptions,         // 显式假设，必须回传给模型
    String confidence                 // HIGH / MEDIUM / LOW
)
```

配套的两个小类型（同样放在 `model/` 下）：

```java
/** 单段通行：来源必须是真实查询结果，source 用于让上层区分真实值与兜底值 */
record TripLeg(
    String from,
    String to,
    String mode,             // driving / transit / walking
    int durationMinutes,
    int distanceMeters,
    String source            // amap / user / fallback；model 不得据此编造
) {}

record GeoPoint(double longitude, double latitude) {}
```

**confidence 判定规则**（不要拍脑袋，按规则算）：| 值 | 条件 |
|---|---|
| HIGH | 两段耗时都拿到真实值，且总耗时 < 4 小时 |
| MEDIUM | 两段都有值，但总耗时 ≥ 4 小时（长途路况波动大） |
| LOW | 只算了市内段（用户未提供城际到达时间），或某段用了兜底模式 |

**assumptions 必须包含的条目**（缺一即返工）：
- 「耗时来自高德路径规划，未含实时路况拥堵，建议额外预留 15 分钟」
- 「未考虑安检、取票、找车位时间」——若 mode 是 driving 或用户提供了车站
- 若 `intercityLeg == null`：「未包含城际段，请自行确认到达时间」

### 3.4 高德客户端封装

```java
public class AmapRouteClient {

    /**
     * 只做一件事：调高德路径规划，返回耗时与距离
     * 不在这里做任何业务判断，也不缓存——缓存放到上层按需加
     */
    public TripLeg route(String origin, String destination, String mode, LocalDateTime departAt);

    /** 地址转坐标，路径规划前需要先转换 */
    public GeoPoint geocode(String address);
}
```

要点：
- 用项目已有的 HTTP 封装风格（参考 `WebSearchClient` / `YouComWebSearchClient`），不要引入新 HTTP 库
- **超时必须小于工具整体超时**，遵循超时金字塔：connect 1s < read 3s < 工具 8s
- 高德返回非零 `infocode` 时抛业务异常，不要返回 0 耗时
- 城市坐标查询失败时，返回明确错误，不要用城市中心点冒充场馆

### 3.5 工具定义

工具 ID：`plan_event_trip`

**description 必须照 `ArtistEventSearchMcpExecutor` 的写法**（那是本仓最好的范本，包含了适用范围、不适用场景、字段语义、禁止编造声明）。建议文本：

```
根据演出时间与出发地，倒推最晚出发时间并给出分段耗时。适用于「几点得从家走」「从车站到场馆要多久」「会不会迟到」这类问题；
不适用于行程编排、景点推荐、订票订房。

必须先用 search_artist_events 拿到演出时间与场馆，不要自行假设演出时间。
城际段（火车/航班）的时刻本工具无法查询：用户未提供到达时间时，只计算市内段，confidence 会降为 LOW，
此时必须明确告诉用户「城际段请自行确认，本结果只包含市内段」。

耗时数据来自高德路径规划，不含实时拥堵，也不含安检、取票、找车位的时间。
不得编造车次号、航班号、发车时间；不得在用户未提供到达时间时假装算过城际段。
返回结构里的 assumptions 必须原样转述给用户，不要省略。
```

入参 schema：

```java
McpToolSchema.object()
    .required(string("venue", "演出场馆名称，从 search_artist_events 的结果里取，不要自己编"))
    .required(string("eventStartTime", "演出开始时间，ISO 本地时间，例如 2026-11-15T19:30:00"))
    .required(string("origin", "出发地地址或城市，例如 上海市徐汇区某某路 或 无锡东站"))
    .optional(string("mode", "通行方式：driving（自驾，默认）、transit（公共交通）、walking（步行）")
            .options(List.of("driving", "transit", "walking"))
            .defaultTo("driving"))
    .optional(string("intercityArrivalTime", "城际到达时间，ISO 本地时间；用户坐火车/飞机且已确定到达时间时传入，不确定就不要传"))
    .optional(string("intercityArrivalPlace", "城际到达地点，例如 无锡东站；与 intercityArrivalTime 成对传入"))
    .optional(integer("arrivalBufferMinutes", "提前到场分钟数，默认 30"))
    .build();
```

标注：`McpToolAnnotations.READ_ONLY`（纯查询，可安全重试）

### 3.6 出参示例

```json
{
  "venue": "无锡大剧院",
  "eventStartTime": "2026-11-15T19:30:00",
  "arriveVenueBy": "2026-11-15T19:00:00",
  "localLeg": {
    "from": "无锡东站",
    "to": "无锡大剧院",
    "mode": "transit",
    "durationMinutes": 42,
    "distanceMeters": 13400,
    "source": "amap"
  },
  "intercityLeg": null,
  "latestDeparture": "2026-11-15T18:18:00",
  "assumptions": [
    "耗时来自高德路径规划，未含实时拥堵，建议额外预留 15 分钟",
    "未考虑安检、取票、找车位时间",
    "未包含城际段，请自行确认到达时间"
  ],
  "confidence": "LOW"
}
```

**注意 `intercityLeg: null` 时 `latestDeparture` 的含义是「从城际到达点出发的最晚时间」，不是「从家出发」。这一点必须在 description 和工具返回的 assumptions 里都说清**，否则模型会把它当作家门口时间转述给用户。

### 3.7 验收

**单测（必须先过）**，用假的 `RouteProvider`：

| 用例 | 输入 | 期望 |
|---|---|---|
| 标准市内 | 演出 19:30，buffer 30，市内 42 分钟 | latestDeparture = 18:18 |
| 两段 | 追加城际到达 18:00、城际 135 分钟 | latestDeparture = 16:03 |
| 跨天 | 演出 00:30，市内 60 分钟 | latestDeparture 落在前一天 23:00，日期正确 |
| 无真实耗时 | RouteProvider 抛异常 | 抛异常，**不得返回估算值** |
| 提前量边界 | buffer = 0 | arriveVenueBy = eventStartTime |
| 极端 buffer | buffer 大于总耗时 | 正常计算，不出现负数时间 |

**端到端**：
1. 问「无锡下个月有什么演出」→ 拿到一场
2. 问「我从上海开车过去来得及吗」→ 工具被调用，返回含 assumptions 的结果
3. 检查模型回复：**必须转述 assumptions，不得出现车次号**

### 3.8 普通城市路线查询（v2 新增）

观演倒推继续使用 `plan_event_trip`。普通问路单独调用只读 MCP 工具 `plan_trip`，不要求先查演出：

| 入参 | 必填 | 说明 |
|---|---|---|
| `origin` | 是 | 出发城市或详细地址 |
| `destination` | 是 | 目的城市或详细地址 |
| `departureAt` | 否 | 完整 ISO 本地日期时间；给出时才筛选对应时刻的公共交通路线 |
| `mode` | 否 | `all`（默认，同时查询公共交通和自驾）、`transit`、`driving` |

城市级地理编码按高德城市定位点规划，并把“非门到门路线”的假设交给模型转述。未提供出发时刻时仍返回路线概览，但不返回或声称具体车次、发车时间及余票。高德返回的参考费用不等于可购票；航班、订票和多日旅游行程不在本期范围。

**端到端**：问「我从南京出发，明天去上海，怎么走」→ 不被判断为超范围；加载出行技能，调用 `plan_trip`，返回高德公共交通与自驾选项及必要假设。

---

## 4. Phase 2：返程与住宿

### 4.1 返程倒推

新增工具 `plan_return_trip`，逻辑与 Phase 1 对称：

- 锚点是**演出结束时间**（不是开始时间）
- 加上散场拥挤缓冲（默认 20 分钟，可配）
- 倒推出「最晚什么时候必须离场」
- 若有末班车约束，判断是否赶得上，赶不上要明确提示

**末班车**是高德拿不到的数据，处理方式同 1.4：由用户提供或后续接第三方数据源。**不得编造**。

### 4.2 住宿建议

新增工具 `search_nearby_hotels`：

- 入参：场馆名、预算上限、入住/离店日期、步行/驾车可接受半径
- 用高德周边搜索按「酒店」类型查，返回名称、地址、距离、评分（若有）
- **只返回事实，不做推荐排序**——排序逻辑放模型，但模型必须基于返回的距离和价格，不得编造价格

注意：高德周边搜索不返回房价。价格必须标明「需自行查询」，这是硬约束。

### 4.3 验收

- 返程：演出 22:00 结束 + 缓冲 20 + 市内 42 分钟 → 21:00 必须离场
- 末班车：用户给末班车 22:30，算出赶不上时，回复必须明确说「赶不上末班车」
- 住宿：返回结果的每个价格字段要么是真实值，要么是 null + 提示语，不允许出现估算价格

---

## 5. Phase 3：行程对象与可重算

### 5.1 为什么需要对象化

用户一定会改需求：「别坐地铁，打车吧」「预算降到 300」「想多待一晚」。如果行程是一段自然语言，改一次就要全量重生成，模型极易改错前面已定的约束（比如新方案里忘了演出 19:30 开始）。

对象化的收益：可校验、可局部重算、可渲染、可复盘。

### 5.2 结构定义

```java
record TripItinerary(
    String itineraryId,
    String conversationId,
    LocalDateTime eventStartTime,
    List<TripSegment> segments,        // 按时间排序
    List<TripConstraint> constraints,  // 硬约束与软约束分开
    String status                      // DRAFT / CONFIRMED / EXPIRED
)

record TripSegment(
    String type,            // transport / stay / venue
    String from, String to,
    LocalDateTime startTime, LocalDateTime endTime,
    Map<String, Object> evidence,  // 来源工具名 + 原始返回值摘要
    boolean satisfied              // 是否满足所有硬约束
)

record TripConstraint(String type, String expression, boolean hard)
```

### 5.3 局部重算

新增工具 `revise_trip`：

- 入参：`itineraryId` + 变更项（如「交通方式改为 transit」）
- 只重算受影响的 segment，其余保持原值
- 重算后重新校验全部硬约束，返回哪些段变了、约束是否仍满足

**硬约束例**：`eventStartTime`、`末班车时间`、`预算上限`。重算后若违反硬约束，必须返回失败并说明冲突，不能静默给出一个违反约束的行程。

### 5.4 前端渲染

沿用 `search_artist_events` 的模式（它的 description 已说明「演出信息由前端渲染为卡片，只给一句总述」）。行程同理：

- 工具返回结构化 JSON，前端识别后渲染为时间轴卡片
- 模型只给一句总述，不重复列出卡片字段
- description 里必须写明「不要重复列出卡片字段，也不要在文字中创建或声称已创建提醒」

### 5.5 与确认机制对接

涉及订票、订房时走 `AgentConfirmPayload`，确认后由业务服务校验身份、库存、金额、幂等键。**行程对象只负责「计划」，不负责「下单」**，两者必须分开。

---

## 6. Skill 配置

新建 skill：`trip_planning`（通过现有 `AgentSkillController` 管理后台配置，不要在代码里硬编码）

- `tool_ids`：挂 `plan_event_trip`、`plan_return_trip`、`search_nearby_hotels`、`revise_trip`
- `description`（决定模型是否加载，按 four-part 写）：

```
出行与观演规划：支持普通城市/地址路线查询，以及根据演出时间和场馆倒推最晚出发时间。

适用于：用户问「我从南京去上海怎么走」「从某地到某地怎么去」「怎么去场馆」「几点出门」「来不来得及」，
或提到要看某场演出并涉及交通、时间安排时。普通问路使用 `plan_trip`，观演倒推使用 `plan_event_trip`。

不适用于：纯演出信息查询（用 search_artist_events）、景点推荐、多日旅游行程与订票。

与相邻能力的区别：只要涉及「为了看演出而安排时间」，就属于本技能；
涉及订票下单，属于票务技能，不在本技能范围。
```

- `content`（手册正文，≤500 行）建议章节：
  1. 必须先从 `search_artist_events` 拿场馆和时间，不得假设
  2. 城际火车时刻拿不到，如何处理（用户提供 or 只算市内段）
  3. 调用顺序：查演出 → 确认出发地 → 算单程 → 问是否需要返程 → 算返程 → 可选住宿
  4. 输出规范：必须转述 assumptions，必须给出 confidence，不得编造车次
  5. 常见追问的处理（「早一点到」→ 调大 buffer 重算；「打车呢」→ 改 mode 重算）

---

## 7. 提交与验收顺序

| 顺序 | 内容 | 验收 |
|---|---|---|
| 1 | `TripTimeCalculator` + 6 个单测 | 单测全绿，不依赖网络 |
| 2 | `AmapRouteClient` + 条件装配 | 配置 Key 后能查到真实耗时；不配 Key 时工具不注册 |
| 3 | `plan_event_trip` 工具 | 端到端问「从上海开车去无锡大剧院来得及吗」 |
| 4 | skill 配置 | 模型在相关问题下会加载手册；无关问题不加载 |
| 5 | 普通问路 | 「我从南京去上海怎么走」会加载技能并调用 `plan_trip`，不要求演出上下文 |
| 6 | Phase 2 两个工具 | 返程、住宿各自端到端 |
| 7 | Phase 3 对象化 + revise | 改一次交通方式，只有该段变化，约束仍满足 |

**每期独立提交，不要跨期混在一起。** Phase 1 单独就能上线，不需要等 Phase 2。

---

## 8. 明确不做的事（避免越做越大）

| 不做 | 原因 |
|---|---|
| 通用多日旅游行程 | 本期只解决路线与观演到场时间，不做完整旅游编排 |
| 景点推荐与排序 | 需要点评数据，高德不提供质量维度 |
| 机票/火车票预订 | 涉及支付、库存、退改，属另一个系统 |
| 编造任何车次/航班号 | 本功能最大的质量红线 |
| 用模型估算距离和耗时 | 必须来自工具，否则用户会误车 |
| Phase 1 就做对象化 | 先用文本跑通闭环，验证需求真实存在后再投入 |

---

## 9. 附录：新工具 description 的写法规范

`ArtistEventSearchMcpExecutor` 的 `DESCRIPTION` 是本仓最好的范本，建议新工具全部照它写。它包含七个要素，缺一不可：

1. **一句话说明它做什么**
2. **适用场景**（什么时候该调用）
3. **不适用场景**（什么时候不该调用）——这条最能降低误触发
4. **字段语义**（枚举值的含义，例如 `stage=APPROVED` 表示什么）
5. **数据来源与局限**（数据来自哪、缺什么、`total=0` 意味着什么）
6. **禁止行为**（不得编造场次/日期/时间）
7. **输出约定**（前端渲染卡片，只给总述，不重复字段）

新工具的 description 如果少于 300 字，基本可以判断没写清第 3、5、6 条——回炉重写。
