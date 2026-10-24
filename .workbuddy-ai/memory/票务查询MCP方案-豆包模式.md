# 票务查询 MCP 工具方案（参照豆包 web_search 模式）

> 目标：参照豆包「web_search + 结构化引用 + 域名白名单」的做法，为星序 Agent 增加票务查询能力，让「薛之谦最近演出」这类问题能查到**带来源类型和时效标记**的结构化结果。
>
> 依据：本方案基于火山方舟 web_search 官方文档、You.com 现有集成、项目 MCP 实现实地排查（2026-10-24）。**未修改任何项目文件。**

---

## 0. 核心思路：把豆包的能力映射到你的架构

| 豆包/火山方舟 | 你的项目 | 实现方式 |
|---|---|---|
| 内置 `web_search` 工具 | 新增 `search_ticket` MCP 工具 | mcp-server 里加一个 executor |
| `allowed_domains` 白名单 | 工具内域名白名单配置 | ⚠️ You.com 不支持 API 级过滤，**在工具代码里判** |
| `annotations`（site_name / publish_time / url） | 结构化返回 `sourceType` / `domain` / `ageHours` | 工具层解析 + 判定 |
| `user_location` | `city` 参数（可选） | 拼进 query |
| 多信源 | You.com（web+news 两段） | 现有能力 |

**关键判断：You.com 没有 `allowed_domains`，也没有稳定的 `publish_time`。所以豆包那种「API 级白名单」做不了，必须把判断下沉到工具代码里——这反而更灵活，因为白名单完全由你控制。**

---

## 1. 先看清 You.com 给你的原始材料

现有 `YouComSearchMcpExecutor.formatResults()` 只解析了 4 个字段：

```java
String title = item.path("title").asText("(无标题)");
String url    = item.path("url").asText("");
String excerpt= resolveExcerpt(item);   // description 或 snippets
```

**有 `url` 但没解析域名，没解析发布时间。** 所以现在模型拿到的是一坨文本，让它自己判断「这是不是官方、新不新」。

我们要做的，就是在 `url` 这一项上做文章：**URL 是现成的，域名和可信度判断可以从它确定性推导出来，不需要任何外部能力。**

---

## 2. 新增工具 `search_ticket`

### 2.1 工具定义

```java
TOOL_ID     : search_ticket
annotations : McpToolAnnotations.READ_ONLY
require_confirm : 0
```

### 2.2 description（最关键，防误用 + 定边界）

```java
private static final String DESCRIPTION =
    "查询艺人演唱会、巡演、场次、场馆、开票时间的实时信息。"
  + "内部先做联网搜索，再按官方域名白名单标注每条来源的可信度与时效，"
  + "返回结构化结果（含 sourceType：OFFICIAL 官方 / UNVERIFIED 未核实，"
  + "以及 isStale 过期标记、domain 域名、sourceUrl 链接）。"
  + "\n\n使用场景：用户询问某艺人的演出安排、开票时间、在哪个城市举办、"
  + "是否已官宣等实时信息时调用。"
  + "\n\n不适用：艺人个人资料（生日、出道时间、代表作品、获奖经历）、"
  + "娱乐八卦、与演出无关的资讯——这类问题直接回答，不要调用本工具。"
  + "\n\n重要：sourceType=UNVERIFIED 的结果只能作为未核实线索陈述，"
  + "不能作为官方结论或提醒依据；isStale=true 的结果必须向用户说明已过期。"
  + "返回空列表表示本次搜索未找到结果，不代表该艺人没有演出，不得编造。";
```

### 2.3 入参 schema

```java
JsonSchema inputSchema = McpToolSchema.object()
        .required(string("artist", "艺人姓名，如「薛之谦」"))
        .optional(string("city", "城市名，如「北京」；不传则不限"))
        .optional(string("freshness", "时间过滤：day / week / month，默认 week")
                .options(List.of("day", "week", "month"))
                .defaultTo("week"))
        .build();
```

**为什么默认 `week`**：演出信息讲究时效，查一年前的新闻没意义。默认一周，模型可自己放宽。

> ⚠️ `McpToolSchema` 没有 boolean 类型，别设计「是否只看官方」这种布尔参数。域名判定在工具内部自动完成。

### 2.4 工具内部流程

```java
CallToolResult handleCall(CallToolRequest request) {
    // 1. 取参数
    String artist = MapUtil.getStr(args, "artist");
    String city   = MapUtil.getStr(args, "city");
    String freshness = MapUtil.getStr(args, "freshness");

    // 2. 拼 query（把 city 编进去，模拟 user_location 的作用）
    String query = city.isBlank()
        ? artist + " 演唱会 开票 官方"
        : artist + " " + city + " 演唱会 开票 官方";

    // 3. 调 You.com（复用现有 doSearch 逻辑，但解析要改）
    JsonNode results = callYouCom(query, freshness);

    // 4. 逐条下沉判定（核心新增）
    List<Map<String, Object>> items = results.stream()
        .map(this::toStructuredItem)   // 见 2.5
        .sorted(byTrustDesc)           // OFFICIAL 排前
        .limit(20)
        .toList();

    // 5. 返回 JSON（不再是拼文本）
    return McpToolResults.success(objectMapper.writeValueAsString(
        Map.of("artist", artist, "total", items.size(), "items", items)));
}
```

### 2.5 核心：`toStructuredItem` —— 下沉判定

这是整个方案的心脏。把每个原始结果转成带判定字段的结构化对象：

```java
private Map<String, Object> toStructuredItem(JsonNode item, Instant now) {
    String url    = item.path("url").asText("");
    String title  = item.path("title").asText("");
    String excerpt= resolveExcerpt(item);

    // ① 域名提取（确定性，纯字符串操作）
    String domain = extractDomain(url);   // mp.weixin.qq.com / detail.damai.cn / www.maoyan.com

    // ② 可信度判定（白名单匹配，100% 确定）
    boolean official = trustConfig.getOfficialDomains().stream()
            .anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    String sourceType = official ? "OFFICIAL" : "UNVERIFIED";

    // ③ 时效判定（拿不到发布时间就退化为「未知」）
    String publishTime = item.path("age").asText(null);   // You.com 可能给 age
    Long ageHours = parseAgeHours(publishTime);           // 拿不到返回 null
    boolean stale = ageHours != null && ageHours > trustConfig.getStaleHours(freshness);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("title", title);
    out.put("domain", domain);
    out.put("sourceType", sourceType);
    out.put("isStale", stale);
    out.put("ageHours", ageHours);          // 可能为 null
    out.put("sourceUrl", url);
    out.put("excerpt", excerpt);
    return out;
}
```

### 2.6 返回 JSON 示例

```json
{
  "artist": "薛之谦",
  "total": 3,
  "items": [
    {
      "title": "薛之谦「天外来物」巡回演唱会北京站",
      "domain": "detail.damai.cn",
      "sourceType": "OFFICIAL",
      "isStale": false,
      "ageHours": 3.2,
      "sourceUrl": "https://detail.damai.cn/item.htm?id=xxx",
      "excerpt": "演出时间 2026-12-20，11月1日开票..."
    },
    {
      "title": "薛之谦演唱会门票购买攻略",
      "domain": "mp.weixin.qq.com",
      "sourceType": "UNVERIFIED",
      "isStale": true,
      "ageHours": 41,
      "sourceUrl": "https://mp.weixin.qq.com/s/xxx",
      "excerpt": "..."
    }
  ]
}
```

**模型拿到这个，直接读 `sourceType` 和 `isStale`，不用推理。**

---

## 3. 域名白名单配置

在 mcp-server 新增配置类（参考 `BitProperties` 的写法）：

```java
@Data
@Component
@ConfigurationProperties(prefix = "starlineagent.ticket-search")
public class TicketSearchProperties {
    /** 官方域名清单（白名单），命中即视为 OFFICIAL */
    private List<String> officialDomains = List.of(
        "damai.cn",       // 大麦
        "maoyan.com",     // 猫眼
        "showstart.com",  // 秀动
        "piaoxingqiu.com" // 票星球
    );
    /** 各 freshness 对应的过期阈值（小时） */
    private int staleHoursDay = 6;
    private int staleHoursWeek = 24;
    private int staleHoursMonth = 168;
}
```

`mcp-server/src/main/resources/application.yml` 里可覆盖：

```yaml
starlineagent:
  ticket-search:
    official-domains:
      - damai.cn
      - maoyan.com
      - showstart.com
```

**这个白名单就是 system prompt 里「平台级官方域名清单」的技术落地。** 加一个域名不用改代码，改配置重启即可。

---

## 4. 与 system prompt 的配合（下沉后简化）

改造后，system prompt 里那一大段核验规则可以砍成 3 句：

```
票务查询工具已返回每条来源的 sourceType（OFFICIAL 官方 / UNVERIFIED 未核实）、
isStale（是否过期）、sourceUrl（链接）。按字段组织回答，不要自行推断可信度。
sourceType=UNVERIFIED 只能作为未核实线索，不得作为结论或提醒依据。
isStale=true 时必须说明「该信息已超过 N 小时未核验」。
```

**收益**：prompt 变短、更可靠，且「可信度判断」从模型大脑挪到了可审计的代码里。

---

## 5. 意图节点配置

工具实现完必须建意图节点，否则 Agent 看不到：

```sql
INSERT INTO t_intent_node (
    id, intent_code, name, level, parent_code, description,
    mcp_tool_id, require_confirm, kind, examples, sort_order, enabled
) VALUES (
    '<雪花ID>', 'TICKET_QUERY', '票务查询', 1, NULL,
    '查询艺人演出、巡演、场次与开票时间',
    'search_ticket', 0, 2,
    '帮我查一下薛之谦最近的演出\n薛之谦今年有演唱会吗\n林俊杰什么时候开票\n周杰伦北京站是哪天\n小薛最近在哪个城市巡演',
    100, 1
);
```

---

## 6. 与 `youcom_search` 的关系

两者**并存，不是替代**：

```
search_ticket   → 票务专用：带域名白名单 + 结构化 + 时效
youcom_search   → 通用兜底：查不到的杂项信息
```

模型按 description 自己选。票务问题优先命中 `search_ticket`（description 更精准），其他问题走 `youcom_search`。

---

## 7. 实施顺序（4 阶段）

**阶段 1：白名单判定（最核心，先做这个）**
1. `TicketSearchProperties` 配置类
2. `SearchTicketMcpExecutor`（先不判时效，只做域名 → sourceType）
3. `extractDomain` + `resolveExcerpt` 工具方法
4. 建意图节点
5. 验证：返回里每条都有 `sourceType`，`damai.cn` 是 OFFICIAL、`mp.weixin.qq.com` 是 UNVERIFIED

**阶段 2：时效判定（依赖 You.com 是否给 age）**
1. 先打印一次真实响应，确认有没有 `age` / `publish_time` 字段
2. 有 → 实现 `parseAgeHours` + `isStale`
3. 没有 → 降级：`ageHours=null`，`isStale=false`，在返回里加 `notes: ["该数据源未提供发布时间，无法判定时效"]`

**阶段 3：system prompt 简化**
1. 把 5 条核验规则砍成 3 句
2. 验证：问「薛之谦演出」，模型正确区分 OFFICIAL / UNVERIFIED

**阶段 4：与 bad case 打通**
1. 工具调用后，`nodeName=search_ticket` 会进入审计节点
2. A2（工具失败）/ A5（耗时）自动对它有信号

---

## 8. 必须注意的坑

1. **You.com 不支持域名过滤**：白名单必须在工具代码里判，别幻想传个 `site:` 参数。这是本方案和豆包最大的实现差异。

2. **`publish_time` 不确定**：You.com 可能给 `age`（相对时间），也可能不给。**阶段 2 必须先打印真实响应再写解析**，不要假设字段名。

3. **mcp-server 零依赖**：`TicketSearchProperties` 要放 mcp-server 自己的包，别引 rag/framework。

4. **域名匹配要处理子域名**：`detail.damai.cn` 要能匹配 `damai.cn`。用 `domain.endsWith("." + officialDomain)` 或精确相等。

5. **`sourceType` 只有两档**：OFFICIAL / UNVERIFIED。不要自作主张加 MEDIA/FAN 档——那需要维护媒体域名清单，先做两档够用，system prompt 规则里「媒体、粉丝账号」都归 UNVERIFIED。

6. **排序**：OFFICIAL 排前面，让模型优先看到可信来源。这能显著提升生成质量。

7. **空结果要说明**：`total=0` 时返回 `notes`，明确「不代表没有演出」，防编造。

---

## 9. 验证清单

- [ ] 问「帮我查薛之谦最近演出」→ 调 `search_ticket`，返回 JSON（非纯文本）
- [ ] 返回结果里 `damai.cn` 的条目 `sourceType=OFFICIAL`
- [ ] `mp.weixin.qq.com` 的条目 `sourceType=UNVERIFIED`
- [ ] 问「薛之谦生日」→ 不调 `search_ticket`（description 边界生效）
- [ ] 库/搜索无结果时 → 返回 `notes`，模型不编造
- [ ] 模型回答时能正确引用 `sourceUrl`，并标注「未核实」「已过期」
- [ ] 工具在 `/agent/v1/meta` 可见，`nodeName=search_ticket` 进入审计
- [ ] 白名单加一个域名，重启后生效（无需改代码）
