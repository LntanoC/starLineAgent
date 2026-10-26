# 星序活动与日历提醒

本业务切片记录用户关注的演出活动。开票时间为空表示“待公布”，活动仍保存在列表中，但不会进入日历；只有已核验来源且有准确开票时间的活动，才会进入 iCal 订阅。

## 提醒规则

- 开票时间采用北京时间（Asia/Shanghai），提醒固定在开票前 10 分钟。
- 用户需确认来源内容与艺人、活动、开票时间一致；来源链接只接受 HTTP 或 HTTPS。
- 开票时间必须晚于当前时间，并至少相隔 10 分钟，否则不允许创建日历提醒。
- 用户取消后，活动状态变成 CANCELLED；日历源保留同 UID 的取消事件且不再包含闹钟，客户端同步后停止提醒。
- 开票时间经核验发生变化时，先取消旧记录再创建新记录；日历客户端按自己的同步周期同步，星序不能确认设备是否实际响铃。

## 数据库

全新数据库使用 resources/database/schema_pg.sql。已有数据库保留历史迁移，按序完成 V001、V002、20261026_starline_ical.sql 和 20261026_starline_whitelist_admin_control.sql。日历迁移会保留活动记录和取消状态，清除邮件投递字段，并建立日历订阅凭证表。

## 官方来源白名单

- 管理员在“官方来源白名单”页面新增、编辑、启用或停用平台域名；保存后立即影响下一次演出查询，无需评审或重启服务。
- 新增且标记为可公开访问的来源默认启用；不可公开访问的来源默认停用。编辑启用来源时更改域名立即生效；停用来源会立即从查询白名单中移除。
- 查询服务每次调用从业务 API 读取当前启用列表，并按 URL 主机名精确匹配或匹配其子域名。白名单外的搜索结果直接丢弃；读取列表失败时查询失败关闭，不回退到静态域名。MCP 使用仅供容器内网访问的只读接口；Nginx 对公网请求返回 404。
- 已有数据库先执行 `resources/database/migrations/20261026_starline_whitelist_admin_control.sql`。历史来源中原 `APPROVED` 转为启用，其余旧状态转为停用。

## API

- GET /api/starlineagent/starline/events：查询当前用户活动。
- POST /api/starlineagent/starline/events：创建活动；缺少 ticketTime 时记录为待公布。
- DELETE /api/starlineagent/starline/events/{id}/reminder：取消当前用户的活动或提醒。
- GET /api/starlineagent/starline/calendar-token：登录后获取当前用户订阅凭证。
- POST /api/starlineagent/starline/calendar-token/reset：登录后立即撤销旧链接并签发新链接。
- GET /api/starlineagent/starline/calendar.ics?token=...：日历客户端只读订阅地址；凭证只授权读取该用户的星序日历。

订阅链接等同私人凭证。生产环境应通过 HTTPS 访问，并避免在截图或公开消息中分享链接。
