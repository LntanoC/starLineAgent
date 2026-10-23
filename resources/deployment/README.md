# Starline MVP 单机部署

此部署包供腾讯云 Ubuntu 22.04 单机环境使用，复用 `/home/ubuntu/starline-infra` 中已有的 PostgreSQL、Redis 与 Docker 网络 `starline_default`。本 Compose 启动 RustFS、RocketMQ NameServer/Broker、Spring Boot API 和 Nginx 前端。数据库、缓存、对象存储、消息队列和应用端口都不发布到公网；网页和 API 仅绑定服务器回环地址，通过 SSH 隧道访问。

## 本机打包

在项目根目录执行：

```powershell
.\mvnw.cmd -pl bootstrap -am package -DskipTests
Set-Location frontend
npm run build
```

将 `bootstrap/target` 中可执行 Spring Boot JAR 复制为服务器端 `starline.jar`；将 `frontend/dist` 复制为 `frontend-dist`；并将本目录下的 `starline-app.compose.yaml`、`pgvector.override.yaml`、`nginx.conf`、`rocketmq-broker.conf` 与 `resources/database/schema_pg.sql`、`resources/database/init_data_pg.sql` 放到服务器 `/home/ubuntu/starline-infra/releases/<release>/`。

## 服务器初始化与启动

1. 先备份现有 `starline` 数据库，再用 `pgvector/pgvector:pg16` 覆盖原 PostgreSQL 16 镜像；保留原 Docker 数据卷。数据库只绑定回环地址，不改变公网规则。
2. 在服务器 `.env` 中生成并设置 `RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`；保留现有 `POSTGRES_PASSWORD`、`REDIS_PASSWORD`。文件权限设为 `600`。`DEEPSEEK_API_KEY`、`BAILIAN_API_KEY` 与邮箱 SMTP 参数可通过后续运维配置，不要写入 Git。
3. 在空的 `starline` 数据库执行 `schema_pg.sql`。`init_data_pg.sql` 含弱默认管理员凭据，本部署流程不会导入该种子；须先为管理员选定安全的初始化凭据流程，再初始化账号。
4. 启动 Compose 中的 RustFS 与 RocketMQ；等 NameServer、Broker 健康后，再启动 API 和 Nginx。
5. 检查容器状态与本地回环端口 `8080`、`9090`。SMTP 未配置时 `STARLINE_EMAIL_ENABLED` 保持 `false`；未配置 DeepSeek/Bailian Key 时 Agent 的模型调用不可用。未创建管理员账号前，登录和需要身份认证的业务操作不可用。

常用 Compose 形式（从基础设施目录执行，`<release>` 替换成发布目录）：

```bash
sudo docker compose -p starline --env-file .env -f compose.yaml -f releases/<release>/pgvector.override.yaml up -d postgres
sudo docker compose --env-file .env -f releases/<release>/starline-app.compose.yaml up -d starline-rustfs starline-rocketmq-namesrv starline-rocketmq-broker
sudo docker compose --env-file .env -f releases/<release>/starline-app.compose.yaml up -d starline-api starline-web
sudo docker compose --env-file .env -f releases/<release>/starline-app.compose.yaml ps
```

本机 SSH 隧道示例：

```powershell
ssh -N -L 8080:127.0.0.1:8080 -L 15432:127.0.0.1:5432 ubuntu@139.199.191.109
```

隧道建立后，浏览器访问 `http://127.0.0.1:8080`；DBeaver 可连接本机 `127.0.0.1:15432`。

本部署将 `agent.mcp.servers` 设为空：仓库当前 `mcp-server` 提供的是比特严选工具并依赖独立业务库，不属于星序 MVP 活动查询工具。后续星序确有 MCP 工具时，再增加星序专用 MCP 服务和数据库。

