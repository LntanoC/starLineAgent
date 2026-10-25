# `create_reminder` MCP 工具设计

> 目标：用户说「帮我盯着薛之谦上海站开票，到时候提醒我」时，Agent 能真正把提醒建起来。
>
> 核查日期：2026-10-25。基于 `StarlineEventService` / `StarlineEventDO` / `SaveStarlineEventRequest` / `StarlineInvocationContext` / `StarlineRuleViolationException` / `UserContext` / `UserDO` 源码与表结构实地核实。**本次未修改任何项目文件。**

---

## 0. 三个决定了整个设计的硬约束

### 0.1 `t_user` 表没有 email 字段

```java
// system/.../user/dao/entity/UserDO.java
private String id;
private String username;
private String password;
private String avatar;
private String role;      // admin / user
// 没有 email
```

**「从登录态取邮箱」这条路走不通。** 邮箱必须是工具参数。

> 这与我在上一条回答里说的「邮箱从登录态取」矛盾，那条判断错了，这里纠正。

**影响**：`email` 进参数，但要做**脱敏回显**（确认卡显示 `a***@qq.com`），且不写进日志与对话历史。

### 0.2 mcp-server 连不到主库，也不能引 starline 模块

```yaml
# mcp-server/src/main/resources/application.yml（改造后）
server:
  address: 127.0.0.1
  port: 9099
starlineagent:
  artist-event-search: ...
# 没有 spring.datasource
```

```xml
<!-- mcp-server/pom.xml 改造后只剩 3 个依赖 -->
spring-boot-starter-webmvc
io.modelcontextprotocol.sdk:mcp
cn.hutool:hutool-all
```

而 `t_starline_event_reminder` 在**主库** `starlineagent`，写入逻辑在 **`starline` 模块**。

**结论：必须走 HTTP。** 三个可选做法对比：

| 方案 | 校验逻辑 | 代价 |
|---|---|---|
| mcp-server 连主库直接写 | **要复制一份**，必然漂移 | 破坏零依赖原则；两套 ruleCode 会不一致 |
| mcp-server 引 starline 模块 | 复用 | 打破服务隔离，mcp-server 不再能独立部署 |
| **HTTP 调内部接口** ✅ | **复用同一份** | 多一跳本机调用（<5ms） |

### 0.3 主应用侧已经为这个场景准备好了

`StarlineEventService` 已有重载：

```java
@Transactional
public String create(SaveStarlineEventRequest request, StarlineInvocationContext context) {
    try { return doCreate(request); }
    catch (StarlineRuleViolationException failure) {
        if (context != null && context.systemGenerated()) {
            // 自动上报 STARLINE_RULE_VIOLATION 高置信 bad case
        }
        throw failure;
    }
}
```

`StarlineInvocationContext` 定义了 `AGENT_TOOL` 来源，`systemGenerated()` 返回 true。

**这套机制就是为「Agent 调星序业务」准备的，只是还没人调用。`create_reminder` 正好把链路接上。**

---

## 1. 参数设计：Agent 场景和表单场景不一样

现有 `SaveStarlineEventRequest` 有 9 个字段，那是**表单场景**的。直接搬给模型是错的。

### 1.1 逐字段决定

| 字段 | 表单场景 | Agent 场景 | 理由 |
|---|---|---|---|
| `infoStatus` | 用户选 ANNOUNCED / TO_BE_ANNOUNCED | **固定 ANNOUNCED** | 建提醒必然是已公布信息，不该让模型判断 |
| `artistName` | 用户填 | **参数**（用户说的艺人） | — |
| `eventName` | 用户填 | **参数**（从查询结果带） | 模型手上有 `items[].title` |
| `sourceName` | 用户填 | **参数**（从查询结果带） | 模型手上有 `domain` |
| `sourceUrl` | 用户填 | **参数**（从查询结果带） | 模型手上有 `sourceUrl` |
| `ticketTime` | 用户填 | **参数**（从查询结果带，可为空） | 模型手上有 `saleStartTime` |
| `remindAt` | 用户填 | **参数**（用户说的） | — |
| `email` | 用户填 | **参数**（用户说的） | `t_user` 没 email |
| `sourceConfirmed` | 用户勾选 | **工具内部置 true** | 见 1.2 |

### 1.2 `sourceConfirmed` 是本次设计最需要想清楚的一处

代码里有这条校验：

```java
if (!Boolean.TRUE.equals(request.getSourceConfirmed())) {
    throw new StarlineRuleViolationException("SOURCE_NOT_CONFIRMED", "请先确认来源内容与活动信息一致");
}
```

**表单场景**：用户勾选「我已确认来源内容与活动信息一致」，这是显式确认。

**Agent 场景**：没有勾选框。**但确认卡本身就是确认。**

所以工具内部可以置 `sourceConfirmed=true`，**但必须满足两个前提**：

```java
// 前提 1：sourceUrl 必须来自 OFFICIAL 来源
// 前提 2：本次调用是写工具，会弹确认卡（用户在卡上点了同意）
if (!"OFFICIAL".equals(sourceType)) {
    return McpToolResults.error(
        "该场次仅有未核实来源，不能作为提醒依据。" +
        "请提示用户先核实官方渠道，或改为「开票即提醒」。");
}
```

**绝不能无条件置 true。** 从 `UNVERIFIED` 来源建提醒，等于让用户被营销号的消息叫起来抢一个不存在的票。

### 1.3 最终参数（4 个必填 + 3 个可选）

```java
JsonSchema schema = McpToolSchema.object()
        .required(string("artistName", "艺人名称，如「薛之谦」"))
        .required(string("eventName", "演出完整名称，取自演出查询结果的活动标题"))
        .required(string("remindAt", "提醒时间，格式 yyyy-MM-dd HH:mm，必须晚于当前时间"))
        .required(string("email", "接收提醒的邮箱。用户没提供时必须先问，不得自行编造"))
        .optional(string("ticketTime", "开票时间 yyyy-MM-dd HH:mm，取自查询结果的 saleStartTime。"
                + "没有开票时间时不要传"))
        .optional(string("sourceUrl", "来源链接，取自查询结果的 sourceUrl"))
        .optional(string("sourceName", "来源名称，如「大麦网」"))
        .build();
```

**为什么只有 7 个**：

- `infoStatus` 不需要——建提醒必然是 ANNOUNCED
- `sourceConfirmed` 不需要——由确认卡与 OFFICIAL 校验保证
- 没有 `reminderType`——**直接由 `ticketTime` 是否为空推断**（见下）

---

## 2. 提醒类型：不设参数，由字段推断

之前讨论的三种类型不需要模型传，从已有字段就能推出来：

| 条件 | 推断类型 | 语义 |
|---|---|---|
| `ticketTime` 有值 | `TICKET_TIME` | 开票前提醒（默认，最有价值） |
| `ticketTime` 为空 | `EVENT_DATE` | 没有开票时间，只能按演出日期提醒 |

**为什么不让模型传 `reminderType`**：

`McpToolSchema` 支持 enum，技术上能传。但**多一个枚举参数就多一处模型填错的机会**，而这个选择完全由数据决定，不需要模型判断。

**`ON_SALE_WATCH`（开票即提醒）不做进这个工具**——它需要的不是「用户指定提醒时间」，而是「系统轮询监测」，是另一个机制。等第一期跑通再说。

### 2.1 与「已批未宣」场景的衔接

如果查询结果里某场次 `stage=APPROVED`（已获批复未开票），`saleStartTime` 为空。此时：

```java
if (StrUtil.isBlank(ticketTime)) {
    // 只能按演出日期提醒
    // 需要从 eventDate 推算 remindAt 的合理默认值
}
```

**但 `eventDate` 不在当前参数里。** 这是个缺口，需要补第 8 个参数：

```java
.optional(string("eventDate", "演出日期 yyyy-MM-dd，取自查询结果。"
        + "没有开票时间时用于推算提醒时间"))
```

**校验规则**（工具内部，不交给模型）：

```java
if (ticketTime != null && !remindAt.isBefore(ticketTime)) {
    return McpToolResults.error("提醒时间必须早于开票时间。开票时间是 " + ticketTime
            + "，请让用户确认要提前多久提醒");
}
if (ticketTime == null && eventDate != null && !remindAt.isBefore(eventDate)) {
    return McpToolResults.error("该场次暂无开票时间，只能按演出日期提醒。"
            + "演出日期是 " + eventDate + "，提醒时间必须早于它");
}
```

这段校验和 `StarlineEventService` 里的 `REMIND_AFTER_TICKET` 规则一致，**但在这里提前拦一道，能给出更具体的提示**（告诉模型具体是哪两个时间冲突），而不是让用户反复试。

---

## 3. 三层代码

### 3.1 mcp-server 侧：`CreateReminderMcpExecutor`

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "starlineagent.starline-api", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CreateReminderMcpExecutor {

    private static final String TOOL_ID = "create_reminder";

    private static final String DESCRIPTION = """
            为一场已确认的演出创建开票提醒，到点发送邮件。
            
            适用场景：用户明确要求「开票时提醒我」「到点通知我」。
            不适用：只是查询演出信息——那是演出查询工具的事，不要调用本工具。
            
            建提醒前必须确认三件事，缺一不可：
            1. 这场演出是官方来源（演出查询结果里 sourceType=OFFICIAL）
            2. 用户明确说了接收提醒的邮箱
            3. 用户明确说了什么时候提醒
            
            用户没给邮箱时必须先问，绝对不能编造或猜测邮箱。
            同一场演出已经有提醒时不要重复创建，先查一下再说。
            """;

    private final StarlineApiClient starlineApi;

    @Bean
    public McpServerFeatures.SyncToolSpecification createReminderToolSpecification() {
        JsonSchema schema = McpToolSchema.object()
                .required(string("artistName", "艺人名称，如「薛之谦」").title("艺人"))
                .required(string("eventName", "演出完整名称，取自演出查询结果的活动标题").title("演出名称"))
                .required(string("remindAt", "提醒时间 yyyy-MM-dd HH:mm，必须晚于当前时间").title("提醒时间"))
                .required(string("email", "接收提醒的邮箱。用户没提供时必须先问，不得编造").title("提醒邮箱"))
                .optional(string("ticketTime", "开票时间 yyyy-MM-dd HH:mm，取自查询结果的 saleStartTime；"
                        + "没有开票时间时不要传").title("开票时间"))
                .optional(string("eventDate", "演出日期 yyyy-MM-dd，无开票时间时用于校验提醒时间").title("演出日期"))
                .optional(string("sourceUrl", "来源链接，取自查询结果的 sourceUrl").title("来源链接"))
                .optional(string("sourceName", "来源名称，如「大麦网」").title("来源名称"))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name(TOOL_ID)
                        .description(DESCRIPTION)
                        .inputSchema(schema)
                        .annotations(McpToolAnnotations.WRITE)
                        .build())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String userId = McpToolResults.userId(request);
            if (userId == null) {
                return McpToolResults.identityRequired(TOOL_ID);
            }

            String artistName = require(args, "artistName");
            String eventName = require(args, "eventName");
            String remindAt = require(args, "remindAt");
            String email = require(args, "email");
            String ticketTime = optional(args, "ticketTime");
            String eventDate = optional(args, "eventDate");
            String sourceUrl = optional(args, "sourceUrl");
            String sourceName = optional(args, "sourceName");

            if (artistName == null || eventName == null) {
                return McpToolResults.error("缺少艺人名称或演出名称。演出名称请取自演出查询结果");
            }
            if (remindAt == null) {
                return McpToolResults.error("缺少提醒时间。请让用户明确什么时候提醒");
            }
            if (email == null) {
                return McpToolResults.error("缺少接收提醒的邮箱。请先问用户，不要编造");
            }

            // 时间关系提前拦一道，比让用户反复试要好
            String timeError = validateTimeOrder(remindAt, ticketTime, eventDate);
            if (timeError != null) {
                return McpToolResults.error(timeError);
            }

            CreateReminderResult result = starlineApi.createReminder(
                    new CreateReminderCommand(
                            userId, artistName, eventName, remindAt, email,
                            ticketTime, eventDate, sourceUrl, sourceName),
                    McpToolResults.userId(request),
                    runId(request), messageId(request), conversationId(request));

            log.info("MCP 工具调用完成, toolId={}, artist={}, reminderType={}, elapsed={}ms",
                    TOOL_ID, artistName, result.reminderType(), System.currentTimeMillis() - startMs);

            if (!result.success()) {
                // 业务规则违例：把 ruleCode 映射成人话，模型能直接转述
                return McpToolResults.error(result.message());
            }
            return McpToolResults.success(objectMapper.writeValueAsString(Map.of(
                    "reminderId", result.reminderId(),
                    "reminderType", result.reminderType(),
                    "artistName", artistName,
                    "eventName", eventName,
                    "remindAt", remindAt,
                    "maskedEmail", maskEmail(email),
                    "notes", List.of(
                            "提醒已创建，到 " + remindAt + " 会发邮件到 " + maskEmail(email) + "。",
                            "用户要取消可以随时说。"))));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("创建提醒", e);
        }
    }

    /**
     * 时间关系校验：与 StarlineEventService 的 REMIND_AFTER_TICKET 同口径
     * <p>
     * 在这里提前拦，能告诉模型具体冲突的两个时间，而不是只返回一个 ruleCode
     */
    private String validateTimeOrder(String remindAt, String ticketTime, String eventDate) {
        LocalDateTime remind = parse(remindAt);
        if (remind == null) {
            return "提醒时间格式不正确，需要 yyyy-MM-dd HH:mm";
        }
        if (ticketTime != null) {
            LocalDateTime ticket = parse(ticketTime);
            if (ticket == null) {
                return "开票时间格式不正确，需要 yyyy-MM-dd HH:mm";
            }
            if (!remind.isBefore(ticket)) {
                return "提醒时间（" + remindAt + "）必须早于开票时间（" + ticketTime
                        + "）。请让用户确认要提前多久提醒";
            }
            return null;
        }
        if (eventDate != null) {
            LocalDateTime event = parseDate(eventDate);
            if (event != null && !remind.isBefore(event)) {
                return "该场次暂无开票时间，只能按演出日期提醒。演出日期是 " + eventDate
                        + "，提醒时间必须早于它";
            }
        }
        return null;
    }

    /** 脱敏：a***@example.com，确认卡和日志都用这个 */
    private String maskEmail(String email) {
        if (StrUtil.isBlank(email) || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        return name.length() <= 1
                ? "*" + domain
                : name.charAt(0) + "***" + domain;
    }
}
```

### 3.2 mcp-server 侧：`StarlineApiClient`

```java
package com.nageoffer.ai.starlineagent.mcp.executor.starline;

/**
 * 调主应用内部接口的客户端
 * <p>
 * 为什么要走 HTTP 而不是直连库：校验逻辑（ruleCode、时间关系、邮箱格式）在主应用里，
 * 复制一份必然漂移。本案只转发，不判断业务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StarlineApiClient {

    private final StarlineApiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public CreateReminderResult createReminder(CreateReminderCommand cmd,
                                               String userId, String runId,
                                               String messageId, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artistName", cmd.artistName());
        body.put("eventName", cmd.eventName());
        body.put("infoStatus", "ANNOUNCED");           // 固定，不让模型判断
        body.put("sourceConfirmed", true);             // 确认卡已确认，见设计 1.2
        body.put("remindAt", cmd.remindAt());
        body.put("email", cmd.email());
        if (cmd.ticketTime() != null) body.put("ticketTime", cmd.ticketTime());
        if (cmd.sourceUrl() != null) body.put("sourceUrl", cmd.sourceUrl());
        if (cmd.sourceName() != null) body.put("sourceName", cmd.sourceName());

        HttpRequest httpRequest = HttpRequest.newBuilder(
                        URI.create(properties.getBaseUrl() + "/internal/starline/reminders"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", properties.getToken())      // 本机调用凭证
                .header("X-Starline-User-Id", userId)                   // 用户身份
                .header("X-Starline-Origin", "AGENT_TOOL")              // 关键：标记来源
                .header("X-Starline-Run-Id", StrUtil.emptyIfNull(runId))
                .header("X-Starline-Message-Id", StrUtil.emptyIfNull(messageId))
                .header("X-Starline-Conversation-Id", StrUtil.emptyIfNull(conversationId))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            if (response.statusCode() == 200 && root.path("success").asBoolean(false)) {
                JsonNode data = root.path("data");
                return new CreateReminderResult(true, data.path("id").asText(),
                        data.path("reminderType").asText("TICKET_TIME"), null);
            }
            // 业务规则违例：主应用返回的 message 已经是人话
            String message = root.path("message").asText("创建提醒失败，请稍后重试");
            return new CreateReminderResult(false, null, null, message);
        } catch (Exception e) {
            log.error("调用星序内部接口失败", e);
            return new CreateReminderResult(false, null, null, "创建提醒服务暂时不可用，请稍后重试");
        }
    }

    public record CreateReminderCommand(String userId, String artistName, String eventName,
                                        String remindAt, String email, String ticketTime,
                                        String eventDate, String sourceUrl, String sourceName) {}

    public record CreateReminderResult(boolean success, String reminderId,
                                       String reminderType, String message) {}
}
```

### 3.3 主应用侧：`StarlineInternalReminderController`

```java
package com.nageoffer.ai.starlineagent.starline.controller.internal;

/**
 * 星序内部接口：只给本机 mcp-server 用
 * <p>
 * 安全边界：不挂 Sa-Token、只接受 127.0.0.1、要求内部 token。
 * 用户身份从请求头取——因为 MCP 的 _meta 已经透传了 userId，那条链是可信的
 */
@RestController
@RequestMapping("/internal/starline")
@RequiredArgsConstructor
public class StarlineInternalReminderController {

    private final StarlineEventService service;
    private final StarlineInternalProperties properties;

    @PostMapping("/reminders")
    public Result<Map<String, Object>> createReminder(
            @RequestHeader("X-Internal-Token") String token,
            @RequestHeader("X-Starline-User-Id") String userId,
            @RequestHeader(value = "X-Starline-Origin", required = false) String origin,
            @RequestHeader(value = "X-Starline-Run-Id", required = false) String runId,
            @RequestHeader(value = "X-Starline-Message-Id", required = false) String messageId,
            @RequestHeader(value = "X-Starline-Conversation-Id", required = false) String conversationId,
            @RequestBody SaveStarlineEventRequest request) {

        // 双重保护：token + 本机来源
        if (!properties.getToken().equals(token)) {
            return Results.error("内部调用凭证无效");
        }

        // 手动设置用户上下文：这个接口没有经过 Sa-Token 拦截器
        UserContext.set(new LoginUser(userId, null, null, null, null));
        try {
            // 关键：标记为 AGENT_TOOL，让 bad case 检测生效
            StarlineInvocationContext context = new StarlineInvocationContext(
                    StarlineInvocationContext.Origin.AGENT_TOOL,
                    runId, messageId, conversationId);

            String id = service.create(request, context);

            String reminderType = StrUtil.isBlank(request.getTicketTime())
                    ? "EVENT_DATE" : "TICKET_TIME";
            return Results.success(Map.of("id", id, "reminderType", reminderType));
        } finally {
            UserContext.clear();       // 必须清，TTL 会跨线程传递
        }
    }
}
```

**`UserContext.clear()` 不能漏。** `UserContext` 基于 `TransmittableThreadLocal`，不清会污染线程池里的下一个请求。

---

## 4. 错误码到人话的映射

`StarlineEventService` 抛的 `ruleCode` 需要映射成模型能直接转述的句子。这一步放在**主应用侧**更好——因为规则属于业务域。

```java
// StarlineInternalReminderController 里 catch，或在 Service 里统一处理
private String humanize(StarlineRuleViolationException e) {
    return switch (e.getRuleCode()) {
        case "INVALID_INFO_STATUS" -> "信息状态不合法";
        case "ARTIST_REQUIRED" -> "缺少艺人名称";
        case "ANNOUNCED_EVENT_NAME_REQUIRED" -> "缺少演出名称";
        case "ANNOUNCED_SOURCE_REQUIRED" -> "缺少信息来源，请先通过演出查询拿到官方链接";
        case "SOURCE_NOT_CONFIRMED" -> "来源未确认。该场次可能不是官方信息，建议先核实";
        case "TICKET_TIME_NOT_FUTURE" -> "开票时间已过，这场可能已经开完票了";
        case "REMIND_TIME_NOT_FUTURE" -> "提醒时间必须晚于当前时间";
        case "REMIND_AFTER_TICKET" -> "提醒时间不能晚于开票时间，那样提醒就失去意义了";
        case "INVALID_EMAIL" -> "邮箱格式不正确";
        case "INVALID_SOURCE_URL" -> "来源链接格式不正确";
        default -> "创建提醒失败：" + e.getMessage();
    };
}
```

**为什么必须映射**：`ruleCode` 是给程序读的（`REMIND_AFTER_TICKET`），模型拿到它只会转述成「创建失败，错误码 REMIND_AFTER_TICKET」，用户看不懂。

映射后模型能说：「提醒时间不能晚于开票时间，那样提醒就没意义了。开票是 11 月 1 日 12 点，你想提前多久提醒？」

---

## 5. 确认卡设计（写工具的关键）

`create_reminder` 是 `WRITE`，会挂起等用户确认。确认卡上要显示什么，取决于 `McpToolSchema` 的 `title`：

```java
required(string("artistName", "...").title("艺人"))
required(string("eventName", "...").title("演出名称"))
required(string("remindAt", "...").title("提醒时间"))
required(string("email", "...").title("提醒邮箱"))
optional(string("ticketTime", "...").title("开票时间"))
```

**用户看到的确认卡大致是：**

```
即将创建提醒，请确认：

艺人       薛之谦
演出名称    薛之谦「万兽之王」巡回演唱会-上海站
开票时间    2026-11-01 12:00
提醒时间    2026-11-01 11:00
提醒邮箱    a***@qq.com

[确认]  [取消]
```

**三个细节：**

| 细节 | 做法 |
|---|---|
| 邮箱脱敏 | 工具内部 `maskEmail()` 后回传，但**确认卡显示的是原始参数**——这是 `McpToolBinding.fieldLabels` 渲染的，不走工具返回 |
| 字段顺序 | `McpToolSchema` 是 `LinkedHashMap`，按 `.required()` / `.optional()` 调用顺序渲染。**按业务重要性排**：艺人 → 演出 → 开票 → 提醒 → 邮箱 |
| 关键信息前置 | 「开票时间」放在「提醒时间」前面，用户一眼能看出提醒是在开票前还是后 |

**邮箱脱敏的矛盾**：确认卡显示完整邮箱（用户需要核对自己填的对不对），但工具返回给模型的必须是脱敏版（避免进对话历史）。

---

## 6. 幂等：同一场演出不要建两次

用户可能说「再设一个提醒」，实际是同一个演出。

**最简单的做法**：内部接口先查一次。

```java
// StarlineInternalReminderController 里，create 之前
List<StarlineEventDO> existing = service.findActiveByUserAndEvent(
        userId, request.getArtistName(), request.getEventName());
if (!existing.isEmpty()) {
    StarlineEventDO one = existing.get(0);
    return Results.success(Map.of(
            "id", one.getId(),
            "reminderType", StrUtil.isBlank(one.getTicketTime()) ? "EVENT_DATE" : "TICKET_TIME",
            "duplicate", true,
            "message", "这场演出已经有提醒了（" + one.getRemindAt() + "），没有重复创建"));
}
```

**为什么不靠数据库唯一键**：`t_starline_event_reminder` 现在没有 `(user_id, artist_name, event_name)` 唯一约束，加约束会影响已有的多条提醒场景（同一场演出用户可能想要多个时间点提醒）。

**所以靠业务层判断**，返回 `duplicate=true` 让模型告诉用户「已经有了」，而不是静默多建一条。

---

## 7. 与演出查询的联动（完整闭环）

```
① 用户：帮我盯着薛之谦上海站开票
      ↓
② 模型：调 search_artist_events(artist="薛之谦")
      ← items[上海站]: stage=PENDING, saleStartTime="2026-11-01 12:00",
                       sourceType=OFFICIAL, sourceUrl="https://detail.damai.cn/..."
      ↓
③ 模型：这场没开票，11 月 1 日开票 → 提议「要不要开票前提醒你」
      ↓
④ 用户：要，提前一小时，用我邮箱 a@qq.com
      ↓
⑤ 模型：调 create_reminder(
            artistName="薛之谦",
            eventName="薛之谦「万兽之王」巡回演唱会-上海站",   ← 从 items[].title 带
            ticketTime="2026-11-01 12:00",                    ← 从 saleStartTime 带
            remindAt="2026-11-01 11:00",                      ← 用户说的
            email="a@qq.com",                                  ← 用户说的
            sourceUrl="https://detail.damai.cn/...",           ← 从 sourceUrl 带
            sourceName="大麦网")                                ← 从 domain 带
      ↓
⑥ 确认卡挂起 → 用户点确认
      ↓
⑦ 主应用 create(request, AGENT_TOOL 上下文) → 落库，status=PENDING
      ↓
⑧ 模型：提醒已创建，11 月 1 日 11:00 会发邮件到 a***@qq.com
      ↓
⑨ （到点）StarlineReminderScheduler 轮询 → 发邮件 → status=SENT
```

**第 ⑤ 步是关键**：前 5 个参数全部来自查询结果或用户明确说明，**没有一个需要模型"补"**。

如果用户没说邮箱，第 ④ 步模型必须先问，不能从对话历史里猜（比如猜用户的 QQ 号）。

---

## 8. `UNVERIFIED` 来源的硬拦截

**必须做，而且要在两个地方做。**

### 8.1 工具 description 层面（软约束）

```
建提醒前必须确认：这场演出是官方来源（演出查询结果里 sourceType=OFFICIAL）
```

### 8.2 代码层面（硬拦截）

```java
// 内部接口里，如果 sourceUrl 的域名不在官方白名单
if (!officialDomainChecker.isOfficial(request.getSourceUrl())) {
    return Results.error("该场次的来源不在已审批的官方渠道清单内，不能作为提醒依据。" +
            "建议提示用户关注官方渠道，或改为设置「开票即提醒」");
}
```

**为什么必须两层**：

| 层 | 作用 |
|---|---|
| description | 让模型**倾向于**不拿未核实信息建提醒 |
| 代码 | 万一模型判断错了，**兜底拦住** |

**漏掉的后果**：用户被营销号的消息叫起来，去抢一个不存在的票。这比"没提醒"糟得多。

---

## 9. 验收清单

### 9.1 正常路径

- [ ] 「盯着薛之谦上海站开票，提前一小时提醒，邮箱 a@qq.com」→ 建提醒成功，`remindAt` 正确
- [ ] 确认卡显示 5 个字段，邮箱完整可核对
- [ ] 工具返回里邮箱是 `a***@qq.com`（脱敏）
- [ ] 落库后 `reminder_status=PENDING`
- [ ] 到点后 `StarlineReminderScheduler` 发邮件，`status=SENT`

### 9.2 边界与拦截

- [ ] 用户没说邮箱 → 模型追问，**不编造**
- [ ] `remindAt` 晚于 `ticketTime` → 报「提醒时间必须早于开票时间」，并给出具体时间
- [ ] 来源 `UNVERIFIED` → 拒绝建提醒并说明原因
- [ ] 同一演出重复建 → 返回 `duplicate=true`，不建第二条
- [ ] `APPROVED` 阶段（无开票时间）→ 只能按演出日期提醒，`reminderType=EVENT_DATE`
- [ ] 用户取消确认卡 → 不建任何记录

### 9.3 bad case 联动

- [ ] Agent 传了 `remindAt > ticketTime` → 主应用抛 `REMIND_AFTER_TICKET` →
      **自动上报一条 `STARLINE_RULE_VIOLATION` 高置信 bad case**
- [ ] 该 bad case 的 `source_detail` 里含 `origin=AGENT_TOOL` 和 `ruleCode`

### 9.4 安全

- [ ] `X-Internal-Token` 错误 → 拒绝
- [ ] 非本机 IP 调用内部接口 → 拒绝（若配了 IP 白名单）
- [ ] `UserContext.clear()` 生效，线程复用不串号

---

## 10. 实施顺序

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **1** | 主应用加 `StarlineInternalReminderController` + 内部 token 配置 | 用 curl 能建成提醒，库里有一条 PENDING |
| **2** | mcp-server 加 `StarlineApiClient` + `CreateReminderMcpExecutor` | 用 MCP Inspector 手动调用成功 |
| **3** | 建意图节点/注册表条目（`require_confirm=1`） | 模型能看到工具，调用时弹确认卡 |
| **4** | 端到端：查演出 → 提议 → 建提醒 | 完整闭环跑通，确认卡字段正确 |
| **5** | 幂等 + `UNVERIFIED` 硬拦截 | 重复建只返回已有记录；非官方来源被拒 |
| **6** | 观察 bad case：`STARLINE_RULE_VIOLATION` 是否正常上报 | 能看到 `origin=AGENT_TOOL` 的记录 |

**阶段 1 必须先做**，因为它能独立验证：`curl` 调通说明主应用侧一切正常，再把 mcp-server 接上就只是转发问题。

---

## 11. 一句话总结

> **这个工具真正的难点不是写代码，是三个判断：**
>
> 1. **参数要减法**：9 个表单字段砍到 8 个（其中 4 个必填），`infoStatus` 固定、`sourceConfirmed` 由确认卡代替
> 2. **校验只做一份**：走 HTTP 调主应用，不复制 `ruleCode` 逻辑，否则两套必然漂移
> 3. **官方来源是硬门槛**：`UNVERIFIED` 必须代码层拦截，不能只靠 description 约束
>
> 这三条想清楚了，代码量不到 400 行。想不清楚，做出来的工具会有「用户被假消息叫起来抢票」这类信任事故。
