# 观演出行与花费规划设计方案 v3（重写版）

> 本文取代 `docs/design/event-trip-planning.md` 的 Phase 1–3 施工设计。旧文保留作历史记录，不再作为实现依据。
>
> - 版本：v3（2026-11-04）
> - 目标问题：「帮我规划去薛之谦上海站的路线」
> - 必须回答的三件事：**坐什么交通工具、路上要多久、总共花多少钱**
> - 核心判断：现有代码是“路线查询骨架”，不是“出行规划”。骨架可留三成，数据契约、时间模型、费用模型和编排要重写。

---

## 0. 结论先行

### 0.1 现有代码能做什么、不能做什么

| 能力 | 现状 | 结论 |
|---|---|---|
| 给定两个具体地点，查市内公交/驾车耗时 | 可运行，有 6 个纯算术单测 | 可用，但只覆盖“单段” |
| 给定演出开场时间，倒推最晚出门时间 | 只支持用户已提供场馆、开场时间、城际到达时间 | 半成品，前置条件用户通常给不出 |
| 用户说「薛之谦上海站」，自动查场次→定位场馆→组路线→算总价 | 跑不通 | **P0 缺口** |
| 回答“总花销是多少” | 没有任何金额字段 | **P0 缺口** |
| 跨城（南京→上海）公共交通 | `plan_event_trip` 直接抛“仅支持同城”；`plan_trip` 依赖高德跨城公交，结果不稳定 | **P0 缺口** |
| 返程、末班车、散场 | 未实现 | P1 缺口 |

一句话：**能回答“从无锡东站到无锡大剧院开车 42 分钟”，回答不了“我去看薛之谦上海站，怎么走、多久、多少钱”。**

### 0.2 根因不是 bug，是契约缺失

现有 `TripPlan` / `TripLeg` / `TripRoutePlan` 三个模型里没有“地点解析结果”“方案时间轴”“费用项”“可行性”“数据来源”。模型拿到的只有“一段耗时 + 一串假设”，于是“总花销”只能靠模型编，而项目自己的红线又明确禁止编造。**这是结构性矛盾，改几行修不好。**

### 0.3 v3 的一句话方案

把一次观演出行拆成四个可验证对象：**事件锚点（EventAnchor）→ 地点（Place）→ 交通段（Segment）→ 费用项（CostItem）**；由 Java 纯函数完成时间轴、可行性、费用汇总和排序；模型只负责解释、取舍和追问，不碰任何数字。

---

## 1. 需求重述与验收口径

### 1.1 用户原话拆解

「帮我规划去薛之谦上海站的路线」隐含六个槽位和三个交付物。

| 槽位 | 用户这句话里有没有 | 系统应如何处理 |
|---|---|---|
| 艺人 | 有：薛之谦 | 作为事件查询的 `artist` |
| 城市/巡演站点 | 有：上海站（=上海场） | 归一化为 `city=上海`，不能当成上海火车站 |
| 具体场次/日期 | 没有 | 查该城市近期场次；多场则列出让用户选 |
| 场馆 | 没有 | 从事件来源补；补不到再问用户 |
| 开场时间 | 没有 | 从事件来源补；补不到再问，**不能拿日期当时间** |
| 出发地 | 没有 | 优先用户画像/长期记忆；没有则只问这一个问题 |

三个交付物：

1. **交通工具**：高铁/动车、飞机、长途大巴、自驾、市内地铁/公交/打车/步行，以及它们的组合。
2. **用时**：门到门时间轴，而不是单段耗时；要区分“路上时间”和“建议提前到场后的总时间”。
3. **总花销**：门票 + 城际 + 市内 + 自驾油费/过路费/停车 + 返程，按人均和同行合计拆开；未知项必须显式标“待核实”。

### 1.2 合格回答长什么样

下面只是结构示例，数值必须来自工具，不能照抄：

> 可以。推荐 **高铁 + 地铁**：南京南 → 上海虹桥约 1 小时 20 分，虹桥 → 场馆约 55 分钟，门到门约 **3 小时 35 分**；建议 16:25 从家出发，18:50 前到场，19:30 开场。
>
> 人均约 **¥275**（往返高铁 + 市内地铁，**不含门票**；门票价格待核实）。如果两个人同行，交通合计约 **¥550**。
>
> 备选自驾：约 3 小时 40 分，过路费 + 油费 + 停车约 **¥330/车**，两人分摊约 **¥165/人**，不含门票。
>
> 待确认：门票实付价格、场馆与开场时间来源、返程末班车是否赶得上。

### 1.3 可验收标准

| 编号 | 标准 | 验证方式 |
|---|---|---|
| A1 | 只给“艺人 + 城市”也能启动，缺出发地时只问必要问题 | 端到端用例 |
| A2 | 每个方案必须含 `modes`、时间轴、`travelMinutes`、`cost`、`feasibility`、`confidence` | JSON 契约测试 |
| A3 | 费用必须逐项分解；未知项状态为 `UNKNOWN`，**不得写成 0** | 单测 + 评审 |
| A4 | 不得编造车次号、航班号、票价、场馆、开场时间 | 红线断言 |
| A5 | “上海站”必须按语境消歧；作为地点时不得原样当火车站 | 单测 |
| A6 | 返程要么给出，要么明确写“未计算/末班车未核实”，不能沉默 | 端到端用例 |
| A7 | 任一 provider 失败时，能返回部分结果并说明降级，不整体崩 | 故障注入测试 |

### 1.4 本期不做

订票、锁座、库存、实时票价、航班时刻、多日旅游、景点推荐、酒店预订。住宿只允许在“返程不可行”时作为提示项，不进入总花销。

---

## 2. 现有实现评审

### 2.1 做得对、应当保留的部分

1. **分层意识**：`TripTimeCalculator` 是纯函数，`RouteProvider` 是可注入接口，单测不依赖网络。这个缝要保留。
2. **不编造车次的红线**：`plan_trip` / `plan_event_trip` 的 description 反复强调不编造车次、余票、航班，这是对的。
3. **工程规范**：`@Conditional(AmapApiKeyConfiguredCondition.class)` 条件装配、`McpToolAnnotations.READ_ONLY`、`McpToolResults.success/error` 统一封装，都可复用。
4. **边界测试**：`TripTimeCalculatorTest` 覆盖了跨天、`buffer=0`、provider 异常、buffer 大于耗时等 6 个用例，报告显示 6/6 通过。
5. **description 写作规范**：`ArtistEventSearchMcpExecutor` 的七要素写法值得沿用。

### 2.2 P0 问题（直接阻断需求）

#### P0-1 事件锚点缺失，闭环断裂

`ArtistEventSearchMcpExecutor.EventItem` 只有 `artistName / title / stage / tier / city / eventDate / saleStartTime / ...`，**没有场馆、没有开场时间、没有票价**（`ArtistEventSearchMcpExecutor.java:485-490`）。

但 `plan_event_trip` 把 `venue` 和 `eventStartTime` 设为 required（`TripPlanMcpExecutor.java:64-65`），description 还要求“必须先调用 search_artist_events 核实演出”（`:54`）。两个工具的能力对不上：

- 事件查询给不出场馆和开场时间；
- 路线工具又必须拿到场馆和开场时间才能算；
- 结果：模型只能回头问用户，用户一句“帮我规划去薛之谦上海站”得到的是反问，而不是方案。

旧设计文档第 66 行写“演出查询工具提供场馆名、演出时间——整个倒推的锚点”，与实现不符。这是文档与代码的实质性漂移，不是措辞问题。

#### P0-2 没有费用模型，“总花销”无数据可依

- `TripPlan` 只有 `venue / eventStartTime / arriveVenueBy / localLeg / intercityLeg / latestDeparture / assumptions / confidence`（`model/TripPlan.java:23-25`），**没有任何金额字段**。
- `TripLeg` 只有 `from / to / mode / durationMinutes / distanceMeters / source`（`model/TripLeg.java`），**没有票价**。
- `plan_trip` 的 `TripRoutePlan.Option.reportedFare` 是一个 `String`（`model/TripRoutePlan.java:31`），仅公共交通可能返回，自驾永远为 `null`（`AmapRouteClient.java:255-256`），没有币种、没有金额类型、没有来源状态。
- 门票价格完全没有来源。

因此“总花销”只能由模型猜。这与项目“不得编造”的红线直接冲突。**费用模型不是增强项，是 P0。**

#### P0-3 城际段实际不存在

- `TripTimeCalculator` 把 `intercityLeg` 永远写成 `null`（`TripTimeCalculator.java:81`）。
- 它的 `latestDeparture` 只减了市内段耗时（`:59`），没有减城际段；旧设计文档第 288 行承诺的“城际 135 分钟 → 16:03”从未实现。
- `AmapRouteClient.route()` 对 `transit` 明确拒绝跨城：“公共交通路线仅支持同城查询”（`AmapRouteClient.java:140-142`）。
- 也就是说，一个南京用户选择 `mode=transit` 调 `plan_event_trip`，**必然失败**；选择 `driving` 才能出结果。

`TripPlan.intercityLeg` 字段永远为 `null`，会被模型误读为“不需要城际段”，而不是“没算城际段”。这是一个语义陷阱。

#### P0-4 “上海站”歧义没有处理

“薛之谦上海站”里的“上海站”是巡演站点（上海场），不是铁路上海站。当前没有任何归一化或消歧逻辑：

- `search_artist_events` 只把“上海”拼进检索词，`EventItem.city` 也只会是城市名；
- 如果把“上海站”当地点传给地理编码，高德很可能把它解析成铁路上海站；
- 模型没有结构化信号判断该用哪个语义。

结果轻则答非所问，重则把用户导到上海火车站。必须显式设计。

#### P0-5 时间模型不是“时刻表可行”，只是“耗时相减”

- `route()`（观演倒推实际走的就是它）的注释明确说公共交通查询是 time-agnostic 的（`AmapRouteClient.java:124`），`departAt` 对 transit 完全不参与查询（`:137-145`），因此耗时不对应任何真实班次。
- `plan()` 虽然把 date/time 传给了高德，但 `parseTransitOption` 只取 `transits[0]`（`:150`），最终又只按耗时保留 3 条（`:232-233`），没有“最快/最省/少换乘”的区分。
- 没有候车时间、没有换乘步行、没有进站安检、没有首末班车判断。
- 用户提供的城际到达时间晚于 `latestDeparture` 时，只在 `assumptions` 里加一句“无法保证准时”（`TripTimeCalculator.java:70-72`），仍然返回一个自相矛盾的 `latestDeparture`，没有 `feasible=false`。

### 2.3 P1 问题（影响质量与可维护性）

| 问题 | 证据 | 影响 |
|---|---|---|
| `confidence` 只有 MEDIUM/LOW，HIGH 从未出现 | `TripTimeCalculator.java:79-80` | 置信度不表达数据完整性，只表达 mode，失去意义 |
| 只有 3 条方案，仅按耗时排序 | `AmapRouteClient.java:232-233` | 无法回答“最省钱的怎么走”“少换乘怎么走” |
| 每次调用重复地理编码，无缓存 | `AmapRouteClient.java:125-126` | 延迟叠加、配额浪费、结果可能前后不一致 |
| 超时过紧：connect 1s、请求 2s | `AmapRouteClient.java:53, 342` | 高德稍慢就整体失败；复合调用失败率被放大 |
| 无重试、无熔断、无限流 | 全文 | 瞬时故障直接变成用户可见失败 |
| 警告被塞进 assumptions | `AmapRouteClient.java:100-114` | “高德公交查询暂不可用”听起来像正常假设，模型会原样转述成业务结论 |
| 参数缺失时报“时间格式不合法” | `TripPlanMcpExecutor.java:106-110` | 用户/模型无法定位到底缺哪个字段 |
| `intercityArrivalTime` 与 `intercityArrivalPlace` 的 XOR 校验可读性差 | `TripTimeCalculator.java:44-46` | 维护风险，错误信息也不精确 |
| description 500+ 字，用自然语言补结构化缺失 | `TripPlanMcpExecutor.java:54` | 上下文膨胀、指令脆弱、模型仍可能漏转述 |
| `plan_trip` 与 `plan_event_trip` 职责重叠 | 两个 Executor | 路由靠模型猜，误触发/漏触发 |
| 同一概念两套类型：`long` vs `int` 分钟数、`String` vs enum 置信度 | `TripRoutePlan` / `TripLeg` / `TripPlan` | 契约不稳定 |

### 2.4 P2 问题（工程卫生）

- 测试只有 6 个纯算术用例；`AmapRouteClient` 的 JSON 解析、费用、跨城失败、事件缺字段都没有测试。
- 没有 fixture/契约测试，所有 provider 行为只能靠线上试。
- 前端没有行程卡片；`search_artist_events` 已有 `StarlineEventCards.tsx` 可参考，但 trip 结果还是纯文本。
- 旧设计文档第 3.7 节的“两段”验收与实现不一致；第 4.2 节住宿价格边界写得对，但没有设计替代数据源。

### 2.5 总评

| 维度 | 评价 |
|---|---|
| 工程分层 | B+：纯函数 + provider 接口 + 条件装配，方向对 |
| 产品闭环 | D：事件、路线、费用三块没接上 |
| 数据契约 | D：缺 Place、Segment、CostItem、Feasibility |
| 可测试性 | C：算得清的测了，查得准的没测 |
| 可回答“总花销” | F：没有金额字段 |

**结论：不要在这个模型上继续加字段。先冻结 v3 契约，按契约重写；`TripTimeCalculator` 的测试思路和 `RouteProvider` 的注入缝可以保留。**

---

## 3. v3 设计原则

1. **事实、计算、表达三层**：provider 只给事实；Java 只做确定性计算；模型只做解释和取舍。
2. **Unknown 是一等公民**：每个金额、每个时刻都有 `status`。没有来源就是 `UNKNOWN`，不能变 0，不能省略。
3. **事件是锚点**：观演出行必须先有 `EventAnchor`；没有场馆/开场时间就不进入路线计算，先澄清。
4. **门到门时间轴**：用户要的是“几点出门、几点到、来不来得及”，不是“一段 42 分钟”。
5. **费用可分解、可追溯、可区间**：一个总价必须能展开到项，每项带来源和状态。
6. **先可行性，再排序**：赶不上的方案不配叫“推荐”；预算超了要明说。
7. **一次问清，最多两问**：能从事件、画像、记忆补的，不问用户。
8. **工具契约稳定，模型不心算**：所有加总、比较、倒推都在 Java 里完成。

---

## 4. 领域模型（建议直接按此建包）

包路径：`mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/trip/`

### 4.1 基础类型

```java
public record GeoPoint(double longitude, double latitude) {}

public record Place(
        String name,          // 展示名，如“上海虹桥站”
        String address,       // 标准化地址
        String city,          // 城市名
        String adcode,        // 行政区划码，便于跨城判断
        String level,         // 高德 level：门牌号/兴趣点/区县/市...
        GeoPoint point,
        String source,        // amap / event / user / memory
        double confidence     // 0~1，仅表示地点解析可信度
) {}

public record Money(long amountMinor, String currency) {} // 分为单位，避免浮点
```

### 4.2 事件锚点

```java
public record PriceRange(
        Long minMinor,
        Long maxMinor,
        String currency,
        CostStatus status,    // QUOTED / REFERENCE / UNKNOWN
        String source         // 票务页 URL 或“用户提供”
) {}

public record EventAnchor(
        String artist,
        String tour,          // 巡演名，可为 null
        String city,
        Place venue,          // 必须可定位；否则不进入路线计算
        LocalDateTime showStart,
        LocalDateTime showEnd,// 可为 null；未知时返程标 UNKNOWN
        PriceRange ticketPrice,
        List<Evidence> evidence
) {}
```

### 4.3 交通段

```java
public record Segment(
        String mode,              // rail / flight / coach / driving / metro / bus / taxi / walk
        Place from,
        Place to,
        LocalDateTime departAt,   // 班次时刻；没有时刻表时为估算出发时间
        LocalDateTime arriveAt,   // 同上
        int durationMinutes,
        Integer distanceMeters,
        String serviceNo,         // 车次/航班号；provider 没给就是 null，禁止补
        String operator,          // 铁路局/航司/公交公司，可为 null
        CostItem fare,            // 单段费用，可为 UNKNOWN
        String source,            // amap / rail / user / estimated
        boolean scheduled         // 是否来自真实时刻表
) {}
```

### 4.4 费用

```java
public enum CostStatus { QUOTED, REFERENCE, ESTIMATED, UNKNOWN }

public enum CostScope { PER_PERSON, PER_PARTY, PER_VEHICLE }

public record CostItem(
        String category,      // ticket / rail / flight / coach / metro / taxi / fuel / toll / parking / other
        String label,         // 展示名，如“南京南→上海虹桥 二等座”
        Long minMinor,
        Long maxMinor,
        String currency,
        CostStatus status,
        CostScope scope,
        String source,
        String note           // 如“参考票价，不代表余票”
) {}

public record CostSummary(
        List<CostItem> items,
        long knownSubtotalMinor,     // 已确认项的小计，不含未知项
        Long minTotalMinor,          // 仅已知项的区间下界
        Long maxTotalMinor,          // 仅已知项的区间上界
        List<String> unknownCategories,
        String currency,
        int partySize
) {}
```

**硬规则**：

- `status=UNKNOWN` 时 `minMinor` / `maxMinor` 必须为 `null`，不得为 0。
- `knownSubtotalMinor` 只累加非 UNKNOWN 项；有 `unknownCategories` 时，展示文案必须是“已知部分 ¥X，另有 Y 项待核实”，不能说“总计 ¥X”。
- 区间费用用 `min`/`max`；单点费用两者相等。

### 4.5 方案与计划

```java
public enum Feasibility { FEASIBLE, TIGHT, INFEASIBLE, UNKNOWN }
public enum Confidence { HIGH, MEDIUM, LOW }
public enum PlanStatus { OK, NEEDS_CLARIFICATION, PARTIAL, FAILED }

public record JourneyOption(
        String id,
        String label,                 // “高铁+地铁”“自驾”
        List<String> modes,
        List<Segment> segments,       // 按时间排序
        int travelMinutes,            // 家门→场馆门口
        int totalMinutes,             // 家门→开场（含到场缓冲）
        LocalDateTime departOriginAt,
        LocalDateTime arriveVenueAt,
        LocalDateTime arriveVenueBy,  // showStart - venueBuffer
        Feasibility feasibility,
        int slackMinutes,             // arriveVenueBy - arriveVenueAt
        CostSummary cost,
        Confidence confidence,
        List<String> assumptions,
        List<String> warnings,
        List<Evidence> evidence
) {}

public record JourneyPlan(
        String schemaVersion,          // "trip-plan.v3"
        PlanStatus status,
        JourneyRequest request,
        EventAnchor event,             // 可为 null（未解析到时）
        List<JourneyOption> options,
        String recommendedOptionId,
        String recommendReason,        // FASTEST / CHEAPEST / FEWEST_TRANSFERS / WITHIN_BUDGET
        List<Clarification> questions, // status=NEEDS_CLARIFICATION 时非空
        List<String> warnings,
        Instant generatedAt
) {}
```

### 4.6 澄清与证据

```java
public record Clarification(String field, String question, List<String> options, boolean required) {}

public record Evidence(String provider, String url, String fetchedAt, String note) {}
```

---

## 5. 数据源与能力矩阵

| 数据 | 首选来源 | 能拿到 | 拿不到 / 风险 | 输出状态 |
|---|---|---|---|---|
| 地点解析 | 高德 `/geocode/geo` + `/place/text` | 坐标、层级、城市、adcode | 城市名只到“市”级；同名 POI 可能多个 | `Place.confidence` |
| 市内公交/地铁 | 高德 `/direction/transit/integrated` | 方案、耗时、步行、线路、参考费用 | 不代表余票；换乘等待可能简化 | `REFERENCE` |
| 跨城铁路 | 高德 transit + `cityd` | 部分车次、时刻、耗时、参考费用 | 覆盖不稳定；无余票；票价可能不含 | `REFERENCE` / `UNKNOWN` |
| 精确车次/票价 | 12306 无稳定官方开放 API；第三方需合规授权 | — | 本期不假设有；没有就标未知 | `UNKNOWN` |
| 自驾 | 高德 `/direction/driving` | 耗时、距离、收费路段、`tolls` | 未来路况会变 | `REFERENCE` |
| 机票/航班 | 本期无 | — | 不做，明确告知 | `UNKNOWN` |
| 场馆/开场时间 | 票务/官方页面（白名单） | 有则结构化 | 抽取可能失败；不得猜 | `QUOTED` / `UNKNOWN` |
| 门票价格 | 票务页面区间或用户实付 | 票档区间、币种 | 服务费、售罄、动态价 | `REFERENCE` / `QUOTED` / `UNKNOWN` |
| 末班车 | 高德线路首末班（部分线路）或用户提供 | 首末班时间 | 数据不一定全 | `UNKNOWN` 兜底 |
| 油费 | 公式：距离/100 × 百公里油耗 × 油价 | 估算值 | 车型、油价、路况 | `ESTIMATED` |
| 停车费 | 配置或用户输入 | 估算值 | 场馆周边差异大 | `ESTIMATED` / `UNKNOWN` |

**红线**：任何没有 `source` 的数字不得进入 `CostItem` 或 `Segment.serviceNo`。没有数据就写 `UNKNOWN`，并在 `warnings` 里说清“哪一项没查到”。

---

## 6. 工具设计

### 6.1 工具清单

| 工具 | 职责 | 何时调用 | 处置 |
|---|---|---|---|
| `search_artist_events` | 查演出；扩展场馆/开场/票价字段 | 涉及具体演出时 | 保留并扩展 |
| `plan_journey` | 点对点、多方式、可倒推、带费用 | 普通问路；也被复合工具复用 | 新写，替代 `plan_trip` |
| `plan_event_journey` | 面向“艺人+城市”的复合规划 | 用户说“去薛之谦上海站” | 新写，替代 `plan_event_trip` |
| `revise_journey` | 改方式/预算/时间后局部重算 | 用户追问“打车呢”“预算 500” | 后期 |

**不要让模型自己串四个工具。** 观演场景由 `plan_event_journey` 一次返回“事件 + 去程 + 返程 + 费用 + 待确认”，模型只做表达。

### 6.2 `search_artist_events` 扩展

`EventItem` 增加以下字段，全部可空：

```java
String venueName;
String venueAddress;
LocalDateTime showStartTime;
LocalDateTime showEndTime;
PriceRange priceRange;
String venueSource;   // 票务页/官方页 URL
String timeSource;
```

规则：

1. 只有来源页面明确写出“演出时间/开场时间 + 场馆”才填；只写了日期就只填 `eventDate`。
2. 同一城市同一天有多个场馆时，不能合并成一条；合并键从 `(date, city)` 改为 `(date, city, venueName)`（当前 `ArtistEventSearchMcpExecutor.java:275-277` 会误合并）。
3. `priceRange` 只从票务页票档抽区间，标 `REFERENCE`；没有就 `UNKNOWN`。
4. 前端卡片解析器要容忍新增字段；旧字段不删。

### 6.3 `plan_journey` 契约

**入参**

| 字段 | 必填 | 说明 |
|---|---|---|
| `origin` | 是 | 城市或详细地址 |
| `destination` | 是 | 城市或详细地址 |
| `arriveBy` | 二选一 | 必须在此时间前到达（观演用） |
| `departAt` | 二选一 | 此时出发（普通问路用） |
| `partySize` | 否 | 默认 1 |
| `modes` | 否 | 默认 `["rail","transit","driving"]`；可传 `flight` 但本期返回 unknown |
| `roundTrip` | 否 | 默认 false；true 时加返程 |
| `budgetMinor` | 否 | 仅用于排序和超预算提示 |

**出参**：`JourneyPlan`。

**关键行为**

- `arriveBy` 模式下，每个方案给出 `departOriginAt` 和 `slackMinutes`。
- `departAt` 模式下，给出到达时间和总耗时。
- 同时给 `arriveBy` 和 `departAt` 时，报参数冲突，不猜。
- 地点只到城市级时，`Place.level=市`，在 `assumptions` 里写“按城市定位点规划，不是门到门”，并降低 `confidence`。

### 6.4 `plan_event_journey` 契约

**入参**

| 字段 | 必填 | 说明 |
|---|---|---|
| `artist` | 是 | 正式姓名，如“薛之谦” |
| `city` | 是 | 如“上海”；“上海站”归一化为“上海” |
| `origin` | 否 | 缺省时先查用户画像/记忆，仍无则返回澄清 |
| `eventDate` | 否 | 用户指定日期；没指定则列该城市场次 |
| `venue` | 否 | 用户指定场馆；没指定则从事件来源补 |
| `showStartTime` | 否 | 用户指定开场；没指定则从事件来源补 |
| `partySize` | 否 | 默认 1 |
| `roundTrip` | 否 | 默认：跨城 true，同城 false；返回结果里说明 |
| `budgetMinor` | 否 | 可选 |

**出参**：`JourneyPlan`；若缺关键槽位，`status=NEEDS_CLARIFICATION`，`questions` 最多 2 条。

**内部流程**

```
1. 归一化 artist/city，识别“上海站”语义
2. search_artist_events(artist, city, freshness)
3. 0 场 -> FAILED + 建议官方渠道，不猜
4. 多场 -> 列最多 3 场，NEEDS_CLARIFICATION（问日期）
5. 单场 -> 取 venue/showStart；缺失则：
   a. 用 place/text 以“艺人+城市+场馆”搜 POI
   b. 仍缺 -> NEEDS_CLARIFICATION（问场馆/开场）
6. origin 缺失 -> 查画像/记忆；仍缺 -> NEEDS_CLARIFICATION（问出发地）
7. 生成去程候选（见 §7）
8. roundTrip 且 showEnd 可得 -> 生成返程候选；showEnd 不可得 -> 返程标 UNKNOWN
9. 汇总费用、排序、给推荐
10. 返回 OK / PARTIAL，模型组织语言
```

**为什么用复合工具**：用户问的是一件事，不是四个 API。复合工具能在服务端做缓存、并行、降级和一致性校验；让模型串工具会把失败率、延迟和幻觉概率都放大。

### 6.5 工具边界

- `plan_journey` 不查演出；`plan_event_journey` 查演出并复用前者。
- `plan_event_journey` 不订票、不锁座、不承诺余票。
- 所有工具只读，`McpToolAnnotations.READ_ONLY`。
- 错误分三类：`NEEDS_CLARIFICATION`（缺槽位）、`PARTIAL`（部分 provider 失败）、`FAILED`（无可用路线）。只有最后一类返回 `isError=true`。

---

## 7. 核心算法

### 7.1 槽位与澄清策略

| 槽位 | 优先级 | 缺失时 | 默认 |
|---|---|---|---|
| `artist` | 1 | 无法启动；问“哪位艺人” | 无 |
| `city` | 1 | 无法启动；问“哪个城市” | 无 |
| `eventDate` | 2 | 列该城市场次（最多 3 个）让用户选 | 无 |
| `venue` | 2 | 事件来源/POI 搜索；仍缺则问 | 无 |
| `showStartTime` | 2 | 问；**不得用日期代替** | 无 |
| `origin` | 1 | 画像/记忆；仍缺则问 | 无 |
| `partySize` | 3 | 不问，默认 1，回答里说明“按 1 人计” | 1 |
| `roundTrip` | 3 | 跨城默认 true 并说明；同城默认 false | 见左 |
| `budgetMinor` | 4 | 不问；有则用于提示 | 无 |

**问法约束**：

- 一轮最多 2 个问题，按优先级取。
- 问题必须带选项或示例，不能开放式。
- 能从工具补的，绝不问用户。
- 用户说“上海站”但没说是火车站时，按巡演站点处理，并在回答里写“上海场”；只有出现“从上海站出发/到上海站”这类交通语境才按火车站处理。

**“上海站”消歧规则**：

```text
如果 token 紧跟在“艺人 + 城市”之后，且句子里没有“从/到/在 + 站”的交通语境
  -> 巡演站点（上海场）
如果句子里有“从上海站出发/到上海站/在上海站换乘”
  -> 铁路上海站
两者都可能
  -> 问一句：“你说的上海站是上海这场演出，还是上海火车站？”
```

### 7.2 候选生成

**跨城**

1. 铁路：用高德 transit + `city`/`cityd` 查询；解析**全部** `transits`，按 `serviceNo` 去重，按到达时间排序，保留最多 3 条。
2. 自驾：1 条，含 `tolls`。
3. 飞机/大巴：本期不生成；若用户明确问，返回 `UNKNOWN` + 说明“未接入航班时刻”。

**同城**

1. 地铁/公交：高德 transit，最多 2 条（最快、少换乘）。
2. 打车：按距离和城市计价参数估算，标 `ESTIMATED`。
3. 自驾：1 条。
4. 步行：距离 ≤ 3km 时给 1 条。

**组合**

- 城际到站 → 市内段：优先同站换乘；跨站时加“站间转移”段。
- 组合上限：3 条城际 × 2 条市内 = 6，剪枝到 3 条展示。
- 去重键：`(城际 serviceNo, 市内 mode, 换乘站)`。

### 7.3 时间轴

正向（`departAt`）：

```text
departOriginAt
  + accessMinutes           // 家→车站/机场
  + stationBufferMinutes    // 铁路 30，机场 90，大巴 20
  + waitMinutes             // 班次发车 - 到达车站（有时刻才计算）
  + intercityMinutes
  + egressMinutes           // 车站→场馆（含出站、换乘）
  = arriveVenueAt
```

反向（`arriveBy`）：

```text
arriveVenueBy = showStart - venueBufferMinutes(默认 30)
arriveVenueAt = arriveVenueBy
departOriginAt = arriveVenueAt - travelMinutes
travelMinutes = access + stationBuffer + wait + intercity + egress
```

**两个时间要分开报**：

- `travelMinutes`：家门→场馆门口，回答“路上要多久”。
- `totalMinutes`：家门→开场，含到场缓冲和候车，回答“几点出门”。

**跨天**：所有时间用 `LocalDateTime` 加减，允许落到前一天；输出时带日期，不能只给 `HH:mm`。

**没有时刻表时**：`waitMinutes=0`，`scheduled=false`，`confidence=LOW`，并在 `assumptions` 写“未按班次时刻筛选，候车时间未计入”。

### 7.4 可行性

```text
slackMinutes = arriveVenueBy - arriveVenueAt

FEASIBLE   : slack >= 15
TIGHT      : 0 <= slack < 15
INFEASIBLE : slack < 0，或当天出发时间已过，或末班车赶不上
UNKNOWN    : 缺时刻表/缺 showEnd，无法判断
```

返程：

```text
leaveVenueAt = showEnd + crowdBufferMinutes(默认 20)
returnArriveStationBy = leaveVenueAt + egressMinutes
lastServiceDeparture = provider 返回的末班时间（没有则 UNKNOWN）
feasible = returnArriveStationBy <= lastServiceDeparture
```

- 赶不上末班车：`returnFeasibility=INFEASIBLE`，给三种替代：打车/网约车、住一晚、次日返程；**不要**给一个不存在的“加开列车”。
- `showEnd` 未知：`returnFeasibility=UNKNOWN`，只给“末班车大致时段”，并明确“散场时间未知”。

### 7.5 费用模型

| 类别 | 计算 | 默认状态 | 备注 |
|---|---|---|---|
| `ticket` | 事件 `priceRange` 或用户实付 | `REFERENCE` / `QUOTED` / `UNKNOWN` | 不含服务费要说明 |
| `rail` | provider 票价；无则 `UNKNOWN` | `REFERENCE` | 不按里程猜票价 |
| `flight` | 本期不做 | `UNKNOWN` | 明确说明 |
| `coach` | 本期不做 | `UNKNOWN` | 可后续接 |
| `metro` / `bus` | 高德 `cost` | `REFERENCE` | 可能是起步价/单一票价 |
| `taxi` | 起步价 + 里程×单价 + 时长费 | `ESTIMATED` | 参数按城市配置 |
| `fuel` | `distance/100 × 油耗L/100km × 油价` | `ESTIMATED` | 参数可配，标注公式 |
| `toll` | 高德 `tolls` | `REFERENCE` | 没有则 `UNKNOWN` |
| `parking` | 配置/用户输入 | `ESTIMATED` / `UNKNOWN` | 场馆周边差异大 |

**汇总规则**：

```text
knownSubtotal = Σ item.min（status != UNKNOWN）
minTotal      = Σ item.min（status != UNKNOWN）
maxTotal      = Σ item.max（status != UNKNOWN）
unknownCategories = [item.category where status == UNKNOWN]
```

- 有未知项时，标题必须是“已知部分”，不能写“总计”。
- 人均 = 所有 `PER_PERSON` 项 + `PER_PARTY`/`partySize` + `PER_VEHICLE`/`partySize`（自驾按同车人数分摊，并在文案说明）。
- 往返：去程 + 返程分别列项；返程票价可能不同，不自动复用去程票价。
- 金额用分存储，展示四舍五入到元；区间展示为“约 ¥275–¥320”。

### 7.6 排序与推荐

规则式，不做加权分数（不可解释）：

```text
1. 过滤 INFEASIBLE 到列表末尾，但仍展示并标红
2. 有 budget：先选 withinBudget 且 feasible 的方案
3. 否则：feasible 中选 travelMinutes 最短
4. 时间接近（差值 <= 10 分钟）：选换乘次数少的
5. 仍并列：选费用已知项多的（unknown 少的）
6. 推荐理由只允许这几个枚举：FASTEST / CHEAPEST / FEWEST_TRANSFERS / WITHIN_BUDGET
```

未知费用不参与“最省”比较；如果用户明确要“最省”，把未知项多的方案标“费用待核实，可能更省”。

---

## 8. 输出契约

### 8.1 JSON 示例（结构示例，数值非真实场次）

```json
{
  "schemaVersion": "trip-plan.v3",
  "status": "OK",
  "request": {
    "artist": "薛之谦",
    "city": "上海",
    "origin": "南京市鼓楼区",
    "partySize": 2,
    "roundTrip": true
  },
  "event": {
    "artist": "薛之谦",
    "tour": "示例巡演",
    "city": "上海",
    "venue": {
      "name": "上海示例体育馆",
      "address": "上海市示例区示例路 1 号",
      "city": "上海",
      "level": "兴趣点",
      "source": "ticketing:example",
      "confidence": 0.9
    },
    "showStart": "2026-11-21T19:30:00",
    "showEnd": null,
    "ticketPrice": {
      "minMinor": null,
      "maxMinor": null,
      "currency": "CNY",
      "status": "UNKNOWN",
      "source": null
    }
  },
  "options": [
    {
      "id": "opt-rail-metro",
      "label": "高铁 + 地铁",
      "modes": ["rail", "metro", "walk"],
      "travelMinutes": 215,
      "totalMinutes": 245,
      "departOriginAt": "2026-11-21T15:25:00",
      "arriveVenueAt": "2026-11-21T18:50:00",
      "arriveVenueBy": "2026-11-21T19:00:00",
      "feasibility": "FEASIBLE",
      "slackMinutes": 10,
      "cost": {
        "currency": "CNY",
        "partySize": 2,
        "knownSubtotalMinor": 27500,
        "minTotalMinor": 27500,
        "maxTotalMinor": 27500,
        "unknownCategories": ["ticket"],
        "items": [
          {
            "category": "rail",
            "label": "南京南→上海虹桥 二等座（参考）",
            "minMinor": 13500,
            "maxMinor": 13500,
            "currency": "CNY",
            "status": "REFERENCE",
            "scope": "PER_PERSON",
            "source": "amap",
            "note": "参考票价，不代表余票"
          },
          {
            "category": "metro",
            "label": "虹桥→场馆 地铁",
            "minMinor": 500,
            "maxMinor": 500,
            "currency": "CNY",
            "status": "REFERENCE",
            "scope": "PER_PERSON",
            "source": "amap",
            "note": null
          },
          {
            "category": "rail",
            "label": "返程 上海虹桥→南京南 二等座（参考）",
            "minMinor": 13500,
            "maxMinor": 13500,
            "currency": "CNY",
            "status": "REFERENCE",
            "scope": "PER_PERSON",
            "source": "amap",
            "note": "返程班次未核实"
          },
          {
            "category": "ticket",
            "label": "演出门票",
            "minMinor": null,
            "maxMinor": null,
            "currency": "CNY",
            "status": "UNKNOWN",
            "scope": "PER_PERSON",
            "source": null,
            "note": "票务页未提供可核实价格"
          }
        ]
      },
      "confidence": "MEDIUM",
      "assumptions": [
        "耗时来自高德路径规划，未含实时拥堵",
        "未含安检、取票、找座时间",
        "返程按去程票价参考，班次与末班车未核实"
      ],
      "warnings": [],
      "evidence": []
    }
  ],
  "recommendedOptionId": "opt-rail-metro",
  "recommendReason": "FASTEST",
  "questions": [],
  "warnings": [
    "门票价格未获取；总花销只包含已知交通部分"
  ],
  "generatedAt": "2026-11-04T13:30:00Z"
}
```

### 8.2 模型回答模板

固定五段，顺序不能变：

1. **结论**：能不能去、推荐哪种、路上多久、人均多少（说清含不含门票）。
2. **方案对比**：2–3 条，列方式、门到门时间、人均/合计、是否赶得上。
3. **时间轴**：几点出门、几点到站、几点到场馆、几点开场。
4. **花费**：逐项列；未知项写“待核实”，不写 0。
5. **风险与待确认**：票务、末班车、场馆/开场时间来源、天气/拥堵（如未接入就不提）。

**禁止**：

- 编造车次号、航班号、发车时间、票价、场馆、开场时间。
- 把 `REFERENCE` 说成“实价”，把 `ESTIMATED` 说成“确定”。
- 把“演出日期”当“开场时间”。
- 把“上海站”当“上海火车站”。
- 在 `feasibility=INFEASIBLE` 时仍说“来得及”。

### 8.3 前端卡片

参考 `frontend/src/components/agent/StarlineEventCards.tsx` 与 `AgentTurn.tsx:502` 的识别方式，新增 `StarlineJourneyCards.tsx`：

- 顶部：事件名、场馆、开场时间、数据来源徽标。
- 方案 tab：推荐 / 最快 / 最省 / 少换乘。
- 时间轴：每段起止时间、方式、耗时、换乘；等待时间单独显示。
- 费用表：项目、金额或“待核实”、状态徽标（已报价/参考/估算/未知）。
- 可行性徽标：来得及 / 偏紧 / 赶不上 / 无法判断。
- 未知字段显示“待核实”，**不显示 0、不显示占位价**。

---

## 9. 可靠性与性能

| 项 | 设计 |
|---|---|
| 超时 | connect 1s，单次 read 3s，单工具 8s，复合工具 12s |
| 重试 | 仅幂等 GET；网络/5xx/限流重试 1 次，退避 200ms；参数错误不重试 |
| 缓存 | geocode 24h；transit/driving 10min（key 含起终点、mode、时间取整到 5 分钟）；事件 5min |
| 限流 | 按 `AMAP_API_KEY` 与用户维度；并发上限可配 |
| 熔断 | 连续 5 次失败，熔断 30s，半开探测 |
| 并行 | 去程城际与市内可并行；多方案 provider 调用并行 |
| 降级 | 单个 provider 失败返回 `PARTIAL` + `warnings`，不整体失败 |
| 观测 | 每次 provider 调用记录 provider、latency、status、infocode、cacheHit；工具记录方案数、unknown 数、降级原因 |
| 隐私 | 地址在日志中脱敏，只保留城市/区县 |

**错误码建议**（放在 `warnings[].code`，不要再往 `assumptions` 里塞）：

```text
AMAP_TIMEOUT / AMAP_QUOTA / AMAP_INVALID_KEY / AMAP_NO_ROUTE
GEOCODE_BROAD / GEOCODE_AMBIGUOUS
EVENT_NOT_FOUND / EVENT_VENUE_MISSING / EVENT_TIME_MISSING
RAIL_SCHEDULE_UNKNOWN / LAST_SERVICE_UNKNOWN
PRICE_UNKNOWN
```

`assumptions` 只放“业务假设”，`warnings` 只放“数据缺口/降级”，两者不能混。

---

## 10. 测试与验收

### 10.1 单元测试（不依赖网络）

| 对象 | 用例 |
|---|---|
| `JourneyTimeCalculator` | 正向/反向、跨天、`buffer=0`、buffer 大于总耗时、无时刻表、多段 |
| `CostCalculator` | 人均/同行/按车分摊、区间、未知项不为 0、往返、不同币种拒绝 |
| `FeasibilityEvaluator` | FEASIBLE/TIGHT/INFEASIBLE/UNKNOWN 边界（14/15/-1 分钟） |
| `StopWordParser` | “薛之谦上海站” → 上海场；“从上海站出发” → 火车站；“梅奔” → 候选 |
| `ConfidenceScorer` | 事件/地点/路线/费用四类缺失的组合 |

### 10.2 Fixture 契约测试

在 `mcp-server/src/test/resources/amap/` 放脱敏 JSON：

- `geocode-city.json` / `geocode-poi.json` / `geocode-broad.json`
- `transit-local.json` / `transit-cross-city.json` / `transit-empty.json`
- `driving.json` / `driving-tolls.json`

断言：

- 解析出的 `Segment` 数量、`serviceNo`、`durationMinutes`、`fare`、`tolls`；
- 缺失字段为 `null`，不是 0；
- `transit-empty` 不抛“成功但空”，而是 `NO_TRANSIT` warning。

### 10.3 端到端黄金集

建议放 `resources/regression/trip-planning/cases.yaml`，至少 10 条：

| 编号 | 用户问法 | 期望 |
|---|---|---|
| 1 | 帮我规划去薛之谦上海站的路线 | 若缺出发地：只问出发地/场次；不编场馆 |
| 2 | 我从南京去上海看薛之谦，2 个人，当天回 | 去程+返程、费用分解、门票 UNKNOWN |
| 3 | 我在上海，怎么去薛之谦上海站 | 同城方案，不生成城际 |
| 4 | 薛之谦上海站几号开唱 | 只查事件，不生成路线 |
| 5 | 上海站到梅奔怎么走 | “上海站”按火车站，场馆按候选确认 |
| 6 | 从上海站出发去场馆 | 同上，不能按巡演站点 |
| 7 | 看完演出怎么回南京 | 返程 + 末班车；赶不上给替代 |
| 8 | 预算 1000，两个人够吗 | 超预算提示；未知项不硬算 |
| 9 | 高德查不到公交怎么办 | PARTIAL + 自驾，明确说明 |
| 10 | 帮我规划去一个不存在的演出 | FAILED + 官方渠道建议，不编造 |

### 10.4 红线断言

1. `serviceNo` 只能来自 fixture/provider；测试中禁止出现硬编码车次。
2. 每个非空金额必须有 `source` 且 `status != UNKNOWN`。
3. `UNKNOWN` 在 UI/文本中不得渲染为 `0` 或 `¥0`。
4. `INFEASIBLE` 必须在模型回复中出现“赶不上/来不及”，不能只藏在 JSON 里。
5. “上海站”作为巡演站点时，回复中不得出现“上海火车站”。

### 10.5 验收门槛

- 黄金集通过率 ≥ 90%；红线断言 100%。
- 复合工具 P95 < 6s（缓存命中 < 2s）。
- 费用可解释率 100%：每个数字能指到 `source` 或公式。
- 未知项必须出现在回复的“待确认”段，不能静默。

---

## 11. 实施计划

| 阶段 | 内容 | 产出 | 估算 |
|---|---|---|---|
| M0 | 冻结 v3 契约；写 fixture；先写失败测试 | records + 测试骨架 | 1 天 |
| M1 | `PlaceResolver` + Amap 三个 client + 缓存/超时/重试 | 事实层可用 | 2–3 天 |
| M2 | `JourneyTimeCalculator` + `CostCalculator` + 可行性 | 纯函数全绿 | 2–3 天 |
| M3 | `plan_journey` + 事件字段扩展 | 普通问路闭环 | 2 天 |
| M4 | `plan_event_journey` + 澄清 + 返程 | “薛之谦上海站”闭环 | 3 天 |
| M5 | 前端行程卡片 + 模型/skill 手册 | 可演示 | 2 天 |
| M6 | 观测 + 黄金集 + 压测 | 可上线 | 2 天 |

**顺序不能反**：先事实层和纯函数，再工具，再前端。不要先写一个“什么都能答”的复合工具，再倒推数据。

---

## 12. 与现有代码的迁移映射

| 现有文件 | 处置 | 说明 |
|---|---|---|
| `TripTimeCalculator.java` | 重写为 `JourneyTimeCalculator` + `CostCalculator` | 保留“纯函数 + 可注入”思路和边界用例 |
| `model/TripPlan.java` | 删除/替换为 `JourneyPlan` | 加事件、方案、费用、澄清 |
| `model/TripLeg.java` | 替换为 `Segment` | 加时刻、班次、票价、是否时刻表 |
| `model/TripRoutePlan.java` | 替换为 `JourneyOption` | `reportedFare` 字符串改为 `CostItem` |
| `TripPlanMcpExecutor.java` | 重写为 `EventJourneyMcpExecutor`（`plan_event_journey`） | 承接“艺人+城市”入口 |
| `TripRouteMcpExecutor.java` | 重写为 `JourneyMcpExecutor`（`plan_journey`） | 普通问路 + 内部复用 |
| `RouteProvider.java` | 保留接口思想，换成 `RouteGateway` | 返回 `Segment` 列表，不返回单段耗时 |
| `AmapRouteClient.java` | 拆成 `AmapPlaceClient` / `AmapTransitClient` / `AmapDrivingClient` + `AmapHttpClient` | 共享超时、重试、缓存、错误码 |
| `TripPlanProperties.java` | 扩展 | 到场缓冲、散场缓冲、进站缓冲、油耗、油价、停车、城市计价 |
| `ArtistEventSearchMcpExecutor.java` | 扩展 `EventItem` | 场馆/开场/票价可空；合并键加场馆 |
| `StarlineEventCards.tsx` | 参考 | 新增 `StarlineJourneyCards.tsx` |
| `docs/design/event-trip-planning.md` | 标记为历史 | 本文取代其 Phase 1–3 |

---

## 13. 需要你拍板的开放问题

1. **城际票价/时刻数据源**：只做高德参考，还是接 12306/合规第三方？这决定“总花销”是区间还是接近准数。
2. **门票价格**：从票务页抽区间，还是让用户输入实付，还是两者都做？
3. **返程默认**：跨城是否默认算返程？算的话，散场时间未知时怎么标？
4. **“待核实”是否可接受**：如果业务要求必须给一个总价，就会和“不编造”冲突；需要明确优先级。
5. **住宿/餐饮**：是否纳入“总花销”？本期建议不纳入。
6. **预算/偏好**：是否影响推荐排序？还是只做提示？

---

## 附录 A：用户问法的系统行为

### A1. 只给“艺人 + 城市”，没有出发地

```text
用户：帮我规划去薛之谦上海站的路线
系统：search_artist_events(薛之谦, 上海) -> 1 场
      缺 origin -> NEEDS_CLARIFICATION
回复：可以。上海场查到 11 月 21 日 19:30，场馆是 XXX。
      你从哪个城市/地址出发？几个人？
      （拿到出发地后我再算交通、用时和总花销。）
```

只问一个问题，不要一次列五个字段。

### A2. 事件有日期但没有场馆/开场时间

```text
系统：search_artist_events -> eventDate=2026-11-21, venue=null, showStart=null
      place/text(薛之谦 上海 场馆) -> 候选 A/B
回复：查到上海场 11 月 21 日，但场馆和开场时间还没有可靠来源。
      是 A 还是 B？开场时间你知道吗？
```

**不得**把 11 月 21 日当成 19:30，也**不得**默认场馆是“梅赛德斯-奔驰文化中心”。

### A3. 事件、场馆、开场、出发地都齐

```text
plan_event_journey -> OK
回复：结论 + 方案对比 + 时间轴 + 花费 + 风险（按 §8.2）
```

---

## 附录 B：旧设计文档需要修正的断言

| 位置 | 旧断言 | 实际情况 | 处置 |
|---|---|---|---|
| `event-trip-planning.md:66` | 演出查询提供场馆名、演出时间 | `EventItem` 无场馆/开场字段 | 扩展事件工具；在新文档中更正 |
| `event-trip-planning.md:288` | 城际 135 分钟 → latestDeparture=16:03 | 实现只减市内段，得 18:18 | 删除该验收，改为返程/城际新模型 |
| `event-trip-planning.md:3.7` | “两段”用例 | 未实现城际段 | 由 v3 §10 替换 |
| `event-trip-planning.md:4.2` | 高德不返回房价 | 正确，但没有替代方案 | v3 明确“住宿不进总花销” |
| `event-trip-planning.md:5.3` | 硬约束违反时返回失败 | 未实现 | v3 用 `Feasibility` + `warnings` 结构化 |

---

## 附录 C：给模型/skill 的新手册要点（≤10 条）

1. 观演出行先查事件；没有场馆/开场时间先问，不许猜。
2. “上海站”默认是巡演站点；出现“从/到上海站”才是火车站。
3. 用户没给出发地，只问出发地；不要一次问五个字段。
4. 调用 `plan_event_journey` 一次拿全方案，不要自己串四个工具。
5. 回答固定五段：结论、方案对比、时间轴、花费、风险。
6. 费用逐项说；未知项说“待核实”，不说 0，不说“大概”。
7. 不编造车次、航班、票价、场馆、开场时间。
8. `REFERENCE` 说“参考价”，`ESTIMATED` 说“估算”，`QUOTED` 才说“实价”。
9. `INFEASIBLE` 必须明说“赶不上/来不及”，并给替代。
10. 不承诺余票、不代订票；订票走独立票务流程。
