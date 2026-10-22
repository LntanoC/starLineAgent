# 服务器操作日志

本日志记录 Codex 在 Tencent Cloud 服务器 `139.199.191.109` 上执行的命令。每次执行服务器命令前先追加准备执行的命令；执行后补充时间、结果和异常。命令涉及密码时只记录变量名与文件位置，不记录密钥值。

## 2026-10-21：部署 PostgreSQL 和 Redis

服务器：`ubuntu@139.199.191.109`（Ubuntu 22.04.5 LTS，主机名 `VM-0-17-ubuntu`）
项目目录：`/home/ubuntu/starline-infra`

### 本地 SSH 命令

```powershell
ssh -o BatchMode=yes -o ConnectTimeout=10 ubuntu@139.199.191.109 'set -eu; cd /home/ubuntu/starline-infra; sudo -n docker compose pull; sudo -n docker compose up -d; sudo -n docker compose ps'
```

### 服务器上执行的操作

```bash
# 配置腾讯云内网 Docker 镜像并重启 Docker 服务
sudo install -d -m 0755 /etc/docker
sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
sudo docker info

# 部署配置检查、拉取镜像和启动服务
cd /home/ubuntu/starline-infra
sudo docker compose config --quiet
sudo docker compose pull
sudo docker compose up -d
sudo docker compose ps

# 验证服务健康、端口绑定与凭据文件权限
sudo docker inspect -f '{{.Name}} {{.State.Health.Status}} {{json .NetworkSettings.Ports}}' starline-postgres starline-redis
sudo docker exec starline-postgres pg_isready -U starline -d starline
sudo docker exec starline-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
stat -c '%a %n' /home/ubuntu/starline-infra /home/ubuntu/starline-infra/.env /home/ubuntu/starline-infra/compose.yaml
```

### 结果

- Docker 镜像拉取成功，`postgres:16.15-bookworm` 与 `redis:7.4.11-bookworm` 已启动。
- `starline-postgres` 和 `starline-redis` 均为 `healthy`。
- PostgreSQL `5432` 与 Redis `6379` 均只绑定服务器回环地址 `127.0.0.1`。
- PostgreSQL `pg_isready` 返回 `accepting connections`。
- `.env` 权限为 `600`，项目目录权限为 `700`；未把密码值记入本文件。
- 数据持久化在 Docker 命名卷 `starline_postgres_data` 和 `starline_redis_data`。
