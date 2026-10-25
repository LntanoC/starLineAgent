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
2. 在服务器环境文件中设置基础设施与模型凭证；敏感值不写入 Git。日历订阅凭证由应用按用户随机签发。
3. 在空的 starline 数据库执行 schema_pg.sql，再执行 init_data_pg.sql 初始化星序助手人设与提示词。该数据脚本不创建管理员账号；管理员需通过单独的安全流程初始化。
4. 启动 Compose 中的 RustFS 与 RocketMQ；等 NameServer、Broker 健康后，再启动 API 和 Nginx。
5. 检查容器状态与本地回环端口 `8080`、`9090`。日历链接等同私人凭证，生产环境应通过 HTTPS 访问；未配置 DeepSeek/Bailian Key 时 Agent 的模型调用不可用。未创建管理员账号前，登录和需要身份认证的业务操作不可用。

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

应用 Compose 已增加独立 `starline-mcp` 容器，服务不暴露宿主机端口，不再依赖比特严选业务库。演出查询工具 search_artist_events 使用腾讯元宝搜索，依赖 YUANBAO_SEARCH_API_KEY，未配置时不会注册；YDC_API_KEY 仅用于可选的通用 You.com 搜索工具。为避免在旧意图树仍引用电商工具时产生不可用工具，API 的 `agent.mcp.servers` 仍保持为空；完成星序初始化并配置搜索 Key 后，再单独启用 MCP client。

