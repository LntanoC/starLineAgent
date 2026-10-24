# starlineAgent 项目长期记忆

## 用户偏好与规则

### 只读原则（最高优先级）

- **不修改项目文件**：不要修改 starlineAgent 项目内的任何文件（含 `.workbuddy-ai/` 以外的一切源码、配置、文档）。仅做查看、分析、咨询，实际编码与文件修改交给 Codex。
- **不修改服务器文件**：不要对 `ubuntu@139.199.191.109` 做任何写操作——不得删除/移动/修改文件、不得 `docker stop/restart/rm`、不得 `chmod/chown`、不得导入 SQL、不得改配置或安全组。**先提出建议，由用户或 Codex 执行**。
- 发现服务器上的遗留文件（如 `.env~`）或风险项时，只报告不处理。

### 服务器查询规则

- **每次询问服务器相关问题时，必须通过 SSH 实际查看真实状态**，不得仅凭 `docs/server-operations.md`、本地快照或历史记忆作答。
- SSH 连接：`ssh -i ~/.ssh/id_ed25519 ubuntu@139.199.191.109`（`sudo -n` 免密可用），主机 `VM-0-17-ubuntu`。
- 若因网络等原因无法连接，明确说明「未能实连」，并区分哪些是实测、哪些来自文档记录。
- 读 `.env` 之类的凭据文件时只列变量名，不读值、不记录值。

## 服务器架构要点

- 部署根目录 `/home/ubuntu/starline-infra/`；发布目录 `releases/starline-20261022/`。
- 两套 Stack：`starline`（compose.yaml：postgres + redis）、`starline-20261022`（starline-app.compose.yaml：api/web/rocketmq/rustfs），共享外部网络 `starline_default`。
- 无 CI/CD：本地 `mvn package` + `vite build` → `scp` 上传 → Compose 挂载启动。
- 公网仅暴露 80（starline-web）与 9443（portainer）；9090/5432/6379 绑 127.0.0.1。
- 数据库 `starline` 库 public schema 共 35 张表。

## 已知问题（截至 2026-10-23）

- `t_user` 表 0 行：`init_data_pg.sql` 因含弱默认管理员凭据未执行，登录链路不可用，待建强密码管理员。
- `/api/.../actuator/health` 返回 200 但响应体是未登录 JSON，不能作健康探针。
- 服务器存在遗留文件 `/home/ubuntu/starline-infra/.env~`（含 DEEPSEEK/BAILIAN API Key 明文），未清理。
- 本地快照与线上配置内容一致，sha256 差异仅因 Windows CRLF；比对需用 `diff <(tr -d '\r')`。
