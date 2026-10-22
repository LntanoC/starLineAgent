# 星序首个业务切片：活动信息与邮箱提醒

本切片在现有登录体系下提供活动信息录入、来源确认、待公布状态展示、提醒创建和取消。

## 当前边界

- 本迭代由用户录入公开信息和来源链接；没有接入演出信息检索 Agent，不会生成或猜测艺人活动数据。
- “待公布”记录不创建提醒。
- 已公布记录必须确认来源，并提供未来的准确开票时间、提醒时间和邮箱。
- 到达提醒时间后，后端轮询待发送记录并发送邮件。邮件配置默认关闭。

## 数据库

首次部署执行 resources/database/migrations/V001__starline_event_reminders.sql。该脚本可重复执行。

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