# 星序首个业务切片：活动信息与邮箱提醒

本切片在现有登录体系下提供活动信息录入、来源确认、待公布状态展示、提醒创建和取消。

## 当前边界

- 本迭代由用户录入公开信息和来源链接；没有接入演出信息检索 Agent，不会生成或猜测艺人活动数据。
- “待公布”记录不创建提醒。
- 已公布记录必须确认来源，并提供未来的准确开票时间、提醒时间和邮箱。
- 到达提醒时间后，后端轮询待发送记录并发送邮件。邮件配置默认关闭。

## 数据库

首次部署按顺序执行 resources/database/migrations/V001__starline_event_reminders.sql 与 V002__starline_platform_sources.sql。两个脚本均可重复执行。

## 邮件配置

通过部署环境变量配置，不要把凭据写入配置文件或 Git：

- STARLINE_EMAIL_ENABLED=true
- STARLINE_EMAIL_FROM：发件邮箱
- SPRING_MAIL_HOST、SPRING_MAIL_PORT
- SPRING_MAIL_USERNAME、SPRING_MAIL_PASSWORD
- 可选：SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
- 可选：SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true

未启用或未配置邮件时，提醒记录仍可创建、查询和取消；不会发送邮件。

## API

应用已有 context path /api/starlineagent：

- GET /api/starlineagent/starline/events：当前登录用户的活动与提醒
- POST /api/starlineagent/starline/events：创建待公布记录或已确认来源的邮箱提醒
- DELETE /api/starlineagent/starline/events/{id}/reminder：取消仍处于待发送状态的提醒

所有接口按当前登录用户隔离数据。

## 官方来源平台清单评审

管理员可在 /admin/starline-sources 登记平台级候选域名和调研结论，分别记录产品与研发评审。只有两项评审均通过的来源才会进入可供后续查询适配器使用的已审批清单。任一调研字段修改后需重新评审。

- 页面可访问性、活动/场次字段与开票时间/状态字段可得性
- 字段稳定性、官方身份依据、更新频率
- 访问或授权边界

评审 API（当前应用 context path 为 /api/starlineagent）：

- GET /api/starlineagent/starline/admin/platform-sources
- POST /api/starlineagent/starline/admin/platform-sources
- PUT /api/starlineagent/starline/admin/platform-sources/{id}
- POST /api/starlineagent/starline/admin/platform-sources/{id}/reviews
