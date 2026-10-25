# 演出查询 MCP 工具设计方案 v2（检索式 + 下沉判定）

> 需求：用户问「薛之谦最近有什么演出」，Agent 要能查到真实场次、城市、场馆、开票时间，并明确区分官方来源与未核实线索。
>
> 本版**推翻 v1 的「建表 + 人工录入」路线**。v1 把 60% 工作量放在数据采集上，方向错了：字节/腾讯自己也不建票务表，而是检索 + 结构化引用。v1 文件保留作历史参考，实施以本文为准。
>
> 依据：项目 MCP 实代码（`YouComSearchMcpExecutor` / `McpToolSchema` / `McpToolResults` / `McpServerConfig`）、腾讯云元宝搜索官方文档（product/1806）、火山方舟 web_search 文档。核查日期 2026-10-25。**未修改任何项目文件。**

---

## 0. 结论先行

### 0.1 三条路线，选第三条

| 路线 | 数据来源 | 优点 | 致命问题 |
|---|---|---|---|
| **A. 自建表 + 人工录入**（v1） | `t_artist_event` | 结构化、可控、审计友好 | 数据采集占 60% 工作量；录入滞后一天就等于答错；无 API 只能靠人抄 |
| **B. 纯接第三方搜索 MCP** | 元宝搜索 MCP 直连 | 零代码 | 模型拿到裸网页，**要自己判断哪个官方、哪个过期** —— 判断没下沉，幻觉风险没降低 |
| **C. 自建工具 + 外部检索 + 工具内下沉判定** ✅ | 检索 API（WSA / You.com） | 判断在可审计的代码里；无需维护数据表；工具 description 可控边界 | 受检索质量限制；需要处理噪音与实体歧义 |

**选 C 的理由**：工具的真正价值不是「能搜到网页」（那是搜索 API 的事），而是**把「这是不是官方来源 / 这条过期了没 / 这场是不是在卖」变成可直接读取的字段**。

如果只是把搜索 API 包一层原样返回，那不如在 `agent.mcp.servers` 里直接挂元宝搜索 MCP，一行配置完事。

### 0.2 检索源选型

| 能力 | 腾讯云元宝搜索（wsa） | You.com（项目现有） | 火山方舟 web_search |
|---|---|---|---|
| 原生站点过滤 | ✅ `Site` 参数，三版本全支持 | ❌ 无 | ✅ `allowed_domains` |
| 原生时效过滤 | ✅ `Freshness` d1-30/m1-12/y1-5 | ✅ `freshness` day/week/month/year | ✅ |
| 返回发布时间 | ✅ | ⚠️ 可能给 `age`，不稳定 | ✅ |
| 返回发布站点 | ✅ | ❌ 需自己从 URL 解析 | ✅ `site_name` |
| 价格 | 14 元/千次起 | 已采购 | 0.02 元/次 |
| 国内可达性 | ✅ | ⚠️ 跨境 | ✅ |

**建议**：主用**元宝搜索**（`Site` + `Freshness` + 发布时间原生给全），`youcom_search` 保留为通用兜底，两者并存不互替。

实施上建议抽一层 `WebSearchClient` 接口，两个实现可切换，避免把业务逻辑绑死在某个供应商上。

---

## 1. 用户问「薛之谦最近有什么演出」时，工具要做四件判断

这是本设计的核心。裸检索只能给出「十几条网页」，模型必须自己完成四步推理；工具要把这四步**下沉成字段**。

| # | 要判断什么 | 模型自己判断的风险 | 工具怎么下沉 |
|---|---|---|---|
| 1 | 这条是不是**薛之谦**的演出？ | 「薛之谦同款」「翻唱薛之谦」被当成本人场次 | 实体相关性校验 → 不相关直接丢弃 |
| 2 | 来源**算不算官方**？ | 把营销号当官方公告转述 | 域名白名单 → `sourceType=OFFICIAL/UNVERIFIED` |
| 3 | 这条**过期了没**？ | 拿三个月前的旧闻说「即将开票」 | 发布时间 + 阈值 → `isStale=true/false` |
| 4 | 这场**还能不能买**？ | 「有演出」说成「能买票」 | 文本抽取售票状态 → `saleStatus` |

**类比**：这就像「挂号分诊」——检索 API 是把你所有检查报告堆桌上的人，工具是那个先在报告上盖章「这项异常 / 这项正常」的护士。医生（模型）只需要读盖章，不用逐页推演。

---

## 2. 工具定义

### 2.1 基本定义

```
TOOL_ID         : search_artist_events
annotations     : McpToolAnnotations.READ_ONLY      // 只读无副作用
require_confirm : 0                                 // 无需确认
```

### 2.2 description（最关键的一行）

模型**只靠 description** 决定要不要调用。写不好会出三类事故：拿它查生日、该查不查、查到空就编。

```java
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
  + "\n\n本工具只查询，不创建提醒。用户要设提醒请用创建提醒的工具。";
```

三条边界（每条都对应真实事故）：

1. **「不适用」段**——直接对应你遇到的 bad case：问「林俊杰生日」时模型如果误调本工具，会把一堆「林俊杰 2026 巡演」网页当答案，答非所问。
2. **`total=0` 不等于没演出**——搜索没命中往往只是关键词没拼好，或该艺人确实低调。不加这句，模型会从「查不到」推成「没有」。
3. **`UNVERIFIED` 不能当提醒依据**——这是业务红线。用户拿营销号信息设了提醒，到点提醒他去抢一个不存在的票，比不提醒更糟。

### 2.3 入参 schema

```java
JsonSchema inputSchema = McpToolSchema.object()
        .required(string("artist", "艺人姓名，中文常用名，如「薛之谦」。用户说昵称或错别字时"
                + "按最接近的正式艺人名传入，如「小薛」传「薛之谦」"))
        .optional(string("city", "限定城市，如「北京」「上海」；用户没提城市就不要传"))
        .optional(string("freshness", "检索跨度：week(近7天+未来30天)、month(近30天+未来90天，"
                + "默认)、year(近90天+未来365天)")
                .options(List.of("week", "month", "year"))
                .defaultTo("month"))
        .build();
```

四个设计取舍：

- **必填只有 `artist`**。`city` 和 `freshness` 绝不设必填——用户说「薛之谦最近有什么演出」时没有城市，逼模型填会诱导它瞎猜。
- **没有 `limit`**。内部固定上限 10 条。让模型填条数，它会在「多给点」和「省 token」之间摇摆，输出不稳定。
- **没有 `onlyOfficial` 这类布尔**。① `McpToolSchema` 根本不支持 boolean；② 更本质的是——**是否只看官方不该是模型的选择题**，工具必须两类都返回、都标清楚，让模型会话说人话（「官方没查到，但有几个媒体号提到…」）。
- **`freshness` 用相对跨度而非绝对日期**。见 3.2：模型算日期不可靠，且行为不可复现。

> ⚠️ `McpToolSchema` 只支持 `string / integer / number / array`，无 boolean。这是既有代码约束，不要设计布尔参数。

---

## 3. 内部流程：五步

```java
CallToolResult handleCall(CallToolRequest request) {
    Map<String,Object> args = McpToolResults.args(request);
    String artist = StrUtil.trimToNull(MapUtil.getStr(args, "artist"));
    if (artist == null) return McpToolResults.error("请提供艺人姓名");

    // ① 拼 query —— 加"官方/开票"提升命中权威页的比例
    String query = buildQuery(artist, city);

    // ② 检索（站点白名单 + 时效，交给搜索 API 的原生参数）
    List<RawResult> raw = searchClient.search(query, freshness);

    // ③ 逐条下沉判定 + 丢弃不相关
    List<EventItem> items = raw.stream()
            .map(r -> toEventItem(r, artist, now))       // 四层判定
            .filter(Optional::isPresent).map(Optional::get)
            .sorted(Comparator.comparing(EventItem::trustRank)
                    .thenComparing(EventItem::eventDate, nullsLast(naturalOrder())))
            .limit(MAX_ITEMS)
            .toList();

    // ④ 空结果给 notes，绝不给裸 total=0
    return McpToolResults.success(toJson(artist, query, items, notes));
}
```

### 3.2 「最近」的时间语义（不要试图定义，要显式化 + 可修正）

用户说「薛之谦最近有什么演出」，这里的「最近」有三重歧义，任何单一理解都会在部分场景下答错：

| 歧义 | 具体表现 | 处理方式 |
|---|---|---|
| **方向** | 「最近有什么演出」＝即将到来；「最近那场演出」＝刚刚结束。字面相同，方向相反 | 前后都查，用 `relativePhase` 标注 |
| **窗口长度** | 一周 / 一个月 / 一季度 / 半年，无数字无法确定 | 服务端定默认值，不让模型猜 |
| **参照点** | 今天 00:00 / 用户发消息时刻 / 会话开始时刻 | 统一用 `current_date` 的结果 |

#### 默认双向窗口

```
过去 30 天  ←──  今天  ──→  未来 90 天
```

**为什么往后看 90 天**：演出有强「提前官宣」特征。官宣到开票 1–4 周，开票到演出 1–2 个月，大型巡演提前 3–6 个月。窗口只取 7 天会漏掉绝大多数「已官宣、已开票、还没到演出日」的场次——那恰恰是用户最关心的抢票窗口；取一年又会带回过期与取消信息。

**为什么往过去看 30 天**：该艺人若三个月内无安排、但上个月刚结束一场，只往后看时模型只能答「查不到」；加前 30 天，模型能说「近期没新安排，不过 X 月刚结束一场巡回」——这比「查不到」有用，也顺带避免「把已结束说成即将到来」。

#### `freshness` 参数按「跨度」而非「起点」设计

```java
.optional(string("freshness", "检索跨度：week(过去7天+未来30天)、"
        + "month(过去30天+未来90天，默认)、year(过去90天+未来365天)")
        .options(List.of("week", "month", "year"))
        .defaultTo("month"))
```

| 参数值 | 服务端展开区间 |
|---|---|
| `week` | `[today - 7d, today + 30d]` |
| `month`（默认） | `[today - 30d, today + 90d]` |
| `year` | `[today - 90d, today + 365d]` |

三个取值都**同时向前向后**，不会有「只往后看」的选项——因为「只往后看」正是最常见的漏答案原因。

#### 关键约束：绝不让模型把「最近」翻译成日期

```java
// ❌ 错误：模型自己算「最近 = 10-25 到 11-25」，再传 dateFrom/dateTo
//   跨月/跨年/闰年/大小月都可能算错；且行为不可复现，回归评测会抖

// ✅ 正确：只传相对跨度，区间由服务端展开
freshness = "month"
```

**行为不可复现比算错更麻烦**：同一问题跑两次给出两个不同时间范围，bad case 分析和回归评测都无法定位。

#### 返回里必须显式声明实际区间

```json
{
  "searchedRange": { "from": "2026-09-25", "to": "2027-01-23" },
  "items": [ { "relativePhase": "UPCOMING", ... } ]
}
```

`relativePhase` 三档：

- `UPCOMING`：`eventDate > today`，即将到来
- `ONGOING`：多日演出，今天落在演出期内
- `PAST`：`eventDate < today`，已结束
- `TBD`：只官宣了城市/大致时间，无确定日期

**为什么必须声明**：把「我按什么区间查的」摊开，用户觉得不对时能立刻说「太近了，再往后看看」，模型改 `freshness` 重查。不声明的话，用户只会觉得「这系统漏了」，却不知道是窗口问题。

**类比**：跟出租车司机说「前面路口停」，好司机会说「前面那个路口大概 500 米，行吗」——把假设摊开让你否决，而不是假装理解了你的模糊意图。

### 3.3 为什么 query 要拼「官方/开票」

裸传「薛之谦」会优先命中百科、歌单、八卦。加上领域词能把权威页往前顶：

```
无城市：薛之谦 演唱会 开票 官方
有城市：薛之谦 北京 演唱会 开票 官方
```

同时如果有 `Site` 能力，**不要**把白名单塞进 query（`site:damai.cn` 只在部分搜索引擎有效，且会让非白名单的权威媒体彻底查不到）。白名单只用于**事后打标**，不用于**事前过滤**——否则「官方没发公告但媒体已有报道」这种情况会完全查不到，用户会以为「没演出」。

---

## 4. 四层下沉判定（核心代码逻辑）

### 4.1 第一层：实体相关性 —— 丢弃噪音

这一步最容易被忽略，但**不做的话工具会变成噪音放大器**。

```java
/**
 * 结果标题/摘要里必须出现艺人名，且不能只是"同款/翻唱/模仿"关联
 */
private boolean isAboutArtist(RawResult r, String artist) {
    String text = r.title() + " " + r.excerpt();
    if (!text.contains(artist)) return false;          // 完全没提到 → 丢弃

    // 否定/无关语境：出现这些词说明只是"借用艺人名"卖东西
    List<String> noiseMarkers = List.of("同款", "翻唱", "模仿秀", "应援", "周边", "壁纸", "头像", "歌词");
    boolean onlyNoise = noiseMarkers.stream().anyMatch(text::contains)
            && !text.contains("演唱会") && !text.contains("巡演") && !text.contains("门票");
    return !onlyNoise;
}
```

**为什么要这一层**：搜「薛之谦」必然带回「薛之谦同款外套」「薛之谦歌词壁纸」。这些结果给了模型之后，模型要么当噪音（浪费 token），要么误判成演出（更糟）。

**注意**：这里只做丢弃，不做「相关性打分」。因为一旦给分数，模型就会开始按分数排序做判断，又回到「让模型推理」的老路。

### 4.2 第二层：来源可信度 —— 域名白名单

```java
private static final List<String> OFFICIAL_DOMAINS = List.of(
    "damai.cn",        // 大麦
    "maoyan.com",      // 猫眼
    "showstart.com",   // 秀动
    "piaoxingqiu.com", // 票星球
    "228.com.cn"       // 永乐
);

private String classifySource(String url) {
    String domain = extractDomain(url);   // URI.create(url).getHost()，去掉 www.
    boolean official = OFFICIAL_DOMAINS.stream()
            .anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    return official ? "OFFICIAL" : "UNVERIFIED";
}
```

**只做两档**。不要加 `MEDIA` / `FAN` / `SUSPICIOUS`——每加一档就要维护一份对应的域名清单，而媒体号域名无穷无尽（微信公众号、微博、小红书、B 站全在里面），维护不过来。**「不是官方」就是「未核实」，这个二分足够指导模型措辞。**

子域名匹配用 `endsWith("." + d)`：`detail.damai.cn` 要能命中 `damai.cn`。

### 4.3 第三层：时效 —— 用发布时间算，不用「感觉」

```java
private static final long STALE_HOURS = 24 * 7;   // 演出信息 7 天阈值

private StaleMark judgeStale(RawResult r, Instant now) {
    Instant published = r.publishTime();          // 搜索 API 原生给
    if (published == null) {
        return new StaleMark(false, "来源未提供发布时间，无法判断时效");
    }
    long hours = Duration.between(published, now).toHours();
    return hours > STALE_HOURS
            ? new StaleMark(true, "该信息发布于 " + hours / 24 + " 天前，可能已变化")
            : new StaleMark(false, null);
}
```

**阈值为什么是 7 天**：v1 里的「6 小时/24 小时」是给「已建表 + 人工核验」场景定的——那种场景下 `verified_at` 是你自己核验的时间，阈值要严格。**检索式场景的 `publish_time` 是网页发布时间，不是核验时间**，用 6 小时会把几乎所有结果都标成过期（没人天天更新网页），阈值就失去意义。

**发布时间拿不到时返回 `isStale=false` 但附 `staleReason` 说明「无法判断」**——不能因为拿不到就默认过期（会让全量结果不可用），也不能默认为新（会误导）。

### 4.4 第四层：售票状态 —— 从文本抽取，拿不到就 `UNKNOWN`

```java
private String extractSaleStatus(String text) {
    if (text.contains("售罄") || text.contains("已抢完")) return "SOLD_OUT";
    if (text.contains("售票中") || text.contains("购买") || text.contains("立即预订")) return "ON_SALE";
    if (text.contains("待开票") || text.contains("即将开票") || text.contains("预约")) return "PENDING";
    if (text.contains("已结束") || text.contains("已落幕")) return "CLOSED";
    return "UNKNOWN";     // 绝大多数情况落这里，很正常
}
```

**关键**：`UNKNOWN` 是**正常且高频**的返回值，不是异常。绝大多数网页不会明确写「售票中」。模型看到 `UNKNOWN` 就知道「不能断言能不能买」，这比给一个猜出来的状态安全得多。

**这一层只能做「防断言」，不能做「准确播报」**。如果将来需要准确的售票状态，唯一可靠路径是拿票务平台官方 API（需商务合作）——在那之前，`UNKNOWN` 就是诚实的答案。

---

## 5. 返回结构

### 5.1 正常结果

```json
{
  "artist": "薛之谦",
  "query": "薛之谦 演唱会 开票 官方",
  "searchedAt": "2026-10-25T13:05:00+08:00",
  "searchedRange": { "from": "2026-09-25", "to": "2027-01-23" },
  "total": 2,
  "items": [
    {
      "title": "薛之谦「天外来物」巡回演唱会 北京站",
      "relativePhase": "UPCOMING",
      "city": "北京",
      "venue": "国家体育场（鸟巢）",
      "eventDate": "2026-12-20",
      "saleStartTime": "2026-11-01 12:00",
      "saleStatus": "PENDING",
      "sourceType": "OFFICIAL",
      "domain": "damai.cn",
      "sourceUrl": "https://detail.damai.cn/item.htm?id=xxx",
      "publishedAt": "2026-10-24T18:00:00+08:00",
      "isStale": false,
      "staleReason": null,
      "excerpt": "演出时间 2026-12-20 19:30，11 月 1 日 12:00 开票…"
    },
    {
      "title": "网传薛之谦年底北京开唱",
      "relativePhase": "UPCOMING",
      "city": "北京",
      "venue": null,
      "eventDate": null,
      "saleStartTime": null,
      "saleStatus": "UNKNOWN",
      "sourceType": "UNVERIFIED",
      "domain": "mp.weixin.qq.com",
      "sourceUrl": "https://mp.weixin.qq.com/s/xxx",
      "publishedAt": "2026-10-10T09:00:00+08:00",
      "isStale": true,
      "staleReason": "该信息发布于 15 天前，可能已变化",
      "excerpt": "有消息称…（未获官方确认）"
    }
  ],
  "notes": [
    "共检索 8 条，其中 1 条为官方渠道、1 条为未核实线索，其余因与演出无关被过滤。",
    "检索区间：2026-09-25 至 2027-01-23。若需更远期安排，可放宽检索跨度。"
  ]
}
```

**排序规则**：`UPCOMING` 优先于 `PAST`，同类内 `OFFICIAL` 优先，再按演出日期升序。让模型先看到「还能去的、可信的」。

**当只有 `PAST` 结果时**，`notes` 要给出可用的说法：

```json
{
  "notes": [
    "检索区间内未发现未来场次，但有 1 场已于 2026-10-05 结束。",
    "可以告知用户「近期暂无新安排，上一场是 X 月 X 日在 Y 城市」，不要只说「查不到」。"
  ]
}
```

### 5.2 空结果（防编造的最后防线）

```json
{
  "artist": "周杰伦",
  "query": "周杰伦 演唱会 开票 官方",
  "total": 0,
  "items": [],
  "notes": [
    "未检索到与「周杰伦」演出直接相关的可信结果。",
    "这不代表该艺人没有演出安排：可能尚未官宣、已结束，或本次检索未覆盖。",
    "请如实告知未查到，并建议关注艺人官方微博与主办方公告，不要编造场次、城市或时间。"
  ]
}
```

**只返回 `{"total":0,"items":[]}` 是危险的**。模型拿到一个空壳 JSON，最常见的两种反应是「编一个」或「说一句很生硬的没有」——`notes` 是给它「怎么好好说」的脚本。

### 5.3 结果互相冲突

同一艺人不同来源的开票时间打架（改期、加场、假消息）时：

```json
{
  "notes": [
    "检测到 2 条来源的演出日期不一致：damai.cn 显示 12-20，mp.weixin.qq.com 显示 12-27。",
    "请并列展示两方说法并标注来源类型，不要自行选一个下结论，也不要据此创建提醒。"
  ]
}
```

---

## 6. 意图节点（不建就白做）

工具注册完，Agent 仍然看不到它。必须往 `t_intent_node` 插一条 `kind=2` 的节点，因为 `DefaultIntentClassifier.listMcpToolNodes()` 是这么筛的：

```java
.filter(IntentNode::isMCP)                                       // kind = 2
.filter(node -> node.getMcpToolId() != null && !node.getMcpToolId().isBlank())
```

```sql
INSERT INTO t_intent_node (
    id, intent_code, name, level, parent_code, description,
    mcp_tool_id, require_confirm, kind, examples, sort_order, enabled
) VALUES (
    '<雪花ID>',
    'ARTIST_EVENT_QUERY',
    '艺人演出查询',
    1,
    NULL,
    '查询艺人的演出、巡演、场次、场馆与开票时间',
    'search_artist_events',
    0,                                            -- 只读，无需用户确认
    2,                                            -- IntentKind.MCP
    '薛之谦最近有什么演出
薛之谦今年有演唱会吗
林俊杰什么时候开票
周杰伦北京站是哪天
小薛最近在哪个城市巡演
五月天演唱会门票还能买吗',
    100,
    1
);
```

`examples` 要覆盖四类问法：**标准问法**（最近有什么演出）、**时间问法**（什么时候开票）、**城市问法**（北京站）、**别名**（小薛）、**状态问法**（还能买吗）。它既是意图识别的锚点，也是模型理解工具用途的补充材料。

> ⚠️ **代码核实后的修正**：`examples` 对 **MCP 节点当前完全无效**。`DefaultIntentClassifier.classifyTargets()` 的候选过滤是
> `.filter(node -> node.isKB() || (WORKFLOW && node.isSystem()))`，MCP 节点不参与意图打分；`AgentToolCatalog` 也不读 examples。
> 填 examples 不会报错，但也不产生任何效果。
>
> **真正有效的是 `description`**：`McpToolBinding.of()` 里 `StrUtil.isNotBlank(description) ? description : definition.description()`，即意图树的描述**覆盖**服务端描述，改后台即可改模型看到的工具说明，不用发版。
>
> **两个硬约束**：
> 1. **MCP 节点必须是叶子**。`listMcpToolNodes()` 从 `leafNodes` 筛，给 MCP 节点挂 children 会让它变成非叶子，工具**静默消失**且不报错。
> 2. **一个工具只能挂一个节点**。DB 有 `CREATE UNIQUE INDEX uk_intent_node_mcp_tool ON t_intent_node (mcp_tool_id) WHERE kind = 2 AND deleted = 0`。
>
> **⚠️ 执行完 SQL 必须清 Redis 缓存**：意图树缓存 key 为 `starlineagent:intent:tree`，**TTL 7 天**（`IntentTreeCacheManager`）。只有走 `IntentTreeServiceImpl` 的增删改才会 `clearIntentTreeCache()`；**直接执行 INSERT 绕过了 Service，缓存不会清，工具最长 7 天不生效**。手工执行后必须：
>
> ```bash
> redis-cli -h 127.0.0.1 -p 6379 DEL "starlineagent:intent:tree"
> ```
>
> 清完后 `ReActAgentProvider` 会因 `catalog.fingerprint()` 变化自动重建 ReActAgent，无需重启应用。

---

## 7. 依赖关系

### 7.1 必须和 `current_date` 配合

用户说「最近」是个相对时间。模型如果不先调 `current_date`，它对「今天是 2026-10-25」的认知来自训练数据，必然错。

现有 `current_date` 工具的 description 已经写了「需要理解今天、明天、后天、下周等相对日期时先调用本工具」——本工具的 description 里可以再加一句强化：

```
涉及「最近」「今年」「下个月」等相对时间时，先调用 current_date 确认当前日期。
```

### 7.2 与 `youcom_search` 的分工

| 工具 | 场景 |
|---|---|
| `search_artist_events` | 用户问**具体艺人**的演出 → 带下沉判定 |
| `youcom_search` | 其他通用信息检索 → 原始网页列表 |

**不做自动兜底**（本工具查不到时不自动转 `youcom_search`）。理由：自动兜底会让模型拿到一份「没有下沉判定的原始结果」，然后它就开始自己判断可信度——正好回到我们要避免的路上。让本工具明确返回「未查到」，由模型决定下一步，行为更可预测。

### 7.3 与创建提醒工具的关系

**严格分离**。查询工具只查，不建提醒。用户想设提醒，走独立工具，且必须：
- 只能基于 `sourceType=OFFICIAL` 的数据
- 用户明确确认活动名、开票时间、提醒时间、邮箱

把「查」和「建」混在一个工具里，会让「用户明确确认」这个约束失效。

---

## 8. 实施顺序

**阶段 1：骨架 + 假数据（0.5 天）**
1. 建 `WebSearchClient` 接口 + `YouComSearchClient` 实现（复用现有 `doSearch` 逻辑）
2. 写 `ArtistEventSearchMcpExecutor`，判定逻辑先只做**来源白名单**一层
3. 建意图节点
4. 验证：问「薛之谦最近有什么演出」→ 返回 JSON，`damai.cn` 是 `OFFICIAL`，`mp.weixin.qq.com` 是 `UNVERIFIED`

**阶段 2：实体过滤 + 空结果（0.5 天）**
1. 加 `isAboutArtist` 过滤噪音
2. 加空结果 `notes`
3. 验证：查一个不存在的艺人 → 返回 `notes`，模型不编造
4. 验证：搜「薛之谦」时的「同款/壁纸」结果被过滤掉

**阶段 3：时效 + 售票状态（0.5 天）**
1. 先打印一次真实响应，确认有没有发布时间字段
2. 有 → 实现 `judgeStale`；没有 → 降级为 `isStale=false + staleReason="来源未提供发布时间"`
3. 加 `extractSaleStatus`
4. 验证：`publishedAt` 是 10 天前的条目 → `isStale=true`

**阶段 4：接入元宝搜索（1 天，可选但推荐）**
1. 实现 `WsaSearchClient`，用 `Site` + `Freshness` 原生参数替代事后过滤
2. 配置类 `ArtistEventSearchProperties`（白名单、阈值、条数上限）放 mcp-server 自己包里
3. 验证：同一问题，元宝的 `publishTime`/`siteName` 是否比 You.com 更全

**阶段 5：与 bad case 打通**
1. 工具调用会进审计节点 `nodeName=search_artist_events`
2. A2（工具失败）/ A5（耗时）/ A8（空结果）自动生效
3. 观察一周真实问题，再决定是否调阈值

---

## 9. 必须注意的坑

1. **mcp-server 是零内部依赖服务**（pom 注释明确不依赖 bootstrap/framework）。**不要引 `rag` 模块**去复用 `QueryTermMappingService`。别名归一化两个办法：① 在工具内部维护一个小的静态别名表（薛之谦 → 小薛/谦谦/Joker Xue）；② 让 description 教模型「用户说昵称时传正式名」。优先 ②，零维护。

2. **`McpToolResults` 只有 `success(String text)`**，没有结构化重载。所有返回都是 JSON 字符串，自己 `ObjectMapper.writeValueAsString()`。

3. **`McpToolSchema` 无 boolean**。别设计 `onlyOfficial` / `includeStale`。

4. **必须声明 `readOnlyHint`**。`McpServerConfig.requireReadOnlyHint()` 在启动时校验，漏了直接抛异常，服务起不来。

5. **白名单只用于打标，不用于检索过滤**。否则「官方还没发公告、媒体先报道」的情况彻底查不到。

6. **`isStale` 的阈值取决于数据源语义**。`verified_at`（自己核验）用 6 小时，`publish_time`（网页发布）用 7 天。混用会让规则失效。

7. **不要做「相关性打分」再交给模型排序**。一旦有分数，模型就会按分数做判断，下沉就白做了。

8. **`UNKNOWN` 售票状态是正常的**，不要为了「看起来完整」去猜。

9. **别在工具里做 LLM 调用**。工具内再调一次模型做「结果摘要」，会让延迟翻倍、成本翻倍，而且引入不可预测性。抽取规则能覆盖的用规则，覆盖不了的就返回原文 `excerpt` 让主模型自己读。

---

## 10. 验证清单

- [ ] 问「薛之谦最近有什么演出」→ 调用 `search_artist_events`，返回结构化 `items`
- [ ] 返回里有 `searchedRange`，模型回答时能说出检索的时间范围
- [ ] 结果含 `relativePhase`，即将到来与已结束能分开说
- [ ] 该艺人只有已结束场次时，模型说「近期暂无新安排，上一场是 X 月 X 日」而不是「查不到」
- [ ] 问「薛之谦生日是什么时候」→ **不调用**本工具（description 边界生效）
- [ ] 结果里 `damai.cn` 的条目 `sourceType=OFFICIAL`
- [ ] 结果里 `mp.weixin.qq.com` 的条目 `sourceType=UNVERIFIED`
- [ ] 「薛之谦同款」「歌词壁纸」类结果被实体过滤丢弃
- [ ] 查一个库里/检索都无结果的艺人 → 返回 `notes`，模型如实说没查到、不编造
- [ ] `publishedAt` 为 10 天前的条目 → `isStale=true` + `staleReason`
- [ ] 来源无发布时间 → `isStale=false` + `staleReason="来源未提供发布时间"`
- [ ] `saleStatus` 绝大多数为 `UNKNOWN`，模型不据此断言「还能买」
- [ ] 用户说「小薛」→ 模型传 `artist=薛之谦` 并能命中
- [ ] 用户说「最近」→ 模型先调 `current_date` 再调本工具
- [ ] 工具在 `/agent/v1/meta` 可见；调用后审计有 `nodeName=search_artist_events`
- [ ] **执行完建节点的 SQL 后清了 Redis `starlineagent:intent:tree`**（否则 7 天内不生效且无报错）
- [ ] 意图树里该节点 `kind=2` 且**没有 children**（非叶子会导致工具静默消失）
- [ ] 问「帮我设个提醒」→ **不调用**本工具（走创建提醒工具）
