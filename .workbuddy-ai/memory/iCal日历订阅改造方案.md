# 星序提醒改造：iCal 日历订阅（废除邮件）

> **目标**
> 1. 提醒方式改为 **iCal 日历订阅**，iOS / Android / Outlook 通用
> 2. 固定**提前 10 分钟**通知
> 3. 废除邮件、定时任务、投递状态机等全部推送代码
> 4. 清理其他无关的前后端代码
>
> **核查日期**：2026-10-25。基于 `starline` 模块 23 个源文件、`frontend` 相关页面、`t_starline_event_reminder` 表结构实地核实。**本次未修改任何项目文件。**

---

## 0. 一个必须确认的设计分支

用户要求「提前 10 分钟通知」，这引出两种提醒对象，处理方式不同：

| 场景 | 有 `ticket_time`？ | 能否精确提前 10 分钟 |
|---|---|---|
| **开票提醒** | ✅ 有精确时间（如 `2026-11-01 12:00`） | ✅ `DTSTART=12:00` + `VALARM:-PT10M` → **11:50 响** |
| **演出提醒** | ❌ 只有演出日期（`2026-12-20`，无时刻） | ❌ 全天事件提前 10 分钟 = **前一天 23:50** |

**本方案采用：只有 `ticket_time` 存在的记录才进日历。**

理由：没有开票时间就编一个「假设 19:30 开场」违反「不编造时间」原则；全天事件提前 10 分钟在凌晨响更没有意义。

**UI 上要如实说明**：没有开票时间的场次，界面显示「该场次尚未公布开票时间，暂不支持提醒」，而不是给一个假时间。

如果一定要支持，第 9 节给了可选方案（全天事件、无闹钟）。

---

## 1. 数据模型变更

### 1.1 `t_starline_event_reminder` 精简

**现状（11 个业务字段 + 5 个投递字段）**：

```sql
id, user_id, artist_name, event_name, info_status,
source_name, source_url, ticket_time, email, remind_at,
reminder_status, sent_at, created_time, update_time,
attempt_count, last_error_code, last_error_message,
last_delivery_outcome, failed_at
```

**改造后（8 个字段）**：

```sql
id, user_id, artist_name, event_name,
source_name, source_url, ticket_time,
reminder_status, created_time, update_time
```

**逐字段决策**：

| 字段 | 决策 | 理由 |
|---|---|---|
| `email` | **删除** | 不再发邮件 |
| `remind_at` | **删除** | 提前量固定 10 分钟，由 VALARM 表达，不需要存绝对时间 |
| `info_status` | **删除** | 语义被「是否有 `ticket_time`」取代 |
| `sent_at` | **删除** | 日历方案无「已发送」概念 |
| `attempt_count` | **删除** | 无重试 |
| `last_error_code` | **删除** | 无投递失败 |
| `last_error_message` | **删除** | 同上 |
| `last_delivery_outcome` | **删除** | 同上 |
| `failed_at` | **删除** | 同上 |
| `reminder_status` | **保留但改枚举** | `PENDING/SENDING/SENT/FAILED` → `ACTIVE/CANCELLED` |
| `ticket_time` | **保留**，改 `NOT NULL` | 没有它的记录不进日历 |

### 1.2 迁移脚本

```sql
-- resources/database/migrations/20261026_starline_calendar.sql
BEGIN;

-- ① 状态枚举收敛：历史状态全部归为 ACTIVE
UPDATE t_starline_event_reminder
SET reminder_status = 'ACTIVE'
WHERE reminder_status IN ('PENDING', 'SENDING', 'SENT', 'FAILED');

-- ② 删除投递相关字段
ALTER TABLE t_starline_event_reminder
    DROP COLUMN IF EXISTS email,
    DROP COLUMN IF EXISTS remind_at,
    DROP COLUMN IF EXISTS info_status,
    DROP COLUMN IF EXISTS sent_at,
    DROP COLUMN IF EXISTS attempt_count,
    DROP COLUMN IF EXISTS last_error_code,
    DROP COLUMN IF EXISTS last_error_message,
    DROP COLUMN IF EXISTS last_delivery_outcome,
    DROP COLUMN IF EXISTS failed_at;

-- ③ 状态约束
ALTER TABLE t_starline_event_reminder
    ADD CONSTRAINT ck_starline_reminder_status
    CHECK (reminder_status IN ('ACTIVE', 'CANCELLED'));

-- ④ 索引调整：不再需要「到点扫描」索引，改为按用户查
DROP INDEX IF EXISTS idx_starline_due_reminders;
DROP INDEX IF EXISTS idx_starline_failed_reminders;

CREATE INDEX IF NOT EXISTS idx_starline_user_active
    ON t_starline_event_reminder (user_id, ticket_time)
    WHERE reminder_status = 'ACTIVE';

COMMENT ON TABLE t_starline_event_reminder IS
    '星序提醒：以 iCal 订阅方式下发，本表只存日历事件数据，无投递状态';
COMMENT ON COLUMN t_starline_event_reminder.ticket_time IS
    '开票时间，即日历事件 DTSTART；为空表示无法进日历（演出时间未定）';
COMMENT ON COLUMN t_starline_event_reminder.reminder_status IS
    'ACTIVE=在日历中 / CANCELLED=用户已取消';

COMMIT;
```

### 1.3 新增日历订阅凭证表

```sql
CREATE TABLE IF NOT EXISTS t_starline_calendar_token (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    token           VARCHAR(64) NOT NULL,
    created_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_access_time TIMESTAMP,
    revoked         SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_starline_calendar_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_starline_calendar_token_user
    ON t_starline_calendar_token (user_id) WHERE revoked = 0;

COMMENT ON TABLE t_starline_calendar_token IS
    '日历订阅凭证：日历 App 拉取 .ics 时无 Cookie，只能把凭证放 URL';
COMMENT ON COLUMN t_starline_calendar_token.token IS
    '32 位随机串，泄露后可 revoke 并重新生成；只对 /starline/calendar.ics 生效';
```

**为什么单独建表而不是加在 `t_user` 上**：`starline` 模块不该改 `system` 模块的表；且一个用户可能需要多个凭证（多设备分别重置）。

---

## 2. 完整删除清单

### 2.1 后端：整文件删除（3 个）

```
starline/src/main/java/.../service/StarlineMailProperties.java     ← 邮件配置
starline/src/main/java/.../service/StarlineReminderScheduler.java  ← 定时任务 + SMTP 发送 + bad case 上报
starline/src/test/java/.../service/StarlineDeliveryBadCaseTest.java ← 投递失败测试
```

> **注意**：`StarlineReminderScheduler` 里还带着 `signal()` 方法（上报 `MAIL_ATTEMPT_FAILED`），删文件时这些逻辑一并消失，符合预期。

### 2.2 后端：局部删除

**`starline/pom.xml`**

```xml
<!-- 删除 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

**`StarlinePropertiesConfiguration.java`**

```java
// 改前
@EnableConfigurationProperties(StarlineMailProperties.class)
public class StarlinePropertiesConfiguration {}

// 改后：如果没有其他 properties，整个类可以删；否则去掉 StarlineMailProperties
```

**`starline/src/main/java/.../badcase/StarlineBadCaseDetector.java`**

**整个类删除。** 它扫描的是 `reminder_status = 'FAILED'` 和 `SENDING 超时`，这两个状态在新模型里都不存在了。

> ⚠️ **连带影响**：`application.yaml` 里 `badcase.detectors.starline-delivery` 的配置也要删，否则启动时找不到 detector 会告警。

**`StarlineEventDO.java`** — 删除 6 个字段 + 2 个字段

```java
// 删除
private String email;
private String infoStatus;
private LocalDateTime remindAt;
private LocalDateTime sentAt;
private Integer attemptCount;
private String lastErrorCode;
private String lastErrorMessage;
private String lastDeliveryOutcome;
private LocalDateTime failedAt;
```

**`StarlineEventVO.java`**

```java
// 改前
public record StarlineEventVO(String id, String artistName, String eventName, String infoStatus,
        String sourceName, String sourceUrl, LocalDateTime ticketTime, String email,
        LocalDateTime remindAt, String reminderStatus, LocalDateTime sentAt, LocalDateTime createdTime) {}

// 改后
public record StarlineEventVO(String id, String artistName, String eventName,
        String sourceName, String sourceUrl, LocalDateTime ticketTime,
        String reminderStatus, LocalDateTime createdTime) {}
```

**`SaveStarlineEventRequest.java`**

```java
// 改前 9 个字段
// 改后 5 个
@Data
public class SaveStarlineEventRequest {
    private String artistName;
    private String eventName;
    private String sourceName;
    private String sourceUrl;
    private Boolean sourceConfirmed;
    private String ticketTime;
}
```

**`StarlineEventService.java`** — 大幅简化，见第 4 节。

**`StarlineEventMapper.java`**

```java
// 删除 claimForSending
// 保留 BaseMapper 即可
```

**`starline/src/main/resources` 与 bootstrap 配置**

```yaml
# bootstrap/src/main/resources/starline-application.yaml
# 删除整个 spring.mail 段和 starline.email 段
```

```yaml
# bootstrap/src/main/resources/application.yaml
# 删除 badcase.detectors 下的 starline-delivery
```

**部署配置**

```yaml
# resources/deployment/starline-app.compose.yaml
# resources/docker/server/starline-app.compose.yaml
# 删除
STARLINE_EMAIL_ENABLED: ${STARLINE_EMAIL_ENABLED:-false}
```

```bash
# resources/docker/server/.env.example
# 删除
STARLINE_EMAIL_ENABLED=false
# 以及注释「# 邮件开关；服务器当前默认关闭。」
```

### 2.3 前端

**`frontend/src/services/starlineService.ts`**

```typescript
// 改前
export type StarlineReminderStatus = "NOT_CREATED" | "PENDING" | "SENDING" | "SENT" | "CANCELLED" | "FAILED";

export interface StarlineEvent {
  id: string;
  artistName: string;
  eventName: string | null;
  infoStatus: StarlineInfoStatus;
  sourceName: string | null;
  sourceUrl: string | null;
  ticketTime: string | null;
  email: string | null;
  remindAt: string | null;
  reminderStatus: StarlineReminderStatus;
  sentAt: string | null;
  createdTime: string;
}

// 改后
export type StarlineReminderStatus = "ACTIVE" | "CANCELLED";

export interface StarlineEvent {
  id: string;
  artistName: string;
  eventName: string | null;
  sourceName: string | null;
  sourceUrl: string | null;
  ticketTime: string | null;
  reminderStatus: StarlineReminderStatus;
  createdTime: string;
}

export interface SaveStarlineEventRequest {
  artistName: string;
  eventName?: string;
  sourceName?: string;
  sourceUrl?: string;
  sourceConfirmed?: boolean;
  ticketTime: string;          // 改为必填
}

// 新增
export const getCalendarToken = () => api.get<unknown, string>("/starline/calendar-token");
export const resetCalendarToken = () => api.post<unknown, string>("/starline/calendar-token/reset");
```

**`frontend/src/pages/StarlineRemindersPage.tsx`** — 见第 5 节。

---

## 3. 新增代码

### 3.1 `StarlineCalendarTokenService`

```java
package com.nageoffer.ai.starlineagent.starline.service;

/**
 * 日历订阅凭证：签发、校验、重置
 * <p>
 * 为什么需要它：日历 App 拉取 .ics 时没有 Cookie/Session，凭证只能放在 URL 里。
 * 这个 token 只对 /starline/calendar.ics 生效，不能用来访问其他接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarlineCalendarTokenService {

    private static final int TOKEN_BYTES = 24;   // Base64URL 后约 32 字符

    private final StarlineCalendarTokenMapper mapper;

    /** 取当前用户的有效 token，没有就签发一个 */
    @Transactional
    public String ensureToken(String userId) {
        StarlineCalendarTokenDO existing = mapper.selectOne(
                Wrappers.<StarlineCalendarTokenDO>lambdaQuery()
                        .eq(StarlineCalendarTokenDO::getUserId, userId)
                        .eq(StarlineCalendarTokenDO::getRevoked, 0)
                        .orderByDesc(StarlineCalendarTokenDO::getCreatedTime)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing.getToken();
        }
        return issue(userId);
    }

    /** 重置：作废旧 token，签发新的。用户发现泄露时用 */
    @Transactional
    public String reset(String userId) {
        mapper.update(null, Wrappers.<StarlineCalendarTokenDO>lambdaUpdate()
                .eq(StarlineCalendarTokenDO::getUserId, userId)
                .eq(StarlineCalendarTokenDO::getRevoked, 0)
                .set(StarlineCalendarTokenDO::getRevoked, 1));
        return issue(userId);
    }

    /**
     * token → userId
     * <p>
     * 返回 null 表示无效，调用方必须当 401 处理，不能降级成「查全部」
     */
    public String resolveUserId(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        StarlineCalendarTokenDO row = mapper.selectOne(
                Wrappers.<StarlineCalendarTokenDO>lambdaQuery()
                        .eq(StarlineCalendarTokenDO::getToken, token)
                        .eq(StarlineCalendarTokenDO::getRevoked, 0)
                        .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        // 记录最后访问时间，便于排查「订阅还在不在」
        mapper.update(null, Wrappers.<StarlineCalendarTokenDO>lambdaUpdate()
                .eq(StarlineCalendarTokenDO::getId, row.getId())
                .set(StarlineCalendarTokenDO::getLastAccessTime, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        return row.getUserId();
    }

    private String issue(String userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        StarlineCalendarTokenDO row = new StarlineCalendarTokenDO();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setUserId(userId);
        row.setToken(token);
        row.setCreatedTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        row.setRevoked(0);
        mapper.insert(row);

        log.info("签发日历订阅凭证, userId={}", userId);
        return token;
    }
}
```

### 3.2 `StarlineIcsBuilder` —— ICS 生成器

**这是整个方案的核心，必须写对。**

```java
package com.nageoffer.ai.starlineagent.starline.service;

/**
 * iCalendar (RFC 5545) 生成器
 * <p>
 * 只支持本场景需要的子集：单事件 + 单个 DISPLAY 闹钟。
 * 不做 RRULE 重复、不做时区数据库（固定 Asia/Shanghai）
 */
public final class StarlineIcsBuilder {

    /** 提前量：用户要求固定提前 10 分钟 */
    private static final int REMINDER_MINUTES_BEFORE = 10;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 事件持续时长：开票提醒是瞬时事件，给 1 小时占位避免日历里显示为零长度 */
    private static final int EVENT_DURATION_HOURS = 1;

    private StarlineIcsBuilder() {
    }

    /**
     * @param events 只传 reminder_status=ACTIVE 且 ticket_time 非空的记录
     */
    public static String build(List<StarlineEventDO> events, String calendarName) {
        StringBuilder sb = new StringBuilder(1024);

        // 强制 CRLF：ICS 规范要求，用 \n 会让部分客户端截断
        sb.append("BEGIN:VCALENDAR\r\n")
          .append("VERSION:2.0\r\n")
          .append("PRODID:-//Starline//Event Reminder//CN\r\n")
          .append("CALSCALE:GREGORIAN\r\n")
          .append("METHOD:PUBLISH\r\n")
          .append("X-WR-CALNAME:").append(escape(calendarName)).append("\r\n")
          .append("X-WR-TIMEZONE:Asia/Shanghai\r\n")
          .append("X-WR-CALDESC:").append(escape("星序演出开票提醒")).append("\r\n");

        for (StarlineEventDO event : events) {
            appendEvent(sb, event);
        }

        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static void appendEvent(StringBuilder sb, StarlineEventDO event) {
        LocalDateTime start = event.getTicketTime();
        if (start == null) {
            // 没有开票时间不进日历；调用方应已过滤，这里再兜一层
            return;
        }
        LocalDateTime end = start.plusHours(EVENT_DURATION_HOURS);

        sb.append("BEGIN:VEVENT\r\n")
          // UID 必须稳定：同一提醒改了内容也用同一个 UID，
          // 这样日历里是「更新」而不是「新增一条」
          .append("UID:starline-reminder-").append(event.getId()).append("@starline\r\n")
          .append("DTSTAMP:").append(utcStamp()).append("\r\n")
          // TZID 不能省：省了会被当成 UTC，提醒会错 8 小时
          .append("DTSTART;TZID=Asia/Shanghai:").append(icalLocal(start)).append("\r\n")
          .append("DTEND;TZID=Asia/Shanghai:").append(icalLocal(end)).append("\r\n")
          .append("SUMMARY:").append(escape(summary(event))).append("\r\n")
          .append("DESCRIPTION:").append(escape(description(event))).append("\r\n");

        if (StrUtil.isNotBlank(event.getSourceUrl())) {
            sb.append("URL:").append(event.getSourceUrl()).append("\r\n");
        }

        // 闹钟：提前 10 分钟
        sb.append("BEGIN:VALARM\r\n")
          .append("ACTION:DISPLAY\r\n")
          .append("TRIGGER:-PT").append(REMINDER_MINUTES_BEFORE).append("M\r\n")
          .append("DESCRIPTION:").append(escape(alertText(event))).append("\r\n")
          .append("END:VALARM\r\n")
          .append("END:VEVENT\r\n");
    }

    private static String summary(StarlineEventDO event) {
        String artist = StrUtil.blankToDefault(event.getArtistName(), "演出");
        String name = StrUtil.blankToDefault(event.getEventName(), "");
        return name.isBlank()
                ? artist + " 开票"
                : artist + " " + name + " 开票";
    }

    private static String alertText(StarlineEventDO event) {
        return summary(event) + "（10 分钟后）";
    }

    private static String description(StarlineEventDO event) {
        StringBuilder sb = new StringBuilder();
        sb.append("艺人：").append(StrUtil.blankToDefault(event.getArtistName(), "-")).append('\n');
        if (StrUtil.isNotBlank(event.getEventName())) {
            sb.append("活动：").append(event.getEventName()).append('\n');
        }
        if (event.getTicketTime() != null) {
            sb.append("开票时间：").append(event.getTicketTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        }
        if (StrUtil.isNotBlank(event.getSourceName())) {
            sb.append("来源：").append(event.getSourceName()).append('\n');
        }
        if (StrUtil.isNotBlank(event.getSourceUrl())) {
            sb.append("链接：").append(event.getSourceUrl()).append('\n');
        }
        sb.append("\n请以官方来源的最新信息为准。");
        return sb.toString();
    }

    /** 本地时间格式：yyyyMMdd'T'HHmmss，不带时区后缀（时区由 TZID 声明） */
    private static String icalLocal(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
    }

    /** DTSTAMP 必须是 UTC，结尾带 Z */
    private static String utcStamp() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
    }

    /**
     * RFC 5545 转义
     * <p>
     * 反斜杠必须第一个转，否则后续插入的 \\ 会被二次转义。
     * 逗号、分号不转义会把 ICS 的一行截断，日历里显示不全
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }
}
```

### 3.3 `StarlineCalendarController`

```java
package com.nageoffer.ai.starlineagent.starline.controller;

@RestController
@RequestMapping("/starline")
@RequiredArgsConstructor
public class StarlineCalendarController {

    private final StarlineCalendarTokenService tokenService;
    private final StarlineEventService eventService;

    /**
     * 用户取自己的日历订阅地址（需要登录）
     */
    @GetMapping("/calendar-token")
    public Result<String> token() {
        String userId = UserContext.requireUser().getUserId();
        return Results.success(tokenService.ensureToken(userId));
    }

    /**
     * 重置订阅地址（需要登录）
     * <p>
     * 用户发现链接泄露、或换设备时用。旧链接立刻失效
     */
    @PostMapping("/calendar-token/reset")
    public Result<String> reset() {
        String userId = UserContext.requireUser().getUserId();
        return Results.success(tokenService.reset(userId));
    }

    /**
     * 日历订阅端点：由日历 App 主动拉取，无登录态
     * <p>
     * 必须放行鉴权（加入 Sa-Token 白名单），身份完全靠 URL 里的 token
     */
    @GetMapping(value = "/calendar.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> calendar(@RequestParam(required = false) String token) {
        String userId = tokenService.resolveUserId(token);
        if (userId == null) {
            // 不能返回空日历：那会让用户以为「提醒都消失了」
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("订阅链接无效或已重置，请回到星序重新获取");
        }

        List<StarlineEventDO> events = eventService.findCalendarEvents(userId);
        String ics = StarlineIcsBuilder.build(events, "星序提醒");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                // inline：浏览器直接显示；用 attachment 会变成下载
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"starline.ics\"")
                // 不能长缓存：用户新加的提醒要能尽快出现
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, max-age=0")
                .body(ics);
    }
}
```

---

## 4. `StarlineEventService` 改造

### 4.1 删除的方法

| 方法 | 原因 |
|---|---|
| `findDue(now)` | 不再轮询 |
| `claim(id)` | 无抢占 |
| `markSent(id, at)` | 无发送 |
| `markFailed(...)` | 无投递失败 |
| `sha256(...)` | 只为 bad case 幂等键服务 |

### 4.2 改造后的完整结构

```java
@Service
@RequiredArgsConstructor
public class StarlineEventService {

    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELLED = "CANCELLED";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StarlineEventMapper mapper;
    private final ObjectProvider<BadCaseIntake> intake;

    public List<StarlineEventVO> listMine() {
        String userId = UserContext.requireUser().getUserId();
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getUserId, userId)
                .orderByDesc(StarlineEventDO::getCreatedTime))
                .stream().map(StarlineEventService::toVO).toList();
    }

    /** 日历端点用：只要 ACTIVE 且有开票时间的 */
    public List<StarlineEventDO> findCalendarEvents(String userId) {
        return mapper.selectList(Wrappers.<StarlineEventDO>lambdaQuery()
                .eq(StarlineEventDO::getUserId, userId)
                .eq(StarlineEventDO::getReminderStatus, ACTIVE)
                .isNotNull(StarlineEventDO::getTicketTime)
                .orderByAsc(StarlineEventDO::getTicketTime));
    }

    @Transactional
    public String create(SaveStarlineEventRequest request) {
        return create(request, StarlineInvocationContext.userForm());
    }

    /**
     * 保留 AGENT_TOOL 上下文的 bad case 上报
     * <p>
     * 这部分逻辑与投递无关，是业务规则违例检测，必须保留
     */
    @Transactional
    public String create(SaveStarlineEventRequest request, StarlineInvocationContext context) {
        try {
            return doCreate(request);
        } catch (StarlineRuleViolationException failure) {
            if (context != null && context.systemGenerated()) {
                reportRuleViolation(request, context, failure);
            }
            throw failure;
        }
    }

    private String doCreate(SaveStarlineEventRequest request) {
        String artistName = required(request.getArtistName(), "ARTIST_REQUIRED", "请填写艺人名称");
        String eventName = required(request.getEventName(), "EVENT_NAME_REQUIRED", "请填写活动名称");
        String sourceName = required(request.getSourceName(), "SOURCE_REQUIRED", "请填写信息来源");
        String sourceUrl = validateUrl(request.getSourceUrl());

        if (!Boolean.TRUE.equals(request.getSourceConfirmed())) {
            throw new StarlineRuleViolationException("SOURCE_NOT_CONFIRMED",
                    "请先确认来源内容与活动信息一致");
        }

        LocalDateTime ticketTime = parseTime(request.getTicketTime(),
                "TICKET_TIME_REQUIRED", "请填写开票时间。没有开票时间的场次无法设置提醒");
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (!ticketTime.isAfter(now)) {
            throw new StarlineRuleViolationException("TICKET_TIME_NOT_FUTURE", "开票时间必须晚于当前时间");
        }

        // 关键校验：开票时间距今不足 10 分钟，提醒来不及
        if (ticketTime.isBefore(now.plusMinutes(10))) {
            throw new StarlineRuleViolationException("TOO_CLOSE_TO_NOTIFY",
                    "开票时间距今不足 10 分钟，赶不上提前提醒了");
        }

        StarlineEventDO event = new StarlineEventDO();
        event.setId(IdUtil.getSnowflakeNextIdStr());
        event.setUserId(UserContext.requireUser().getUserId());
        event.setArtistName(artistName);
        event.setEventName(eventName);
        event.setSourceName(sourceName);
        event.setSourceUrl(sourceUrl);
        event.setTicketTime(ticketTime);
        event.setReminderStatus(ACTIVE);
        event.setCreatedTime(now);
        event.setUpdateTime(now);
        mapper.insert(event);
        return event.getId();
    }

    @Transactional
    public void cancelReminder(String id) {
        int updated = mapper.update(null, Wrappers.<StarlineEventDO>lambdaUpdate()
                .eq(StarlineEventDO::getId, id)
                .eq(StarlineEventDO::getUserId, UserContext.requireUser().getUserId())
                .eq(StarlineEventDO::getReminderStatus, ACTIVE)
                .set(StarlineEventDO::getReminderStatus, CANCELLED)
                .set(StarlineEventDO::getUpdateTime, LocalDateTime.now(ZONE)));
        if (updated == 0) {
            throw new ClientException("提醒不存在或已取消");
        }
    }

    private void reportRuleViolation(SaveStarlineEventRequest request,
                                     StarlineInvocationContext context,
                                     StarlineRuleViolationException failure) {
        try {
            String user = UserContext.requireUser().getUserId();
            String normalized = String.valueOf(request.getArtistName()) + "|"
                    + request.getTicketTime() + "|" + request.getSourceConfirmed();
            String subject = sha256(user + "|" + failure.getRuleCode() + "|" + normalized
                    + "|" + (System.currentTimeMillis() / 3600000));
            BadCaseIntake sink = intake.getIfAvailable();
            if (sink == null) {
                return;
            }
            sink.submitSafely(new BadCaseSignal("STARLINE_BUSINESS", "STARLINE_RULE_VIOLATION",
                    "HIGH", "S3",
                    context.messageId() == null ? "STARLINE_RULE" : "AGENT_MESSAGE",
                    context.messageId() == null ? subject : context.messageId(),
                    context.messageId(), context.conversationId(), user, context.runId(),
                    "rule:" + subject,
                    "星序业务规则违例：" + failure.getRuleCode(), Instant.now(), "starline-rule-v1",
                    Map.of("ruleCode", failure.getRuleCode(),
                            "origin", context.origin().name(),
                            "requestSummary", normalized)));
        } catch (Exception ignored) {
            // 检测失败不能改变原始业务异常
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // required / trim / validateUrl / parseTime / toVO 保留，但 parseTime 的 ruleCode 改名

    private static StarlineEventVO toVO(StarlineEventDO e) {
        return new StarlineEventVO(e.getId(), e.getArtistName(), e.getEventName(),
                e.getSourceName(), e.getSourceUrl(), e.getTicketTime(),
                e.getReminderStatus(), e.getCreatedTime());
    }
}
```

### 4.3 新增的校验：`TOO_CLOSE_TO_NOTIFY`

```java
if (ticketTime.isBefore(now.plusMinutes(10))) {
    throw new StarlineRuleViolationException("TOO_CLOSE_TO_NOTIFY",
            "开票时间距今不足 10 分钟，赶不上提前提醒了");
}
```

**这条必须有。** 否则用户 11:55 设一个 12:00 的提醒，日历订阅最快也要几分钟刷新，**提醒根本不会响**，而用户以为设成功了——这是最糟的体验。

---

## 5. 前端改造

### 5.1 `StarlineRemindersPage.tsx` 改动点

| 位置 | 改动 |
|---|---|
| 第 44 行 | 删 `const [email, setEmail]` |
| 第 68 行 | 请求体删 `remindAt`、`email` |
| 第 71 行 | `"已创建邮箱提醒"` → `"已加入你的日历，开票前 10 分钟提醒"` |
| 第 79 行 | 删 `setEmail("")` |
| 第 114 行 | `邮箱提醒` → `开票提醒` |
| 第 180–181 行 | **删除整个「接收提醒的邮箱」输入项** |
| 第 243 行 | 删 `邮箱：{item.email}` |

**新增：日历订阅卡片（放在页面顶部）**

```tsx
function CalendarSubscribeCard() {
  const [token, setToken] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(true);

  React.useEffect(() => {
    getCalendarToken()
      .then(setToken)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Skeleton className="h-32 w-full" />;

  const url = token
    ? `${window.location.origin}/api/starline/calendar.ics?token=${token}`
    : null;

  const copy = async () => {
    if (!url) return;
    await navigator.clipboard.writeText(url);
    toast.success("订阅链接已复制");
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CalendarDays className="h-4 w-4" />
          日历订阅
        </CardTitle>
        <CardDescription>
          订阅后，所有开票提醒会自动同步到你的手机日历，开票前 10 分钟提醒你。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {url ? (
          <>
            <div className="flex gap-2">
              <Input readOnly value={url} className="font-mono text-xs" />
              <Button onClick={copy} variant="outline">
                <Copy className="h-4 w-4" />
              </Button>
            </div>

            <Tabs defaultValue="ios">
              <TabsList>
                <TabsTrigger value="ios">iPhone</TabsTrigger>
                <TabsTrigger value="android">Android</TabsTrigger>
                <TabsTrigger value="outlook">Outlook</TabsTrigger>
              </TabsList>
              <TabsContent value="ios">
                <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600">
                  <li>复制上方链接</li>
                  <li>打开「设置」→「日历」→「账户」</li>
                  <li>点「添加账户」→「其他」</li>
                  <li>选「添加已订阅的日历」，粘贴链接</li>
                  <li>点「下一步」→「存储」</li>
                </ol>
              </TabsContent>
              <TabsContent value="android">
                <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600">
                  <li>装一个支持订阅的日历（推荐 ICSx⁵，开源免费）</li>
                  <li>打开 ICSx⁵ →「+」→「输入 URL」</li>
                  <li>粘贴上方链接 → 保存</li>
                  <li>在系统日历中勾选这个日历</li>
                </ol>
              </TabsContent>
              <TabsContent value="outlook">
                <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600">
                  <li>网页版 Outlook →「添加日历」</li>
                  <li>选「从 Web 订阅」</li>
                  <li>粘贴链接 → 命名 → 导入</li>
                </ol>
              </TabsContent>
            </Tabs>

            <div className="flex items-center justify-between rounded-md bg-amber-50 p-3 text-xs text-amber-800">
              <span>这个链接等于你的私人凭证，不要分享给别人</span>
              <Button
                variant="ghost"
                size="sm"
                onClick={async () => {
                  if (!confirm("重置后旧链接立刻失效，手机日历需要重新订阅。继续？")) return;
                  const next = await resetCalendarToken();
                  setToken(next);
                  toast.success("已重置，请重新订阅");
                }}
              >
                重置链接
              </Button>
            </div>
          </>
        ) : (
          <p className="text-sm text-slate-500">日历订阅暂不可用，请稍后重试</p>
        )}
      </CardContent>
    </Card>
  );
}
```

### 5.2 表单改动

```tsx
// 删除：接收提醒的邮箱（原 180-181 行）
// 删除：提醒时间（原 remindAt 输入）

// 保留：
//  - 艺人名称
//  - 活动名称
//  - 信息来源
//  - 来源链接
//  - 确认来源勾选
//  - 开票时间（改为必填）

// 新增提示文案（开票时间字段下方）
<p className="text-xs text-slate-500">
  开票前 10 分钟提醒你。场次还没公布开票时间的话，暂时无法设置提醒。
</p>
```

---

## 6. 权限白名单（关键，漏了会 401）

日历 App 拉取 `.ics` 时**没有登录态**，必须把 `/starline/calendar.ics` 加入鉴权白名单。

```java
// 找到 Sa-Token 的拦截器配置（通常在 framework 或 bootstrap）
// 加入 excludePathPatterns
registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
        .addPathPatterns("/**")
        .excludePathPatterns(
                "/starline/calendar.ics",   // ← 新增
                "/agent/v1/meta",
                // ... 其他已有白名单
        );
```

**验证方法**：

```bash
# 不带 Cookie 直接访问，应该返回 ICS 内容而不是 401
curl -i "http://localhost:9090/api/starline/calendar.ics?token=<你的token>"
```

---

## 7. bad case 体系的影响

| 原入口 | 处理 |
|---|---|
| `STARLINE_DELIVERY` / `MAIL_ATTEMPT_FAILED` | **废除**（无邮件） |
| `STARLINE_DELIVERY` / `MAIL_STATUS_PERSIST_FAILED` | **废除** |
| `StarlineBadCaseDetector` 整个类 | **删除** |
| `STARLINE_BUSINESS` / `STARLINE_RULE_VIOLATION` | **保留**（Agent 传不合规参数时仍要检测） |

**新方案的可观测性缺口（必须承认）**：

```
邮件：有 success/fail 返回 → 能统计送达率
日历：完全没有返回 → 不知道用户是否收到
```

**唯一的间接观测**：`t_starline_calendar_token.last_access_time`。

```sql
-- 有多少用户在过去 7 天真的拉过日历
SELECT count(*) FROM t_starline_calendar_token
WHERE revoked = 0 AND last_access_time > now() - interval '7 days';
```

**这个数字能说明「订阅是否还活着」**，但说明不了「提醒有没有响」。后者无法测量，是方案的固有边界。

---

## 8. 验收清单

### 8.1 功能

- [ ] 设置开票时间后，页面上出现日历订阅卡片和链接
- [ ] iPhone 添加订阅后，日历里能看到事件，标题含艺人名与活动名
- [ ] **事件时间是北京时间，不是 UTC**（11-01 12:00 显示为 12:00 而不是 20:00）
- [ ] **闹钟为提前 10 分钟**（11-01 11:50）
- [ ] 事件描述里含来源名称和链接
- [ ] 新增一个提醒后，手机日历刷新能出现（iOS 需等订阅刷新周期）
- [ ] 取消提醒后，过期刷新会从日历消失

### 8.2 校验

- [ ] 开票时间距今不足 10 分钟 → 报「赶不上提前提醒了」
- [ ] 开票时间已过 → 报「开票时间必须晚于当前时间」
- [ ] 未勾选「确认来源」→ 报「请先确认来源内容与活动信息一致」
- [ ] 来源链接非 http/https → 报错

### 8.3 安全

- [ ] 不带 token 访问 `/calendar.ics` → 401，且返回可读提示而不是空日历
- [ ] 用别人的 token → 只能看到自己的提醒
- [ ] 重置 token 后，旧链接立刻 401
- [ ] `/calendar.ics` 已加入鉴权白名单（否则日历 App 拿不到数据）
- [ ] `/starline/calendar-token` 仍需登录（不能匿名拿 token）

### 8.4 清理彻底性

```bash
# 以下都应为 0 结果（除文档与历史迁移脚本）
grep -rn "JavaMailSender\|SimpleMailMessage" --include=*.java .
grep -rn "starline.email\|STARLINE_EMAIL" --include=*.yaml --include=*.yml --include=*.example .
grep -rn "StarlineMailProperties\|StarlineReminderScheduler" --include=*.java .
grep -rn "starline-delivery" --include=*.yaml .
grep -rn "remindAt\|remind_at" --include=*.java --include=*.ts --include=*.tsx .
grep -rn "getEmail\|setEmail" --include=*.java starline/
```

- [ ] `starline/pom.xml` 无 `spring-boot-starter-mail`
- [ ] 应用能启动（重点：删了 mail starter 后不能有 `JavaMailSender` 注入残留）
- [ ] bad case 页面不再出现 `STARLINE_DELIVERY` 来源

---

## 9. 可选方案：支持无开票时间的场次

如果业务上一定要让「已定档但未开票」的演出也能进日历：

```java
// 在 appendEvent 里增加分支
if (event.getTicketTime() == null) {
    if (event.getEventDate() == null) return;   // 两个都没有，跳过

    // 全天事件
    sb.append("BEGIN:VEVENT\r\n")
      .append("UID:starline-reminder-").append(event.getId()).append("@starline\r\n")
      .append("DTSTAMP:").append(utcStamp()).append("\r\n")
      .append("DTSTART;VALUE=DATE:").append(event.getEventDate().format(BASIC_DATE)).append("\r\n")
      .append("SUMMARY:").append(escape(summary(event))).append("\r\n")
      .append("DESCRIPTION:演出时间待定，先标记这一天\\n\r\n")
      // ⚠️ 不设 VALARM：全天事件提前 10 分钟会在前一天 23:50 响
      .append("END:VEVENT\r\n");
}
```

**代价**：需要新增 `event_date` 字段；且这类事件**没有闹钟**，只有日历标记——达不到「提前 10 分钟通知」。

**我不推荐做**：它给用户「已设置提醒」的错觉，实际不会提醒，反而更糟。

---

## 10. 实施顺序

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **1** | 数据库迁移：删字段、改枚举、建 token 表 | 迁移可重复执行；旧数据状态正确归为 ACTIVE |
| **2** | 删 `StarlineMailProperties` / `StarlineReminderScheduler` / `StarlineBadCaseDetector` / mail 依赖 | **应用能启动**（最关键，容易漏 mail starter） |
| **3** | 改 `StarlineEventDO` / VO / Request / Service / Mapper | 编译通过；`/starline/events` 增删查正常 |
| **4** | 新增 `StarlineCalendarTokenService` + `StarlineIcsBuilder` + Controller | `curl` 能拿到合法 ICS |
| **5** | **本地验证 ICS**（见下） | 三个客户端都能正确显示时间和闹钟 |
| **6** | 加鉴权白名单 | 不带 Cookie 能拉到 ICS |
| **7** | 前端改造 | 页面无邮箱字段，订阅卡片可用 |
| **8** | 清理配置与部署文件 | 8.4 的 grep 全为空 |

### 阶段 5 的本地验证方法（最重要）

```bash
# ① 生成 ICS 看内容
curl -s "http://localhost:9090/api/starline/calendar.ics?token=xxx" > /tmp/test.ics

# ② 检查时区与闹钟
grep -E "DTSTART|TRIGGER|TZID" /tmp/test.ics
# 期望：
# DTSTART;TZID=Asia/Shanghai:20261101T120000
# TRIGGER:-PT10M

# ③ 导入 Google 日历（网页版 → 设置 → 导入）
#    看事件时间是否显示为 12:00（如果显示 20:00，说明时区错了）

# ④ 导入系统日历
#    macOS: 双击 .ics 文件
#    Windows: Outlook 打开
```

**第 ③ 步是必做的**——ICS 时区错了本地看不出来，导进 Google 日历才能验证。

---

## 11. 风险清单

| # | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| 1 | **时区错 8 小时** | `DTSTART` 漏了 `;TZID=Asia/Shanghai` | 阶段 5 的 Google 日历导入验证 |
| 2 | **订阅不刷新** | iOS 默认每小时刷新，新增提醒要等 | UI 上说明「最多 1 小时后出现在日历」；急用给「下载 .ics 立即导入」 |
| 3 | **鉴权白名单漏配** | 只加了 Controller 没加 exclude | curl 不带 Cookie 验证 |
| 4 | **删 mail 依赖不彻底** | 只删类没删 pom | 8.4 的 grep + 启动验证 |
| 5 | **旧数据状态非法** | 迁移前存在 `SENDING` | 迁移脚本先 UPDATE 再删字段 |
| 6 | **token 泄露** | 用户截图分享 | 提供「重置链接」按钮；文档说明不要分享 |
| 7 | **无开票时间的场次** | 用户想给「已定档未开票」设提醒 | UI 明确提示不支持，不造假时间 |
| 8 | **距开票不足 10 分钟** | 用户临时设提醒 | `TOO_CLOSE_TO_NOTIFY` 前置拦截 |

---

## 12. 工作量

| 部分 | 预估 |
|---|---|
| 数据库迁移 | 0.5 小时 |
| 删代码 + 依赖 + 配置 | 1 小时（含启动验证） |
| DO / VO / Request / Service 改造 | 2 小时 |
| Token 服务 + ICS 生成 + Controller | 3 小时 |
| 本地 ICS 验证（三端） | 1.5 小时 |
| 鉴权白名单 | 0.5 小时 |
| 前端改造 + 订阅卡片 | 3 小时 |
| 清理验证 + 回归 | 1.5 小时 |
| **合计** | **约 1.5 天** |

---

## 13. 一句话总结

> **这次改造的本质不是「换个通知渠道」，而是「把到点提醒的责任从服务器转移到用户设备」。**
>
> 换来的是：零资质、零成本、零外部依赖、不占定时任务。
> 付出的是：**看不见送达**，以及**订阅刷新有延迟**。
>
> 用户只有你自己时，这两个代价都可以接受。等有真实用户、需要「承诺送达」时，再考虑加邮件或微信作为并行通道——**`channel` 字段的抽象那时再加也不迟，现在不要为它预留**。
