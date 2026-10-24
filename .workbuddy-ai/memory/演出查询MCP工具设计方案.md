# 演出查询 MCP 工具设计方案（交付 Codex 执行）

> 需求：用户问「帮我查一下薛之谦最近的演出」时，Agent 能查到真实场次、城市、场馆、开票时间，并按 system prompt 的核验规则标注时效状态。
>
> 依据：本方案基于项目现有 MCP 实现（`mcp-server` 模块 73 文件 / 24 工具）实地排查，所有模板与约定均来自既有代码。

---

## 0. 先说一个必须面对的现实

**这个工具的难点不在 MCP，而在数据源。**

大麦、猫眼等票务平台**没有公开 API**（开放平台需商务合作）。所以：

```
❌ 不可行：MCP 工具直接调票务 API 实时查询
✅ 可行：  本地维护「艺人演出表」，MCP 工具只读这张表
          数据由「人工录入 + 定时搜索同步」填充
```

**这意味着工作量分布是**：

| 环节 | 占比 | 说明 |
|---|---|---|
| MCP 工具实现 | 约 20% | 照模板写，半天 |
| **表结构 + 数据采集** | **约 60%** | 真正的成本在这里 |
| 意图节点配置 | 约 10% | 建节点、填 examples |
| 调优（别名、时效） | 约 10% | 需要真实数据打磨 |

**如果只写 MCP 工具而不解决数据来源，工具会永远返回空结果**——那比没有工具更糟，因为模型会拿到「查无结果」，然后要么编造、要么一直说「查不到」。

---

## 1. 为什么不直接用现成的 `youcom_search`

项目里已有联网搜索工具，能不能直接用它查演出？

| 对比项 | `youcom_search` | 专用 `search_artist_events` |
|---|---|---|
| 结构化程度 | ❌ 返回网页片段，需模型自己解析 | ✅ 直接给场次/城市/开票时间 |
| 来源核验 | ❌ 无法区分官方/媒体/粉丝号 | ✅ `source_type` 字段标明 |
| 时效判定 | ❌ 模型自己算「6小时」 | ✅ 工具算好 `stale` 标记 |
| 稳定性 | ❌ 每个网页格式不同，解析易错 | ✅ 固定 schema |
| 时效性 | ✅ 实时 | ⚠️ 取决于同步频率 |

**结论**：两者不是替代关系，而是**主备关系**。

```
search_artist_events 查到结果 → 直接用
         ↓ 查不到
youcom_search 兜底 → 返回未核实线索（标记 source_type=UNVERIFIED）
```

这正好对应 system prompt 里那条规则：「媒体、粉丝账号只能作为未核实线索，不能单独作为官方结论」。

---

## 2. 数据表设计

新建 `t_artist_event`（mcp-server 独立库，与 `t_product` 等同库）：

```sql
CREATE TABLE IF NOT EXISTS t_artist_event (
    id               VARCHAR(20)  NOT NULL PRIMARY KEY,
    artist_name      VARCHAR(128) NOT NULL,
    artist_aliases   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    event_title      VARCHAR(256),
    event_type       VARCHAR(32)  NOT NULL DEFAULT 'CONCERT',
    city             VARCHAR(64),
    venue            VARCHAR(256),
    event_date       DATE,
    event_time       VARCHAR(32),
    sale_start_time  TIMESTAMP,
    sale_status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    ticket_platform  VARCHAR(64),
    source_url       TEXT,
    source_type      VARCHAR(32)  NOT NULL DEFAULT 'UNVERIFIED',
    verified_at      TIMESTAMP,
    verify_status    VARCHAR(32)  NOT NULL DEFAULT 'UNVERIFIED',
    remark           VARCHAR(512),
    deleted          SMALLINT     NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_artist_event_artist ON t_artist_event (artist_name, event_date);
CREATE INDEX IF NOT EXISTS idx_artist_event_date   ON t_artist_event (event_date);
CREATE INDEX IF NOT EXISTS idx_artist_event_city   ON t_artist_event (city);

COMMENT ON COLUMN t_artist_event.source_type   IS 'OFFICIAL=官方公告 / MEDIA=媒体 / FAN=粉丝账号 / UNVERIFIED=未核实';
COMMENT ON COLUMN t_artist_event.verify_status IS 'VERIFIED=已核验 / UNVERIFIED=未核验 / CONFLICT=来源冲突';
COMMENT ON COLUMN t_artist_event.sale_status   IS 'PENDING=待开票 / ON_SALE=售票中 / SOLD_OUT=售罄 / CLOSED=已结束';
COMMENT ON COLUMN t_artist_event.event_type    IS 'CONCERT=演唱会 / TOUR=巡演 / FANMEETING=见面会';
```

**为什么 `artist_aliases` 用 JSONB 数组而非关联表**：别名通常 2-5 个，不需要独立表；查询用 `artist_name = ? OR artist_aliases @> ?` 即可。

---

## 3. 工具设计

### 3.1 基本定义

```
TOOL_ID     : search_artist_events
annotations : McpToolAnnotations.READ_ONLY     ← 只读，无副作用
require_confirm : 0                            ← 不需要用户确认
```

### 3.2 ⚠️ description 是最关键的一行

模型完全依赖 description 决定「要不要调这个工具」。写错会导致**误调用**——比如拿它去查艺人生日。

```java
private static final String DESCRIPTION =
    "查询艺人的演出、巡演、场次、场馆与开票时间。返回结构化场次列表，"
  + "每条含城市、场馆、演出日期、开票时间、票务平台、官方来源链接与核验状态。"
  + "需要判断某场演出是否已开票、何时开票、在哪个城市举办时调用本工具。"
  + "\n\n不适用：艺人个人资料（生日、出道时间、代表作品、获奖经历）、"
  + "娱乐八卦、与演出无关的资讯——这类问题请直接回答，不要调用本工具。"
  + "\n\n注意：本工具只返回已录入系统的演出数据。返回空列表表示系统暂无该艺人记录，"
  + "不代表该艺人没有演出安排；此时应如实说明并建议用户关注官方渠道，不得编造场次。";
```

**这个 description 同时解决三个问题**：
1. 防止拿它查生日（你之前 payload 里模型被问生日，如果有这个工具且没写清边界，很可能误调）
2. 明确「空结果 ≠ 没有演出」，防止模型编造
3. 提示「不得编造场次」，与 system prompt 的防幻觉规则对齐

### 3.3 入参 schema

```java
JsonSchema inputSchema = McpToolSchema.object()
        .required(string("artist", "艺人姓名，中文标准名，如「薛之谦」"))
        .optional(string("city", "城市筛选，如「北京」「上海」；不传则返回全部城市"))
        .optional(string("dateFrom", "起始日期 yyyy-MM-dd，含当天；不传则不限"))
        .optional(string("dateTo", "结束日期 yyyy-MM-dd，含当天；不传则不限"))
        .build();
```

**为什么入参要这么少**：
- `artist` 必填，其余可选——降低模型调用门槛
- **不要设 `limit` 参数**：模型容易乱填，固定内部上限 20 条即可
- **不要设 `includeStale` 参数**：过期数据一律返回但打标记，由模型决定怎么用，比让模型选更可靠

### 3.4 别名匹配（复用现成资产）

用户可能说「薛子谦」（错别字）、「小薛」（昵称）。两层处理：

**第一层：复用 `t_query_term_mapping`**

这张表就是 `BadCaseRepairService.applyMapping()` 写入的，运营已经在用它修查询词问题。工具里查表做归一化：

```java
// 先查别名映射（与 RAG 侧共用同一张表，运营在 bad case 后台配的映射自动生效）
String normalized = queryTermMappingService.normalize(artist);
```

⚠️ 但 mcp-server 是**零内部依赖的独立服务**（pom 注释明确写了不依赖 bootstrap/framework），直接引 `rag` 模块会破坏隔离。两个选择：

- **方案 A（推荐）**：把映射表的数据**同步一份**到 mcp-server 库（定时同步或 MQ）
- **方案 B**：在 `t_artist_event.artist_aliases` 里冗余存别名，工具直接查本表

**建议先用方案 B**，简单、无跨服务依赖；等别名多了再考虑 A。

**第二层：SQL 里做别名匹配**

```sql
SELECT * FROM t_artist_event
WHERE deleted = 0
  AND (
        artist_name = #{artist}
     OR artist_aliases @> to_jsonb(#{artist}::text)
      )
```

`artist_aliases @> '["小薛"]'` 能命中数组包含。

### 3.5 时效判定（在工具里算，不交给模型）

system prompt 有两条硬规则：

```
开票时间超过 6 小时未核验 → 过期
演出日期超过 24 小时未核验 → 过期
```

**必须在工具里算好**，理由：模型算时间容易错，而且每次都要读一遍规则浪费 token。

```java
private static final long SALE_STALE_HOURS = 6;
private static final long EVENT_STALE_HOURS = 24;

private StaleMark judgeStale(ArtistEvent e, Instant now) {
    Instant verified = e.getVerifiedAt() == null ? Instant.EPOCH : e.getVerifiedAt().toInstant();

    // 开票时间：6 小时未核验即过期
    if (e.getSaleStartTime() != null) {
        long hours = Duration.between(verified, now).toHours();
        if (hours > SALE_STALE_HOURS) {
            return new StaleMark(true, "开票时间已 " + hours + " 小时未核验，超过 6 小时阈值");
        }
    }
    // 演出日期：24 小时未核验即过期
    if (e.getEventDate() != null) {
        long hours = Duration.between(verified, now).toHours();
        if (hours > EVENT_STALE_HOURS) {
            return new StaleMark(true, "演出日期已 " + hours + " 小时未核验，超过 24 小时阈值");
        }
    }
    return new StaleMark(false, null);
}
```

### 3.6 返回结构

**用 `McpToolResults.success(text)` 返回 JSON 字符串**（方法签名是 `success(String text)`，没有结构化重载）：

```json
{
  "artist": "薛之谦",
  "matchedName": "薛之谦",
  "queriedAt": "2026-10-24T21:30:00+08:00",
  "total": 2,
  "events": [
    {
      "title": "薛之谦「天外来物」巡回演唱会 北京站",
      "type": "TOUR",
      "city": "北京",
      "venue": "国家体育场",
      "eventDate": "2026-12-20",
      "eventTime": "19:30",
      "saleStartTime": "2026-11-01 12:00",
      "saleStatus": "PENDING",
      "platform": "大麦网",
      "sourceUrl": "https://detail.damai.cn/item.htm?id=xxx",
      "sourceType": "OFFICIAL",
      "verifyStatus": "VERIFIED",
      "verifiedAt": "2026-10-24T20:00:00+08:00",
      "stale": false,
      "staleReason": null
    }
  ],
  "notes": []
}
```

**空结果时**（关键，防止模型编造）：

```json
{
  "artist": "薛之谦",
  "total": 0,
  "events": [],
  "notes": [
    "系统暂未收录该艺人的演出记录。",
    "这不代表该艺人没有演出安排，请勿据此推断或编造场次。",
    "建议引导用户关注官方渠道（艺人官方微博、主办方、票务平台官方公告）。"
  ]
}
```

**来源冲突时**（system prompt 要求「并列展示，不下结论」）：

```json
{
  "verifyStatus": "CONFLICT",
  "notes": ["检测到 2 个来源的开票时间不一致，请并列展示差异，不要下结论，也不要据此创建提醒。"]
}
```

---

## 4. 别名/意图节点配置

工具实现完**必须建意图节点**，否则 Agent 看不到它（`AgentToolCatalog.resolveMcpTools()` 只遍历 `listMcpToolNodes()`）。

```sql
INSERT INTO t_intent_node (
    id, intent_code, name, level, parent_code, description,
    mcp_tool_id, require_confirm, kind, examples,
    sort_order, enabled
) VALUES (
    '<雪花ID>',
    'ARTIST_EVENT_QUERY',
    '艺人演出查询',
    1,
    NULL,
    '查询艺人的演出、巡演、场次与开票时间',
    'search_artist_events',
    0,                                    -- 只读，无需确认
    2,                                    -- IntentKind.MCP
    '帮我查一下薛之谦最近的演出\n薛之谦今年有演唱会吗\n林俊杰什么时候开票\n周杰伦北京站是哪天\n小薛最近在哪个城市巡演',
    100,
    1
);
```

**`examples` 字段尤其重要**——它是意图识别和模型判断的锚点。要覆盖：
- 标准问法（「薛之谦最近演出」）
- 变体问法（「XX 什么时候开票」）
- 别名（「小薛」）
- 带城市（「北京站」）

---

## 5. 数据采集（真正的工作量）

MCP 工具只负责「读」。数据从哪来有三个来源，按可靠性排序：

### 5.1 人工录入（最可靠，先做这个）

在管理后台加一个「演出信息管理」页面，运营手工录入已核验的官方公告。

**最小可用版本**：不建后台页面，先用 SQL 直接插数据验证链路。等工具跑通了再补 UI。

### 5.2 定时搜索同步（半自动）

定时任务用 `youcom_search` 或直接调 You.com API：

```
每 6 小时跑一次
  ↓
对 t_artist_event 里「近期有演出」的艺人，搜索「{艺人} 演唱会 官方」
  ↓
抓到新场次 → 落库，source_type=MEDIA（未核实）
  ↓
人工在后台确认 → 改成 OFFICIAL + VERIFIED
```

**关键是「机器采集的永远是 MEDIA，人确认后才变 OFFICIAL」**——这与 system prompt 的核验规则一致。

### 5.3 官方 API（长期，需商务）

大麦/猫眼开放平台需要合作。如果能拿到，替换 5.2 的采集层即可，工具层不用改。

---

## 6. 实施顺序

**阶段 1：表 + 假数据 + 工具（1 天，先跑通链路）**
1. 建 `t_artist_event` 表
2. 手工插 2-3 条薛之谦的真实数据（自己从官网抄，verified_at 填当前时间）
3. 写 `ArtistEventSearchMcpExecutor`
4. 建意图节点
5. 验证：Agent 问「薛之谦最近演出」，能返回结构化结果

**阶段 2：时效与空结果（0.5 天）**
1. 加 `judgeStale` 逻辑
2. 验证：把 `verified_at` 改成 10 小时前 → 返回 `stale: true`
3. 验证：查一个没录入的艺人 → 返回 `notes` 而不是空 JSON

**阶段 3：别名（0.5 天）**
1. `artist_aliases` 填别名（薛之谦 → ["小薛","谦谦","Joker Xue"]）
2. 验证：问「小薛最近演出」也能命中

**阶段 4：采集与运营（持续）**
1. 管理后台 UI（或先 SQL）
2. 定时同步任务
3. 运营核验流程

---

## 7. 必须注意的坑

1. **mcp-server 是零依赖独立服务**：pom 注释明确「不依赖 bootstrap / framework」。不要为了复用 `QueryTermMappingService` 而引入 `rag` 模块，会破坏隔离。用冗余别名或数据同步解决。

2. **`McpToolResults` 只有 `success(String)`**：没有结构化重载，所有返回都是 JSON 字符串，需要自己 `ObjectMapper.writeValueAsString()`。

3. **`McpToolSchema` 只支持 string/integer/number/array**：没有 boolean。所以别设计 `includeStale` 这种布尔参数。

4. **必须声明 `readOnlyHint`**：`McpServerConfig.requireReadOnlyHint()` 会在启动时校验，没声明直接抛异常启动失败。这个工具用 `McpToolAnnotations.READ_ONLY`。

5. **不要在这个工具里创建提醒**。system prompt 明确「提醒创建」是另一个工具的事，混在一起会破坏「用户明确确认」的约束。

6. **`eventDate` 用 `DATE` 而非 `TIMESTAMP`**：演出日期是日期粒度，用 timestamp 会引入时区歧义。

7. **空结果必须给 `notes`**：这是防止模型编造的最后一道防线。只返回 `{"total":0}` 而不给说明，模型很可能开始「发挥」。

---

## 8. 验证清单

- [ ] 问「帮我查一下薛之谦最近的演出」→ 调用 `search_artist_events`，返回结构化场次
- [ ] 问「薛之谦生日是什么时候」→ **不调用**本工具（description 边界生效），直接回答
- [ ] 问「小薛最近有演出吗」→ 通过别名命中
- [ ] 问「周杰伦最近演出」但库里没有 → 返回 `notes` 说明，**模型不编造场次**
- [ ] 把某条 `verified_at` 改成 10 小时前 → 返回 `stale: true` + `staleReason`
- [ ] `verify_status=CONFLICT` 的数据 → 返回 `notes` 要求并列展示、不下结论
- [ ] 工具在 Agent 工具清单里可见（`/agent/v1/meta` 能查到）
- [ ] 工具调用后 bad case 规则 A2/A5 能对它生效（因为 `nodeName=search_artist_events`）
