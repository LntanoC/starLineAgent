# 服务器 Docker 配置快照

这里保存 StarLineAgent 服务器当前运行使用的 Compose 文件和相邻挂载配置。配置来源是服务器上的文件，不包含服务器的 .env、应用 JAR 或前端构建产物。

## Stack 对应关系

- starline：compose.yaml 与 pgvector.override.yaml。前者定义 PostgreSQL 和 Redis，后者把 PostgreSQL 镜像替换为带 pgvector 的版本。
- starline-20261022：starline-app.compose.yaml。定义 API、Web、RocketMQ 和 RustFS。
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
