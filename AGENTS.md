# AGENTS.md

本文件是本仓库对 AI 编码代理（Codex / Claude Code / Cursor 等）的强制约定。
冲突时以本文件为准，其次才是 `docs/` 下的设计文档。

---

## 0. 完成定义的硬门槛

在你说「完成」之前，这三条必须全部成立：

```bash
node scripts/check-hygiene.mjs          # 必须 exit 0
git status --short                       # 只能出现本次有意修改的文件
```

改 Java：`./mvnw -q -DskipTests compile`
改前端：`cd frontend && npm run lint && npm run build`

检查失败 **不代表可以改基线**。见第 7 节。

---

## 1. 项目结构（先看清再动手）

Maven 多模块（Java 17，Spring Boot 4）：

| 模块 | 职责 |
|------|------|
| `framework` | 通用基础设施：Web/DB/MQ 自动配置、异常、雪花 ID |
| `infra-ai` | 模型接入层：chat / embedding / rerank / vlm 的路由与供应商实现 |
| `system` | 用户、审计、样例问题 |
| `rag` | 知识库、文档解析、分块、检索、入库、Bad Case |
| `agent` | Agent 运行时：工具调用、记忆、技能、SSE、审计 |
| `starline` | 星序业务域：演出、座位、平台来源 |
| `mcp-server` | **独立 Spring Boot 应用**，通过 MCP 协议对外提供工具 |
| `bootstrap` | 启动模块，聚合全部依赖 |

前端：`frontend/`（React 18 + Vite + Zustand + Tailwind）。

---

## 2. 不得提交构建产物

**禁止入库**：`*.jar` `*.war` `*.tar.gz` `*.zip` `*.log`、`target/`、`dist/`、`.output/`、`node_modules/`、`*.tsbuildinfo`。

**特别禁止** `frontend/vite.config.js` 与 `frontend/vite.config.d.ts`：
它们是 `vite.config.ts` 的 `tsc` 产物，而 Vite 的配置查找顺序是 `.js` → `.mjs` → `.ts`，
**`.js` 存在时你的 `.ts` 修改永不生效**。改完配置要确认没有生成这两个文件。

**临时文件**一律写到系统临时目录，任务结束删除；不要留在仓库里，哪怕只是几 KB。
**不要创建** `.local-backups/`、`docs/server-operations/` —— git 本身就是备份，
这两个目录已经被 gitignore，但**仍会占磁盘**，请勿再往里放任何东西。

## 3. 不得复制已有内容

这是本仓库历史上最频繁的劣化来源。先搜，再写。

| 内容 | 唯一权威位置 | 禁止 |
|------|--------------|------|
| 应用配置 | `bootstrap/src/main/resources/application.yaml` | 在 `resources/docker/`、`releases/` 下再放一份完整 `application.yaml`；部署差异用 `application-<profile>.yaml` |
| DDL / 迁移 | `resources/database/{migrations,upgrades}/` | 把同一份 `.sql` 复制到发布目录或文档目录 |
| 前端设计令牌 | `frontend/src/styles/tokens.css` | 在 `globals.css` / `redesign.css` 里再写一遍 `:root { --xxx }` |
| SSE 事件载荷 | `agent/.../agent/dto/Agent*Payload.java` | 在 `rag/.../rag/dto/` 再造 `CompletionPayload` / `MetaPayload` |
| 通用客户端 | `infra-ai` / `framework` 已有实现 | 自己再写 `JdbcClient` / `RedisRespClient` / `SimpleJson` / `HttpClient` |
| 前端工具 | `@/lib/utils`、`@/utils/*` 现有函数 | 新建 `helpers2`、`commonUtils` 之类平行模块 |

同一个概念**不得有两个同名类**（历史上 `SourceType` 在 `ingestion/domain/enums` 和
`knowledge/enums` 各有一份）。跨模块共享的 DTO 放 `infra-ai` 或调用方模块，不要两边各留一份。

## 4. 替换就必须删除

迁移、改名、重构之后，**旧实现要在同一个提交里删掉**，不要保留"以防万一"。
仓库里现有的 `rag/dto/{CompletionPayload,MetaPayload,RecommendedQuestionsPayload}` 就是
模块拆分后没删的遗留物，全仓零引用。

删掉一个类之前确认：全仓搜索类名，排除自身后无命中，且不是 Spring 组件 / MyBatis Mapper /
`@ConfigurationProperties`。删除后跑一次编译。

## 5. 新增能力前先复用

- 需要 HTTP / JSON / JDBC / Redis / 缓存？先查 `infra-ai`、`framework` 是否已有。
- 需要脚本（初始化数据、回归验证、压测）？**放进 Maven 模块的 `src/main/java`**，
  或用 Node/Python 放在 `scripts/`。**不要把裸 `.java` 丢在 `resources/` 下** ——
  它们不在构建里，改接口时不会编译报错，只会在真正跑的时候炸。
- 新增前端依赖必须**真的 import 后再写进 `package.json`**；删除代码时同步清理依赖。
  当前 `package.json` 里 `recharts`、`@tanstack/react-table`、`react-dropzone` 等
  5 个运行时依赖零引用，属历史欠账，不要在此基础上再加。

## 6. 前端：不要留下不可达文件

组件、hook、工具函数写完后必须**至少被一处 import**（含 `import()` 动态导入）。
临时试验用的组件要么用完删除，要么接进路由，不要留在 `src/` 里。
样式令牌只改 `tokens.css`，`globals.css` 只放 Tailwind 指令与基础重置。

## 7. 检查失败时的正确反应

`scripts/check-hygiene.mjs` 有一个 `.hygiene-baseline.json` 记录已知存量违规。

- **正确**：修掉违规（删文件、改配置、去重）。
- **错误**：运行 `--update-baseline` 把你的新问题加进去；或修改检查脚本放宽规则。
- 只有当你**有意**引入一笔需要后续偿还的技术债、并且用户明确同意时，才扩充基线，并在此文件里记一行说明。

## 8. 提交与沟通

- 一次提交只做一件事；提交信息写明「做了什么 + 为什么」。
- 改数据结构时同步更新 `resources/database/` 下的 DDL，而不是只改实体类。
- 汇报结果时**如实说明未验证的部分**，不要用"应该可以"代替实际运行过的命令。

---

## 附：本仓库曾经出现过的问题（用于自查）

| 症状 | 正确做法 |
|------|----------|
| 5 个上线快照共 954 MB 的 `starline.jar` | 快照只记变更说明与 SQL，构件由 CI 重建 |
| 三份 `application.yaml` 已经漂移 | 单一来源 + profile 覆盖 |
| 12 个 SQL 在迁移目录和发布目录各一份 | DDL 只留 `resources/database/` |
| `vite.config.js` 遮蔽 `vite.config.ts` | 删掉 tsc 产物 |
| `rag/dto` 三个类零引用 | 迁移时同提交删除旧实现 |
| 57 个 `.java` 游离在构建之外 | 收进 Maven 模块 |
| 5 个前端文件不可达 + 5 个依赖零引用 | 完成前跑 hygiene 检查 |
| CSS 令牌三处定义 | 只改 `tokens.css` |
