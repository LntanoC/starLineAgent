# 服务器 Docker 配置快照

这里保存 StarLineAgent 服务器当前运行使用的 Compose 文件和相邻挂载配置。配置来源是服务器上的文件，不包含服务器的 .env、应用 JAR、前端构建产物或运行日志。

## Stack 对应关系

- starline：compose.yaml 与 pgvector.override.yaml。前者定义 PostgreSQL 和 Redis，后者把 PostgreSQL 镜像替换为带 pgvector 的版本。
- starline-20261022：starline-app.compose.yaml。定义 API、Web、RocketMQ 和 RustFS。
- `starline-mcp`：由应用 Compose 管理的独立 MCP Server 容器，使用 `mcp-server.jar`，只加入 Docker 内部网络，不发布宿主机端口；YUANBAO_SEARCH_API_KEY 留空时星序演出查询工具不会注册，YDC_API_KEY 仅用于可选的通用 You.com 搜索工具。
- rocketmq-broker.conf 与 nginx.conf：由应用 Compose 只读挂载的附属配置。

应用 Stack 使用外部 Docker 网络 starline_default；先创建 starline Stack 后，应用 Stack 才能接入。配置还引用同目录的 starline.jar 和 frontend-dist/，这两项是发布构建产物，不在本目录中。

## 环境变量

.env.example 列出 Compose 所需变量名称。复制后填写本机或目标服务器自己的值：

```powershell
Copy-Item resources/docker/server/.env.example resources/docker/server/.env
```

.env 已通过本目录的 .gitignore 排除。不要将真实密码、对象存储密钥或模型 API Key 提交到 Git。

## 从服务器刷新快照

从项目根目录的 PowerShell 执行：

```powershell
.\scripts\sync-server-docker-config.ps1
```

脚本用 SSH 密钥只读拉取服务器文件，并在下载成功后替换本地快照。每次同步都会先把 SCP 命令写入 docs/server-operations.md，完成后补记结果，并更新 docs/change-log/YYYY-MM-DD.md。它不会读取 .env，也不会重启或修改服务器容器。

服务器发布目录变化时，可传入对应发布目录名：

```powershell
.\scripts\sync-server-docker-config.ps1 -Release starline-YYYYMMDD
```

同步方向为服务器到本地项目；本地修改配置后，需要另行部署到服务器，再刷新本地快照。

## 重新部署前需要准备

服务器当前发布目录还包含 starline.jar、frontend-dist/、.env。Compose 之外还要求 starline_default 网络由 starline Stack 创建。上述运行数据和凭据不包含在此快照中。部署时应先准备这些文件与变量，再通过 Portainer 的 Stacks 或 Docker Compose 启动。

应用 Compose 将发布目录的 `logs/` 挂载到 API 容器 `/app/logs`。应用日志文件最大 100 MB，保留 7 天且总量上限为 1 GB；`logs/` 是运行数据，已加入 `.gitignore`。


## 发布版本配置快照

resources/docker/server/releases/starline-20261022/ 保存该发布版本的 Compose、Nginx/RocketMQ 配置及数据库 SQL。schema_pg.sql 保留服务器发布版结构；init_data_pg.sql 经项目适配后初始化星序助手人设和提示词。

发布版 schema_pg.sql 与项目当前维护的 resources/database/schema_pg.sql 不同，分别保留，避免版本混淆。两处 init_data_pg.sql 保持同步：初始化星序助手人设和提示词，不写入固定默认管理员凭据；管理员账号需单独安全初始化。服务器 .env、应用 JAR、frontend-dist 和数据库内容不在归档中。


## 临时公网访问

`starline-web` 的 Compose 端口映射为 `80:80`，可通过服务器公网 IP 的 HTTP 80 端口访问前端；当前 Nginx 在容器内继续代理 `/api/` 到 API 服务。腾讯云安全组和主机防火墙需允许 TCP 80。应用 API 的宿主机 9090 仍绑定 `127.0.0.1`，数据库、Redis、RustFS、RocketMQ 和 Portainer 不应通过公网直接暴露。此 IP + HTTP 方式用于临时演示；正式使用应配置域名、备案（如适用）和 HTTPS。
访问示例：http://<服务器公网 IP>/starline/reminders。


## 2026-10-25 Bad Case 统一候选池

现行 release 快照包含 `20261025_bad_case_candidate_pool.sql` 和 iCal 迁移 `20261026_starline_ical.sql`；旧的 `20261025_starline_delivery_failure.sql` 仅保留为历史文件，不得在新部署中运行。
`application.yaml`为服务器最终配置，模式值显式带引号；四类高置信入口ON，行为/抽样OFF，自动评审和告警false。新一轮全量部署先在临时配置将四类入口置SHADOW，联调后再ON。
`deploy-badcase-candidate-pool.sh`负责备份、迁移和整体切换，`verify-badcase-candidate-pool.py`负责在线断言；`deploy-badcase-mode-hardening.sh`记录本次最终模式容错切换。完整命令和结果见 `docs/server-operations/2026-10-25.md`。
不保存.env、密钥、数据库转储或运行数据。

## 元宝搜索 API Key

星序演出查询 MCP 使用腾讯云元宝搜索。将控制台创建的服务 API KEY 放入服务器 /home/ubuntu/starline-infra/.env：

~~~dotenv
YUANBAO_SEARCH_API_KEY=在腾讯云控制台创建的服务密钥
~~~

不要把真实密钥提交到 Git、粘贴到聊天或写入操作日志。保存 .env 后，仅重建 MCP 容器以加载新环境变量：

~~~sh
cd /home/ubuntu/starline-infra/releases/starline-20261022
sudo docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f starline-app.compose.yaml up -d --no-deps --force-recreate starline-mcp
~~~

YUANBAO_SEARCH_API_KEY 未配置时，MCP 不注册 search_artist_events 工具。
