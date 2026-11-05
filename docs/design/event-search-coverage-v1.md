# 星序演出查询覆盖与解析重设计（event-search v1）

> 面向 Codex 的施工单。本文只解决 `search_artist_events` 的“查不全”：用户问“薛之谦最近有什么演出”，结果只返回杭州，而大麦/其他来源还有上海、无锡等场次。
>
> - 版本：v1（2026-11-04）
> - 目标工具：`search_artist_events`（发现模式）
> - 不改：`plan_journey` / `plan_event_journey` 的路线、时间轴、费用模型
> - 核心原则：**覆盖是管道，不是一条 query；任何丢弃必须可见，任何“全量”必须可证伪。**

---

## 0. 结论

现状不是“搜索没查到”，而是**一条 query 的 top-N 被当成了全量**，再被四层收窄：

1. 一个通用 query、只取第一页，不翻页；
2. 一个 URL 只产出一个 `EventItem`，多城市页面只取第一个日期/城市；
3. 按日期排序后硬截断 10 条，`notes` 不报告截断；
4. 城市用固定列表 `findFirst`，列表里没有无锡等城市；日期只认 4 位年份。

重设计把链路拆成：

```text
EventSearchService
  ├─ EventQueryPlanner      生成多条 query（发现/平台/城市）
  ├─ SearchProvider         支持分页、count、site 过滤
  ├─ SourceResolver         白名单 + 跳转解析 + 过滤原因
  ├─ EventPageExtractor     一页产出多个 EventDraft
  ├─ EventMerger            去重、合并证据、冲突标记
  └─ CoverageReporter       统计每一层，生成 notes/completeness
```

三个红线：

- **不静默丢弃**：截断、过滤、解析失败都要有计数和原因。
- **不声称全量**：只承诺“本次检索到的”，并标明覆盖边界。
- **不编造场次/日期/场馆**：未知就是 `null` + 追问，不用模型记忆补。

---

## 1. 复现证据（先看这个）

### 1.1 现有测试全绿，但没有覆盖真实搜索

```text
.\mvnw.cmd -o -pl mcp-server -Dtest=ArtistEventJourneyContractTest,JourneyPlanningTest test
Tests run: 14, Failures: 0, Errors: 0
```

但：

- `ArtistEventJourneyContractTest` 用 mock `WebSearchClient`，只断言“同一天不同场馆不合并”（`mcp-server/src/test/java/.../ArtistEventJourneyContractTest.java:27-41`）。
- `JourneyPlanningTest` 开头写明“不是在线模型生成或真实票务覆盖率”（`.../trip/JourneyPlanningTest.java:32`）。

所以测试通过不能证明覆盖没问题；需要一个“12 条多城市结果”的回归用例。

### 1.2 用项目原类复现“只剩杭州”

用当前 `ArtistEventSearchMcpExecutor`，不传 `city`、`freshness=year`，喂入 12 条真实结构的 `RawResult`：

| 输入 | 输出 |
|---|---|
| 10 条杭州（11/21–11/30）+ 上海（12/05）+ 无锡（12/20），全部在 90 天内 | `total=10`，10 条全是杭州；`notes` 写“本次共检索 12 条，展示 10 条…**0 条未通过筛选**” |

上海和无锡被静默截断，`notes` 没有“还有 2 条未展示”。这是最直接的复现：**不需要 city 参数，也不需要 90 天窗口，只要杭州结果够 10 条，其他城市就会被挤掉。**

### 1.3 多城市页面只产出一个场次

| 输入（单个 URL 的 title+excerpt） | 输出 |
|---|---|
| “杭州站 11.21，无锡站 12.20，演出场馆：杭州奥体中心”，无年份 | `total=1`，`city=杭州`，`eventDate=null`；**无锡丢失** |
| “上海站 2026-12-05，杭州站 2026-12-20”，有年份 | `total=1`，`city=上海`；**传 `city=杭州` 时 `total=0`** |

原因：

- `toEventItem` 每个 `RawResult` 只生成一个 `EventItem`（`ArtistEventSearchMcpExecutor.java:223-257`）。
- 日期只匹配 4 位年份（`:85`、`:397-416`），没有年份就 `null`。
- 城市是固定列表 + `findFirst`（`:81-84`、`:455-456`），**列表里没有无锡**；多城市页面会被标成列表顺序里第一个出现的城市。

### 1.4 问题的代码位置

| 问题 | 证据 |
|---|---|
| 单 query | `ArtistEventSearchMcpExecutor.java:148` |
| 只取一页、count 未传给腾讯 | `YuanbaoWebSearchClient.java:83-85`、`:130` |
| 城市精确过滤 | `ArtistEventSearchMcpExecutor.java:179-182` |
| 按日期排序后截断 10 条 | `ArtistEventSearchMcpExecutor.java:191-195`、`ArtistEventSearchProperties.java:32` |
| notes 不报告截断 | `ArtistEventSearchMcpExecutor.java:269-288` |
| 一页一个事件 | `ArtistEventSearchMcpExecutor.java:223-257` |
| 日期只认 4 位年份 | `ArtistEventSearchMcpExecutor.java:85`、`:397-416` |
| 城市固定列表、缺无锡 | `ArtistEventSearchMcpExecutor.java:81-84`、`:455-456` |
| 白名单只认直接 URL | `ArtistEventSearchMcpExecutor.java:350-362` |
| `official-domains` 配置没有任何 Java 读取 | `mcp-server/src/main/resources/application.yml:9-18` |
| 发现类问题被城市槽位限制 | `EventJourneyPlanner.java:42-58` |

---

## 2. 目标、非目标与验收

### 2.1 目标

| 编号 | 目标 |
|---|---|
| G1 | 发现模式不要求 `city`；一次返回多个城市的场次 |
| G2 | 一个页面可以产出多个场次，而不是一个 URL 一个事件 |
| G3 | 截断、过滤、解析失败都有 `coverage` 元数据，`notes` 明确说明 |
| G4 | 日期支持无年份/区间；城市支持无锡等完整城市集合；未知不静默丢弃 |
| G5 | 搜索结果可分页/加载更多，不再硬性只给 10 条 |
| G6 | 建立可重复的覆盖评测：固定 fixture 能证明“多城市不会被杭州挤掉” |

### 2.2 非目标

- 不承诺覆盖大麦全部活动；只承诺“在已配置来源、query 预算和窗口内尽力检索”，并明确边界。
- 不做登录页/反爬绕过；不爬取需要登录或验证码的页面。
- 不做订票、库存、余票、锁座。
- 不改变 `plan_event_journey` 的路线/费用算法；它继续只规划用户指定的城市/场次。
- 不做全量城市爬虫；城市词典来自配置/高德行政区划，不手工枚举全国。

### 2.3 验收口径

| 编号 | 场景 | 期望 |
|---|---|---|
| A1 | 12 条结果：10 杭州 + 上海 + 无锡 | 返回 3 个城市；若因分页只返回第一页，必须 `truncated=true` 且 `notes` 写“还有 N 条” |
| A2 | 一个页面含杭州+无锡（无年份） | 产出 2 个 `EventDraft`；无锡可识别；日期 `dateUnknown=true`，不丢 |
| A3 | 一个页面含上海+杭州（有年份） | 产出 2 个 `EventDraft`；传 `city=杭州` 仍能命中杭州 |
| A4 | 无锡站 | `city=无锡` 可识别；不再依赖 `CITY_NAMES` |
| A5 | 大麦跳转链接 | 解析 302 后按最终域名命中白名单；失败有 `REDIRECT_UNRESOLVED` |
| A6 | 来源查询失败 | `completeness=PARTIAL`，其他来源结果照常返回 |
| A7 | 用户问“最近有什么演出” | `notes` 说明检索窗口、query 数、来源数、原始/过滤/展示/截断 |
| A8 | 任何响应 | 不出现“全部/完整”这类不可证声明；每个 item 有 `sourceUrl` |

---

## 3. 总体架构

```text
search_artist_events (tool)
  -> EventSearchService.search(EventSearchQuery)
       -> EventQueryPlanner.plan(query)
       -> for each planned query:
            SearchProvider.search(SearchRequest)  // page/cursor
       -> SourceResolver.resolve(rawResults)
       -> EventPageExtractor.extract(page)       // 0..N drafts
       -> EventMerger.merge(drafts)              // dedupe + evidence
       -> CoverageReporter.report(...)
  -> EventSearchResult (items + coverage + notes)
```

### 3.1 包结构建议

```text
mcp-server/src/main/java/.../executor/starline/event/
├── EventSearchService.java          编排，工具层只调用它
├── EventQueryPlanner.java           生成 query 列表与预算
├── SearchProvider.java              搜索提供方接口（分页）
├── YuanbaoSearchProvider.java       腾讯适配器（补 count/page）
├── YouComSearchProvider.java        备选（已支持 count）
├── SourceResolver.java              白名单、跳转、过滤原因
├── EventPageExtractor.java          页面 -> List<EventDraft>
├── DateExpressionParser.java        日期/区间/无年份
├── CityDictionary.java              城市词典（含无锡）
├── EventMerger.java                 去重、证据合并、冲突
├── CoverageReporter.java            coverage + notes
└── model/
    ├── EventSearchQuery.java
    ├── EventSearchResult.java
    ├── EventDraft.java
    ├── Coverage.java
    ├── FilterReason.java
    └── EventItem.java               复用现有字段，保持兼容
```

### 3.2 与 `plan_event_journey` 的边界

- `search_artist_events` 是**发现**：可以没有城市，返回多城市列表，支持分页。
- `plan_event_journey` 是**规划**：必须有目标城市；它调用 `EventSearchService` 时传 `city`，只取该城市候选；不在规划工具里做跨城市发现。
- 发现类问题（“有哪些/最近有什么”）只走 `search_artist_events`；如果模型误用 `plan_event_journey`，应在工具 description 和 skill 中明确纠正。
- `EventJourneyPlanner` 现有的 `request.city()` + 精确 city 过滤保留；它不应成为发现入口。

---

## 4. 查询规划（EventQueryPlanner）

### 4.1 为什么需要多条 query

一条 `"薛之谦 演唱会 巡演 开票 官方"` 只能拿到搜索引擎给这一条 query 的 top-N；杭州是当前热门站，容易占满。发现模式必须**多角度**检索，再由白名单/解析/合并收敛。

### 4.2 查询模板

| 类型 | 模板 | 用途 |
|---|---|---|
| 基础 | `{artist} 演唱会 巡演` | 通用发现 |
| 巡演 | `{artist} 巡演 城市 站` | 巡演汇总页 |
| 平台 | `site:damai.cn {artist} 演唱会` | 大麦 |
| 平台 | `site:maoyan.com {artist} 演唱会` | 猫眼 |
| 平台 | `site:showstart.com {artist} 演出` | 秀动 |
| 城市 | `{artist} {city} 演唱会` | 指定城市（规划/追问后） |
| 开票 | `{artist} 开票 官方` | 当前在售/开票信息 |

注意：

- `site:` 是否生效取决于搜索提供方；不生效也不报错，只是结果仍会被白名单过滤。
- 发现模式默认不用城市模板；`city` 只在用户明确指定或规划工具调用时加入。
- 模板数量不等于 query 数量：按 `artist`/`city` 去重，按预算截断。

### 4.3 预算

```text
maxQueries = 6
maxPagesPerQuery = 2
maxPageSize = 20
maxRawResults = 200
timeoutPerQuery = 4s
```

- 先跑基础/巡演/平台 query，再跑城市 query。
- 达到 `maxRawResults` 或时间预算即停止；停止原因写入 coverage（`QUERY_BUDGET_EXHAUSTED`）。
- 并行度 2–4；同一 `(query,page)` 5 分钟内缓存。

### 4.4 去重

- 规范化 URL：去 tracking 参数、统一 host、保留活动 id（如 `id=`）。
- 同一 URL 只解析一次；同一 `(artist,date,city,venue)` 合并。
- 记录每个 item 的来源 query，便于解释“为什么这条被搜到”。

---

## 5. 搜索提供方（SearchProvider）

### 5.1 接口

```java
public interface SearchProvider {
    SearchPage search(SearchRequest request);
}

public record SearchRequest(
        String query,
        String site,          // 可为 null；不支持时由 provider 忽略
        int page,             // 0-based
        int pageSize,
        String freshness      // provider 侧发布时间过滤；事件日期过滤在本地
) {}

public record SearchPage(
        List<RawResult> results,
        boolean hasMore,
        String nextCursor
) {}

public record RawResult(
        String title,
        String url,
        String excerpt,
        Instant publishTime,
        String siteName,
        int rank,
        String query
) {}
```

`RawResult` 增加 `rank`/`query`，便于排序和解释；旧字段保持兼容。

### 5.2 腾讯元宝适配器

当前 `YuanbaoWebSearchClient` 只发 `{"Query": query}`（`mcp-server/src/main/java/.../YuanbaoWebSearchClient.java:83-85`），`count` 只在解析时截断（`:130`）。

要求：

1. 查腾讯 SearchPro 文档，确认是否支持 `Page`/`Cnt`/`Limit`/`Offset`；支持就传，不支持就在适配器注释和指标里明确“单页上限”。
2. 如果接口支持 `site`/`Freshness`，透传；不支持就由 query 模板和本地过滤兜底。
3. 保留“不把 `count` 当请求参数”的现状也可以，但必须在 coverage 里标 `providerPageLimit`，不能假装拿到了 20 条。
4. 如果无法分页，发现模式改用支持 `count` 的 You.com 作为主/备（`YouComWebSearchClient.java:81-86` 已经传 `count`），或接第二个 provider 做并集。

### 5.3 备选/并集

- 允许配置多个 provider；发现模式可并行请求，按 URL 去重后合并。
- provider 失败不整体失败：记录 `SourceFailure(provider, reason)`，`completeness=PARTIAL`。
- 不要用模型记忆补搜索结果；provider 没有就是没有。

### 5.4 超时/重试/限流

| 项 | 值 |
|---|---|
| connect | 2s |
| per query | 4s |
| retry | 1 次，仅网络/5xx/限流 |
| 并发 | 2–4 |
| 缓存 | 5 分钟，key=`(provider,query,page)` |
| 限流 | 按 provider/API key；超限返回 `PROVIDER_RATE_LIMITED` |

---

## 6. 来源解析（SourceResolver）

### 6.1 白名单

- 唯一事实来源：数据库 `t_starline_platform_source` 的 `ACTIVE` + `public_accessible=true`（`StarlinePlatformSourceService.java:94-100`）。
- `application.yml` 的 `official-domains` 当前没有任何 Java 读取（`application.yml:9-18`）；**删除它，或把它作为 DB 不可用时的 fallback 并显式记录**，不能让它看起来生效。
- 首期种子包含 `damai.cn`（`resources/database/init_data_pg.sql:381-384`），但生产以管理后台状态为准。

### 6.2 URL 规范化

1. 解析 302/301 到最终 URL（最多 3 跳，禁止跳转到非 http/https）。
2. 去 tracking 参数（`spm`、`from`、`sqm`、`utm_*` 等），保留活动 id。
3. host 小写、去 `www.`、IDN 转 ASCII；允许子域名匹配（`m.damai.cn`、`detail.damai.cn`）。
4. 失败：`REDIRECT_UNRESOLVED` / `INVALID_URL`，计入 coverage。

### 6.3 过滤原因枚举

```java
public enum FilterReason {
    NOT_WHITELISTED,
    REDIRECT_UNRESOLVED,
    NOT_ABOUT_ARTIST,
    NO_EVENT_MARKER,
    DATE_OUT_OF_WINDOW,
    DATE_UNPARSEABLE,
    CITY_MISMATCH,
    DUPLICATE,
    STALE,
    TRUNCATED,
    PARSER_REJECTED
}
```

每个原因计数写入 `Coverage.filtered`；调试日志记录 URL/原因（地址脱敏）。

### 6.4 艺人相关性

- 保留 `isAboutArtist` 的精确包含，但**不要因为“周边/壁纸”等噪音词直接丢弃**：有 `EVENT_MARKERS` 就保留，并标记 `noise=true`，让排序降权。
- 标题/摘要都没有艺人名时，允许正文/URL slug 命中；仍无则 `NOT_ABOUT_ARTIST`。

---

## 7. 页面抽取（EventPageExtractor）

### 7.1 输入

`RawResult(title, excerpt, url, publishTime, siteName)`。当前只用了 title+excerpt；如果未来抓正文，extractor 接口不变，输入换成 `PageContent`。

### 7.2 一页多事件

```java
public List<EventDraft> extract(PageContent page, String artist) {
    // 1. 按行/句切块
    // 2. 在每个块里找 date + city + venue 的组合
    // 3. 同一块多个日期 -> 每个日期一个 draft
    // 4. 同一页多个城市 -> 每个城市一个 draft
    // 5. 找不到组合 -> 返回单 draft（dateUnknown）或 PARSER_REJECTED
}
```

`EventDraft`：

```java
public record EventDraft(
        String artist,
        String city,
        LocalDate date,
        boolean dateUnknown,
        String venue,
        String address,
        LocalDateTime showStart,
        LocalDateTime showEnd,
        PriceRange price,
        String sourceUrl,
        String sourceName,
        String title,
        String stage,
        List<String> evidence
) {}
```

### 7.3 日期解析

支持：

| 形式 | 例子 |
|---|---|
| 完整日期 | `2026-11-21`、`2026.11.21`、`2026/11/21`、`2026年11月21日` |
| 无年份 | `11.21`、`11月21日`、`11/21` |
| 区间 | `11.21-11.22`、`11月21日-22日`、`2026-11-21 至 11-22` |
| 带星期 | `2026-11-21 周六` |
| 时间 | `19:30`、`19:30-22:00` |

规则：

1. 完整年份直接用。
2. 无年份：用 `publishTime` 的年份或窗口起始年份推断；如果推断结果落在窗口外，尝试上一年/下一年；仍不唯一则 `dateUnknown=true` + `dateCandidates`。
3. 区间：产出开始/结束两个日期，或一个 `dateRange`；不要只取第一个。
4. **日期解析失败不丢弃**：保留 `dateUnknown=true`，在 notes 里提示“日期待核实”，排序放后面但不截断。
5. 不要把开票日期当演出日期：保留现有 sale-context 逻辑，但只用于“排除开票日”，不用于“确定演出日”。

### 7.4 城市解析

- 用 `CityDictionary` 替代 `CITY_NAMES` 固定列表。
- 词典来源：高德行政区划（adcode）+ 手工补充（无锡、宁波、佛山、东莞、珠海、温州、绍兴、嘉兴、常州、南通等）。
- 匹配：
  1. 找文本中所有城市名及位置；
  2. 优先取距离“站/场馆/演出时间/演出地点”最近的一个；
  3. 如果页面是巡演汇总（多个城市 + 多个日期），为每个城市/日期组合生成 draft，而不是只取一个；
  4. 城市不在词典但能通过“XX站/XX奥体中心”识别，标记 `cityUnknown=true`，不要丢。
- 不要用“列表顺序第一个”决定城市；这是当前多城市页错标的根因。

### 7.5 场馆/开场/票价

- 只从明确标签抽取：`演出场馆:`、`演出地点:`、`场馆地址:`、`演出时间:`、`开场时间:`、`票价:`。
- 没有标签就是 `null`；`plan_event_journey` 会追问，不能用“艺人+城市”猜场馆。
- 票价保留 `PriceRange`（`REFERENCE`/`UNKNOWN`），不要用“起”造单点总价。

### 7.6 合并与冲突

- key：`(artist, date, city, venue)`；`dateUnknown` 时用 `(artist, city, venue, sourceUrl)` 避免误合并。
- 票务来源优先于 GOV；同一 key 的多来源合并 `sourceEvidence`。
- 时间/状态冲突：按现有规则置 `conflict=true`，不要把冲突值当确定值。
- 合并后仍保留 `TRUNCATED` 之前的全量计数。

---

## 8. 结果与覆盖元数据（Coverage）

### 8.1 `EventSearchResult`

```java
public record EventSearchResult(
        String artist,
        String city,
        SearchWindow window,
        List<EventItem> items,
        Coverage coverage,
        List<String> notes,
        String nextCursor
) {}
```

工具层把 `items` 放在 `items` 字段，`coverage`/`notes` 同级返回；旧前端只读 `items` 也能工作。

### 8.2 `Coverage`

```java
public record Coverage(
        List<String> queries,
        int rawResults,
        int afterSource,
        int afterRelevance,
        int afterDate,
        int merged,
        int returned,
        int truncated,
        Map<FilterReason, Integer> filtered,
        List<SourceCoverage> sources,
        Completeness completeness,
        String window
) {}

public record SourceCoverage(String platform, String domain, String status,
                             int raw, int accepted, String failure) {}

public enum Completeness { BEST_EFFORT, PARTIAL, UNKNOWN }
```

规则：

- `BEST_EFFORT`：所有 query/来源成功、无截断、无未知日期。
- `PARTIAL`：有来源/query 失败、有截断、有未知日期、有 `dateUnknown`。
- `UNKNOWN`：没有可用来源或没有执行检索。
- **永远不出现 `COMPLETE`**；产品文案也不要说“全部”。

### 8.3 截断与分页

- `maxItems` 默认 50（发现模式），`pageSize` 默认 20。
- 没有 `cursor`：返回第一页，`truncated = merged > returned`，`notes` 写“还有 N 条未展示”。
- 有 `cursor`：返回下一页；cursor 用 base64 的 `(queryOffset, lastKey)`，不暴露内部 id。
- **不要先按日期排序再截断导致后面的城市消失**：排序只影响展示顺序，分页覆盖全量；如果必须限制，先按“城市多样性”抽样再按日期排序，且 `truncated` 必须为 true。
- 允许前端“加载更多”；没有前端时，至少返回 `nextCursor`。

### 8.4 notes 生成

`CoverageReporter` 生成固定格式：

```text
本次检索 6 个查询、3 个来源，原始 42 条；
白名单过滤 12 条，艺人相关性过滤 5 条，日期过滤 3 条，合并后 22 条；
当前展示 20 条，还有 2 条未展示。
来源 maoyan.com 查询失败，覆盖可能不完整。
2 条日期待核实，已保留但未按时间排序。
```

要求：

- 有截断必写“还有 N 条”。
- 有来源失败必写“覆盖可能不完整”。
- 有 `dateUnknown` 必写“日期待核实”。
- 不写“共查到全部 N 场”。

### 8.5 观测指标

```text
event_search_queries_total{provider,status}
event_search_raw_results_total{provider}
event_search_filtered_total{reason}
event_search_truncated_total
event_search_source_failures_total{platform}
event_search_coverage_completeness_total{level}
event_search_latency_ms{stage}
```

日志只记 query hash/artist、provider、count、reason；不记完整地址。

---

## 9. 工具契约

### 9.1 `search_artist_events` 入参

| 字段 | 必填 | 说明 |
|---|---|---|
| `artist` | 是 | 正式姓名 |
| `city` | 否 | 只有用户明确指定城市/规划工具调用时传；发现模式不要自动补 |
| `window` | 否 | `recent`（未来 90 天）、`year`（未来 365 天，默认）、`all`（未来 730 天） |
| `includeEnded` | 否 | 默认 false；true 时包含近 30 天已结束（标 ENDED） |
| `pageSize` | 否 | 默认 20，上限 50 |
| `cursor` | 否 | 分页游标 |

**默认 `window=year`**，不是 `month`。理由：发现模式的“最近”不等于“90 天”；90 天会制造假阴性。需要“最近”时由模型传 `recent`，并在回答里说明窗口。

### 9.2 出参示例

```json
{
  "artist": "薛之谦",
  "city": null,
  "window": "year",
  "total": 3,
  "items": [
    {
      "artistName": "薛之谦",
      "title": "薛之谦「万兽之王」巡演 杭州站",
      "city": "杭州",
      "eventDate": "2026-11-21",
      "stage": "ON_SALE",
      "sourceName": "大麦网",
      "sourceUrl": "https://www.damai.cn/...",
      "venueName": "杭州奥体中心",
      "showStartTime": "2026-11-21T19:30:00"
    }
  ],
  "coverage": {
    "queries": ["薛之谦 演唱会 巡演", "site:damai.cn 薛之谦 演唱会", "..."],
    "rawResults": 42,
    "afterSource": 30,
    "afterRelevance": 25,
    "afterDate": 22,
    "merged": 3,
    "returned": 3,
    "truncated": 0,
    "filtered": {"NOT_WHITELISTED": 12, "DATE_OUT_OF_WINDOW": 3},
    "sources": [{"platform": "大麦网", "domain": "damai.cn", "status": "OK", "raw": 20, "accepted": 18}],
    "completeness": "BEST_EFFORT",
    "window": "year"
  },
  "notes": ["本次检索 6 个查询、3 个来源，原始 42 条；..."],
  "nextCursor": null
}
```

### 9.3 工具 description 修改要点

- 明确“发现模式可传 `city` 为空，返回多城市；不要因为一个问题里出现过城市就自动补 `city`”。
- 明确“默认 `year`；用户说‘最近’才传 `recent`”。
- 明确“`coverage.truncated=true` 时必须告诉用户还有未展示结果；`completeness=PARTIAL` 时必须说覆盖不完整”。
- 保留“不编造场次/日期/时间/场馆/票价”的红线。
- 前端卡片只渲染 `items`；`coverage` 用于“覆盖说明”徽标/折叠区。

### 9.4 `plan_event_journey` 边界

- 入参 `city` 必填；它调用 `EventSearchService.search(artist, city, window=recent|year)`，只取该城市候选。
- 不在规划工具里做跨城市发现；用户问“有哪些”时，模型应调用 `search_artist_events`。
- `EventJourneyPlanner` 继续过滤 `eventDate != null` 的近期场次；但事件发现层不能把 null 日期的场次丢掉，否则规划工具永远看不到它们。规划层可以要求日期，发现层不能。
- 如果用户在 `plan_event_journey` 里给的城市没有场次，返回 `EVENT_NOT_FOUND` + “可改用 search_artist_events 查看其他城市”，不要自己扩展城市。

---

## 10. 前端

- `StarlineEventCards` 增加：
  - 城市/日期分组；多城市时显示“杭州 3 场 / 上海 2 场 / 无锡 1 场”。
  - “加载更多”按 `nextCursor`。
  - 覆盖徽标：`BEST_EFFORT` / `PARTIAL`，点击展开 `coverage` 和 `notes`。
  - `dateUnknown` 显示“日期待核实”，不隐藏。
- 不要在前端再次按 10 条截断；前端只渲染工具返回的分页。
- 旧历史 tool result 没有 `coverage` 时，沿用原始文本展示。

---

## 11. 可靠性与性能

| 项 | 设计 |
|---|---|
| 超时 | provider per query 4s；整个发现 8s；工具 12s |
| 重试 | 幂等 GET/查询 1 次；5xx/限流重试；参数错误不重试 |
| 缓存 | `(provider, query, page)` 5 分钟；事件合并结果 5 分钟 |
| 并发 | query 并行 2–4；同一 artist 的并发请求合并（singleflight） |
| 限流 | 按 provider/API key；超限返回 PARTIAL + `PROVIDER_RATE_LIMITED` |
| 熔断 | 连续 5 次失败，30s 熔断；半开探测 |
| 成本 | `maxQueries`/`maxRawResults` 预算；覆盖优先，不做无限翻页 |

---

## 12. 测试与验收

### 12.1 单元测试

| 对象 | 用例 |
|---|---|
| `DateExpressionParser` | 完整日期、无年份、区间、带星期、跨年、非法日期 |
| `CityDictionary` | 无锡、宁波、佛山、多城市页、城市名与站名共存 |
| `EventPageExtractor` | 一页一事件、一页多城市、一页多日期、无年份、无场馆 |
| `EventMerger` | 同 key 合并、不同场馆不合并、冲突标记、dateUnknown 不误合并 |
| `CoverageReporter` | 计数守恒、截断文案、来源失败文案、dateUnknown 文案 |
| `EventQueryPlanner` | 预算、去重、city 模式 |

### 12.2 Fixture

放 `mcp-server/src/test/resources/event-search/`：

```text
multi-city-hangzhou-wuxi.txt
multi-city-shanghai-hangzhou.txt
twelve-results.json          # 10 杭州 + 上海 + 无锡
year-less-dates.txt
redirect-chain.json
disabled-source.json
no-venue.txt
```

### 12.3 黄金用例

| 编号 | 输入 | 期望 |
|---|---|---|
| E1 | 12 条：10 杭州 + 上海 + 无锡，无 city，window=year | 返回 3 城市；或 `truncated=true` + notes“还有 N 条” |
| E2 | 杭州+无锡同页，无年份 | 2 个 draft；无锡识别；日期 `dateUnknown` |
| E3 | 上海+杭州同页，有年份，city=杭州 | 命中杭州，不返回 0 |
| E4 | 无锡单页 | `city=无锡`，不再依赖 `CITY_NAMES` |
| E5 | 大麦 302 | 最终域名命中白名单；失败有原因 |
| E6 | 一个来源超时 | `PARTIAL`，其他来源结果保留 |
| E7 | 用户问“最近有什么演出” | notes 包含窗口、query 数、截断/覆盖说明 |

### 12.4 红线断言

1. `truncated=true` 时，`notes` 必须包含“还有”与数量。
2. `completeness != BEST_EFFORT` 时，`notes` 必须说明原因。
3. `items.size() == returned`；`merged >= returned`；`raw >= afterSource >= afterRelevance >= afterDate`。
4. 每个 item 必须有 `sourceUrl`；没有来源的 item 不允许出现。
5. 没有 `eventDate` 的 item 必须 `dateUnknown=true`，不能悄悄丢。
6. 不允许出现“全部/完整/所有场次”的确定性表述。

### 12.5 性能

- 发现模式 P95 < 6s（缓存命中 < 2s）。
- 6 个 query × 2 页 × 20 条的上限下，内存和日志可控。
- 覆盖评测：每周一次，固定 3 个艺人，人工对比大麦公开在售页与工具返回；只记录差异，不把实时结果写死到 CI。

---

## 13. 实施计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（1 天） | 加 `Coverage`/`truncated`/`notes`；不改搜索行为；用 12 条 fixture 写回归 | 现有行为会返回 10 杭州，但测试能断言 `truncated=true`、notes 有“还有 2 条” |
| P1（2–3 天） | `CityDictionary`、`DateExpressionParser`、`EventPageExtractor` 多事件 | E2/E3/E4 通过；无锡/无年份页面不再丢 |
| P2（3–4 天） | `EventQueryPlanner`、`SearchProvider` 分页、`SourceResolver` 跳转 | E1/E5/E6 通过；多 query 能拿到多城市 |
| P3（2 天） | 前端分页/覆盖徽标、工具 description、skill | 用户能看到“杭州/上海/无锡”或明确的截断说明 |
| P4（持续） | 覆盖评测、备选 provider、必要时接大麦公开页/API | 每周对比，不再出现“只返回杭州” |

**P0 必须单独上线**：它不改变结果，只让“被截断”可见，先止血“用户以为只有杭州”。

---

## 14. 迁移映射

| 现有 | 处置 |
|---|---|
| `ArtistEventSearchMcpExecutor` | 变薄，只做参数校验/结果序列化；逻辑迁到 `EventSearchService` |
| `WebSearchClient` | 扩展为 `SearchProvider`（分页）；保留旧方法做适配 |
| `YuanbaoWebSearchClient` | 补 `page`/`count`/`site`（按 API 实际支持）；不支持则标 `providerPageLimit` |
| `YouComWebSearchClient` | 作为备选 provider；已传 `count`（`:81-86`） |
| `ArtistEventSearchProperties` | `maxItems` 默认 50；新增 `maxQueries`/`maxPagesPerQuery`/`discoveryWindow` |
| `CITY_NAMES` | 删除，换 `CityDictionary`（高德 adcode + 补充） |
| `EVENT_DATE`/`extractEventDate` | 换 `DateExpressionParser` |
| `EventFactExtractor` | 保留标签抽取；多事件由 `EventPageExtractor` 负责 |
| `EventJourneyPlanner` | 继续按 city 规划；发现入口改回 `search_artist_events` |
| `StarlineEventCards.tsx` | 加分页/覆盖徽标；兼容旧 JSON |
| `application.yml` `official-domains` | 删除或改为 fallback；DB 白名单是唯一事实来源 |

---

## 15. 风险与开放问题

| 风险 | 处理 |
|---|---|
| 腾讯 SearchPro 是否支持分页未知 | 先做 spike；不支持就用 You.com 备选或接受单页 + 明确 coverage |
| 大麦页面 JS 渲染/反爬 | 只使用公开搜索结果/公开页面，不绕过登录；必要时走合作 API |
| 多 query 成本/延迟 | 预算 + 缓存 + 并发 2–4；先基础/平台 query，再城市 query |
| 城市词典维护 | 高德 adcode 为主，补充城市表走配置/后台，不硬编码 |
| 产品期望“全量” | 明确 best-effort 契约；UI 显示 coverage，不承诺全量 |
| 旧前端只读 `items` | 新字段 additive；旧字段保留 |

**需要产品拍板：**

1. 发现模式默认窗口：`year` 还是 `recent`？建议 `year`，回答里说明窗口。
2. `maxItems` 默认 50 是否够？前端是否做“加载更多”？
3. 是否包含已结束场次（`includeEnded` 默认 false）？
4. 大麦是“必须覆盖”还是“尽力覆盖”？这决定是否要接合作 API/第二数据源。
5. `site:` 查询是否被腾讯支持？不支持时是否接受 You.com 作为主 provider？

---

## 附录 A：复现 harness 的输入/输出

```text
输入（不传 city，window=year）：
  10 条杭州：2026-11-21 ~ 2026-11-30
  1 条上海：2026-12-05
  1 条无锡：2026-12-20

输出：
  total=10
  items=[杭州×10]
  notes=本次共检索 12 条，展示 10 条白名单渠道结果，0 条未通过白名单、相关性或日期筛选。
```

结论：`max-items=10` + 按日期升序截断，足以在**没有 city、没有 90 天窗口**的情况下复现“只剩杭州”。

第二个 harness：

```text
输入：单页 "杭州站 11.21，无锡站 12.20，演出场馆：杭州奥体中心"
输出：total=1，city=杭州，eventDate=null

输入：单页 "上海站 2026-12-05，杭州站 2026-12-20"
输出：total=1，city=上海；传 city=杭州 -> total=0
```

结论：一页多事件/多城市没有拆开，城市由固定列表顺序决定。

> 说明：harness 使用的是按真实页面结构构造的 `RawResult`，不是线上腾讯搜索的实时返回；本地没有 `YUANBAO_SEARCH_API_KEY`/运行中的 MCP 服务。解析、过滤、截断用的是项目原类，行为是确定的。

---

## 附录 B：现有代码证据索引

| 位置 | 说明 |
|---|---|
| `ArtistEventSearchMcpExecutor.java:148` | 单 query 拼接 |
| `:157-159` | fetchCount + 单次 search |
| `:179-182` | city 精确过滤 |
| `:191-195` | 日期排序 + 截断 |
| `:223-257` | 一页一个 EventItem |
| `:269-288` | notes 不含截断 |
| `:397-416` | 只认 4 位年份 |
| `:455-456` | 固定城市列表 findFirst |
| `:81-84` | CITY_NAMES 不含无锡 |
| `:350-362` | 白名单直接 URL 匹配 |
| `YuanbaoWebSearchClient.java:83-85` | 只发 Query |
| `:130` | count 仅解析截断 |
| `ArtistEventSearchProperties.java:32` | maxItems=10 |
| `application.yml:9-18` | official-domains 未被读取 |
| `EventJourneyPlanner.java:42-58` | 规划工具只查单城市 |
| `JourneyPlanningTest.java:32` | 测试不覆盖真实票务 |

---

## 附录 C：建议的 PR 拆分

1. **PR-1（P0）**：`Coverage`/`truncated`/`notes` + 12 条 fixture 回归；不改搜索。
2. **PR-2**：`CityDictionary` + `DateExpressionParser` + 单测。
3. **PR-3**：`EventPageExtractor` 多事件 + fixture。
4. **PR-4**：`EventQueryPlanner` + `SearchProvider` 分页 + `SourceResolver` 跳转。
5. **PR-5**：前端分页/覆盖徽标 + tool description/skill。
6. **PR-6**：覆盖评测脚本 + 每周报告。

每个 PR 独立可回滚；PR-1 先上线让问题可见，PR-4 再真正提升覆盖。
