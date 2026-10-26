# 星序 Starline：从本地源码到服务器的快速部署手册

> 适用范围：当前项目结构、Ubuntu 22.04/24.04、Docker Compose 单机部署。
> 维护日期：2026-10-26。配置以 resources/docker/server/ 和当前 release 快照为准。

## 1. 部署结构与端口

项目使用两个 Compose 项目：

| Compose 项目 | 配置 | 组件 | 用途 |
| --- | --- | --- | --- |
| starline | resources/docker/server/compose.yaml + release 中的 pgvector.override.yaml | PostgreSQL、Redis | 基础数据服务，并创建内部网络 starline_default |
| starline-20261022 | resources/docker/server/releases/starline-20261022/starline-app.compose.yaml | RustFS、RocketMQ、MCP、API、Nginx Web | 星序应用服务 |

网页通过 TCP 80 访问。API 的宿主机端口 9090 只绑定 127.0.0.1。PostgreSQL 5432 和 Redis 6379 也只绑定回环地址；MCP、RustFS、RocketMQ 只在 Docker 内部网络通信。

## 2. 准备软件与安全组

本地构建机需要：

- JDK 17
- Node.js 与 npm（建议使用当前 Node.js LTS）
- Git
- Windows 自带的 ssh.exe / scp.exe，或 WinSCP

服务器需要 Ubuntu 22.04/24.04、Docker Engine 和 Docker Compose 插件。请按 [Docker 官方 Ubuntu 安装文档](https://docs.docker.com/engine/install/ubuntu/)安装并验证 docker compose version。

腾讯云安全组建议只开放：

- TCP 22：SSH；来源尽量限制为你自己的公网 IP
- TCP 80：需要公开访问网页时开放
- TCP 443：配置 HTTPS 后开放

不要向公网开放 5432、6379、9090、9099、9000、RocketMQ 端口或 Portainer 端口。Docker 发布的端口可能绕过常见主机防火墙规则，仍需在腾讯云安全组与主机网络策略两侧核对。

## 3. 本地构建发布文件

在项目根目录的 PowerShell 执行：

~~~powershell
.\mvnw.cmd -pl bootstrap,mcp-server -am package -DskipTests
Push-Location .\frontend
npm ci
npm run build
Pop-Location
~~~

构建完成后，API 与 MCP JAR 分别位于：

- bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
- mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar

前端静态文件位于 frontend/dist/。服务器运行 Java 17 JRE，不需要在服务器上安装 JDK 或 Node.js。

如果只改了某个模块，也应按当前 Maven reactor 要求保留 -am，避免漏掉依赖模块。部署前确认 Maven 和前端构建都成功。

## 4. 准备服务器目录和发布文件

以下用 /home/ubuntu/starline-infra 作为基础设施目录，release 名称示例为 starline-20261022。新版本可使用新的日期版本目录；不要覆盖正在运行的 release。

服务器目录约定：

~~~text
/home/ubuntu/starline-infra/
├── .env
├── compose.yaml
├── .env.example
└── releases/
    └── starline-20261022/
        ├── starline-app.compose.yaml
        ├── pgvector.override.yaml
        ├── application.yaml
        ├── nginx.conf
        ├── rocketmq-broker.conf
        ├── starline.jar
        ├── mcp-server.jar
        ├── frontend-dist/
        ├── schema_pg.sql
        └── init_data_pg.sql
~~~

把本地构建产物与配置文件传到对应位置：

- resources/docker/server/compose.yaml、.env.example 放在 starline-infra/
- resources/docker/server/releases/starline-20261022/ 中当前使用的 Compose、pgvector.override.yaml、application.yaml、Nginx、RocketMQ 和 SQL 文件放入 release 目录
- 两个 JAR 分别复制并改名为 starline.jar、mcp-server.jar
- frontend/dist/ 中的内容放入 release 的 frontend-dist/，不要多套一层 dist/

可用 WinSCP 上传，也可在 Windows PowerShell 里用 SSH/SCP。命令中的 SERVER_IP 与 RELEASE 替换为你的服务器地址和 release 目录名：

~~~powershell
ssh ubuntu@SERVER_IP "mkdir -p /home/ubuntu/starline-infra/releases/RELEASE/frontend-dist"
scp .\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar ubuntu@SERVER_IP:/home/ubuntu/starline-infra/releases/RELEASE/starline.jar
scp .\mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar ubuntu@SERVER_IP:/home/ubuntu/starline-infra/releases/RELEASE/mcp-server.jar
scp -r .\frontend\dist\* ubuntu@SERVER_IP:/home/ubuntu/starline-infra/releases/RELEASE/frontend-dist/
~~~

首次部署还要上传上述 Compose/配置/SQL 文件。升级已有环境时不要用全量初始化 SQL 代替数据库迁移。

## 5. 配置环境变量

将 .env.example 复制为 /home/ubuntu/starline-infra/.env，设置仅 ubuntu 用户可读：

~~~bash
cd /home/ubuntu/starline-infra
cp .env.example .env
chmod 600 .env
nano .env
~~~

填写变量：

| 变量 | 是否必需 | 用途 |
| --- | --- | --- |
| POSTGRES_PASSWORD | 必需 | PostgreSQL 与 API 连接密码 |
| REDIS_PASSWORD | 必需 | Redis 与 API 连接密码 |
| RUSTFS_ACCESS_KEY、RUSTFS_SECRET_KEY | 必需 | RustFS 对象存储访问凭证 |
| DEEPSEEK_API_KEY 或 BAILIAN_API_KEY | 使用对应模型时必需 | Agent 模型调用凭证；按 application.yaml 的模型配置选择 |
| YUANBAO_SEARCH_API_KEY | 演出联网查询必需 | MCP search_artist_events 元宝搜索凭证 |
| YDC_API_KEY | 可选 | 通用 You.com 搜索工具凭证 |

为数据库、Redis、RustFS 分别生成不同的强随机凭证。真实 .env 只放服务器，不上传 Git，不放入变更记录或截图。改了 .env 后，必须重建读取该变量的容器，单纯重启不会更新容器环境。

## 6. 启动基础设施

先确认服务器文件已上传，随后启动 PostgreSQL 和 Redis：

~~~bash
INFRA=/home/ubuntu/starline-infra
RELEASE=$INFRA/releases/starline-20261022

sudo docker compose \
  --project-directory "$INFRA" \
  --env-file "$INFRA/.env" \
  -p starline \
  -f "$INFRA/compose.yaml" \
  -f "$RELEASE/pgvector.override.yaml" \
  up -d

sudo docker compose \
  --project-directory "$INFRA" \
  --env-file "$INFRA/.env" \
  -p starline \
  -f "$INFRA/compose.yaml" \
  -f "$RELEASE/pgvector.override.yaml" \
  ps
~~~

等 PostgreSQL 与 Redis 变为 healthy 后再继续。基础设施 Compose 会创建应用 Compose 所需的 starline_default 网络。

## 7. 初始化数据库（仅限全新空库）

**只对全新空数据库执行一次。** 这两条 SQL 不是线上升级命令；不要对已有业务数据库重跑。

在服务器上执行：

~~~bash
RELEASE=/home/ubuntu/starline-infra/releases/starline-20261022

sudo docker exec -i starline-postgres \
  psql -v ON_ERROR_STOP=1 -U starline -d starline \
  < "$RELEASE/schema_pg.sql"

sudo docker exec -i starline-postgres \
  psql -v ON_ERROR_STOP=1 -U starline -d starline \
  < "$RELEASE/init_data_pg.sql"
~~~

schema_pg.sql 创建当前完整表结构；init_data_pg.sql 写入初始业务配置、Agent 提示和首期官方来源白名单。执行前再次核实目标数据库为空，并保留恢复方案。已有环境按 docs/database/postgres.md 的迁移顺序逐个升级，先备份，禁止用全量 SQL 覆盖数据。

## 8. 启动应用栈

确认 .env、数据库、Redis 和 starline_default 网络就绪后，启动应用 Compose：

~~~bash
INFRA=/home/ubuntu/starline-infra
RELEASE=$INFRA/releases/starline-20261022

sudo docker compose \
  --project-directory "$RELEASE" \
  --env-file "$INFRA/.env" \
  -p starline-20261022 \
  -f "$RELEASE/starline-app.compose.yaml" \
  up -d
~~~

首次启动会下载镜像并启动 RustFS、RocketMQ NameServer/Broker、MCP、API 与 Web。可用以下命令观察状态和日志：

~~~bash
sudo docker compose --project-directory "$RELEASE" --env-file "$INFRA/.env" \
  -p starline-20261022 -f "$RELEASE/starline-app.compose.yaml" ps

sudo docker logs --tail 200 starline-api
sudo docker logs --tail 200 starline-mcp
sudo docker logs --tail 200 starline-web
~~~

## 9. 验证网页和服务

~~~bash
curl -I --max-time 10 http://127.0.0.1/
curl -fsS --max-time 10 http://127.0.0.1:9090/api/starlineagent/agent/v1/meta
sudo docker inspect starline-postgres starline-redis starline-mcp starline-api starline-web \
  --format '{{.Name}} {{.State.Status}}'
~~~

公网访问示例：http://SERVER_IP/starline/reminders。若服务器本机检查通过但外网打不开，检查腾讯云安全组 TCP 80、主机网络策略与 Nginx 容器状态。生产使用应配置域名和 HTTPS。

DBeaver 通过 SSH 隧道连接数据库，不要把 PostgreSQL 端口公开到公网：

- SSH 主机：SERVER_IP，用户：ubuntu
- 本地转发端口：15432 → 服务器 127.0.0.1:5432
- DBeaver 主机：127.0.0.1，端口：15432
- 数据库：starline，用户：starline，密码读取服务器 .env

SSH 隧道命令：

~~~powershell
ssh -N -L 15432:127.0.0.1:5432 ubuntu@SERVER_IP
~~~

## 10. 首次管理员与 Agent 业务初始化

数据库全量 SQL 不创建管理员账号。初始化器模板使用已有的 admin 管理员登录，并通过 STARLINEAGENT_ADMIN_PASSWORD 注入密码；它不是管理员账号创建器。首次部署前需要先通过项目认可的安全流程创建强口令管理员，不能依赖前端输入框里的默认值。

完成管理员账号配置后，再阅读 docs/initialization/README.md 与 docs/initialization/starline-events.md，按模板初始化 Agent 知识库、意图树和 MCP 工具。完整 InitializeMain 会先执行清理流程；只应在全新库或明确确认清理范围、完成备份后运行。当前仓库没有通用的一键首次管理员引导流程，因此部署完成不等于登录与 Agent 数据初始化已经完成。

## 11. 版本升级、备份与回滚

升级已有环境时：

1. 先备份数据库和当前 release 文件。
2. 在新的日期目录准备 JAR、前端和新配置，保留当前 release 作为回滚点。
3. 按 docs/database/postgres.md 选择并按顺序执行未应用的迁移脚本；不要再次执行全量 schema/init SQL。
4. 只重建受影响容器，检查容器状态、API meta、首页和关键业务路径。
5. 探针失败时恢复数据库/文件备份并启动上一个 release。

查看 Compose 状态和近期日志：

~~~bash
sudo docker compose --project-directory /home/ubuntu/starline-infra/releases/starline-20261022 \
  --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 \
  -f /home/ubuntu/starline-infra/releases/starline-20261022/starline-app.compose.yaml ps
~~~

本文档描述的是当前项目配置快照。更新 Compose、SQL 或外置应用配置后，请同步维护 resources/docker/server/ 的本地快照与本手册；不把服务器 .env、密钥、数据库转储、JAR、构建产物或运行日志放入 Git。