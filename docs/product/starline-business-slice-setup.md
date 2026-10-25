# 星序活动与日历提醒

本业务切片记录用户关注的演出活动。开票时间为空表示“待公布”，活动仍保存在列表中，但不会进入日历；只有已核验来源且有准确开票时间的活动，才会进入 iCal 订阅。

## 提醒规则

- 开票时间采用北京时间（Asia/Shanghai），提醒固定在开票前 10 分钟。
- 用户需确认来源内容与艺人、活动、开票时间一致；来源链接只接受 HTTP 或 HTTPS。
- 开票时间必须晚于当前时间，并至少相隔 10 分钟，否则不允许创建日历提醒。
- 用户取消后，活动状态变成 CANCELLED；日历源保留同 UID 的取消事件且不再包含闹钟，客户端同步后停止提醒。
- 开票时间经核验发生变化时，先取消旧记录再创建新记录；日历客户端按自己的同步周期同步，星序不能确认设备是否实际响铃。

## 数据库

全新数据库使用 resources/database/schema_pg.sql。已有数据库保留历史迁移，按序完成 V001、V002 和 20261026_starline_ical.sql。新迁移会保留活动记录和取消状态，清除邮件投递字段，并建立日历订阅凭证表。

## API

- GET /api/starlineagent/starline/events：查询当前用户活动。
- POST /api/starlineagent/starline/events：创建活动；缺少 ticketTime 时记录为待公布。
- DELETE /api/starlineagent/starline/events/{id}/reminder：取消当前用户的活动或提醒。
- GET /api/starlineagent/starline/calendar-token：登录后获取当前用户订阅凭证。
- POST /api/starlineagent/starline/calendar-token/reset：登录后立即撤销旧链接并签发新链接。
- GET /api/starlineagent/starline/calendar.ics?token=...：日历客户端只读订阅地址；凭证只授权读取该用户的星序日历。

订阅链接等同私人凭证。生产环境应通过 HTTPS 访问，并避免在截图或公开消息中分享链接。
