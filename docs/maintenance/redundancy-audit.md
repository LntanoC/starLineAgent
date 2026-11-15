# 冗余设计与无用文件盘点

盘点时间：2026-11-06
盘点范围：`starlineAgent` 工作区全部文件（含被 git 忽略的本地目录）
代码基线：`main` 分支，1395 个受跟踪文件；工作区约 1.67 GB（不含 `.git`）

判定方法：

- **Java 死代码**：对 718 个 `src/main/java` 文件做全仓符号引用扫描（`.java/.xml/.yaml/.properties/.sql/.md/.ts/.tsx`），排除自身声明后统计外部引用；再人工区分「Spring 容器托管」与「真正无人引用」。
- **前端死代码**：以 `main.tsx` 为入口做静态 import 图可达性分析（含 `import()` 动态导入），孤立即不可达。
- **重复文件**：按内容 MD5 分组比对。
- **配置/依赖**：与 `application.yaml` 实际装配、`package.json`/`pom.xml` 实际引用交叉核对。

---

## 一、结论速览

| # | 类别 | 位置 | 体量 | 处置 |
|---|------|------|------|------|
| 1 | 上线快照里的胖 JAR | `docs/server-operations/`（已 gitignore） | **1021 MB** | 直接删 |
| 2 | Maven 构建产物 | 8 个 `target/` | **235 MB** | 直接删 |
| 3 | 前端构建产物 | `frontend/dist`、`frontend/.output` | 8.9 MB | 直接删 |
| 4 | 本地回滚快照 | `.local-backups/`（9 个批次） | 6 MB / 135 文件 | 直接删 |
| 5 | 未被引用的配图 | `assets/` 中 28 张 | 15.3 MB | 建议删 |
| 6 | 被 git 跟踪的编译残留 | `vite.config.js`、`*.tsbuildinfo`、`fcheck.mjs` | 5 个文件 | 删并补 `.gitignore` |
| 7 | 死代码类 | Java 7 个 + 整包 `framework/idempotent`（6 个） | ~600 行 | 删 |
| 8 | 构建体系外的影子代码 | `resources/initializer`、`resources/regression` | **57 个 .java** | 收敛 |
| 9 | 三份配置并存 | 3 份 `application.yaml` | 34 KB | 合并为 1 份 |
| 10 | SQL 重复 | 78 个 `.sql`，唯一内容仅 56 份 | 20 组重复 | 去重 |
| 11 | 前端未使用文件/依赖 | 5 个源文件 + 5 个 npm 运行时依赖 | — | 删 |
| 12 | 配置为「关」的备用子系统 | Milvus / ES / LightRAG / WebSearch / SiliconFlow | ~1500 行 | 见第五节 |

可立即释放磁盘空间合计约 **1.29 GB**。

---

## 二、可直接删除的无用文件

### 2.1 上线快照里的胖 JAR（1.02 GB，最大头）

`docs/server-operations/` 下有 6 个以点开头的「部署快照」目录，每个都塞了完整可执行构件：

| 文件 | 份数 | 合计 |
|------|------|------|
| `starline.jar` | 5 | **954.1 MB** |
| `mcp-server.jar` | 2 | 58.1 MB |
| `frontend.tar.gz` | 4 | 8.3 MB |

5 份 `starline.jar` 哈希各不相同（`3DE4300E…`、`393FE1E6…`、`D92B590F…`、`416FA529…`、`1A289F63…`），是 5 次连续发版的产物，**每一份都能由 git 提交重新构建，且都已被下一份取代**。

该目录及 `docs/server-operations.md` 已被 `.gitignore` 忽略（`/docs/server-operations/`），因此纯粹是本地磁盘占用。

> 与项目自己的规范冲突：[project-artifact-policy.md](project-artifact-policy.md) 明确要求「备份目录禁止保存 `*.jar`、`*.tar.gz` 等可重建的构建产物」。这条规则目前只在 `.local-backups` 被遵守，`docs/server-operations` 完全没有约束。

建议：只保留每个快照的 `snapshot-metadata.sql` / 变更说明，JAR 一律删除。

### 2.2 Maven 与前端构建产物（244 MB）

- 8 个 `target/` 目录共 **234.7 MB**，其中 `bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar` 单个 190.8 MB。
- `frontend/dist` 7.1 MB、`frontend/.output` 1.8 MB。

均已 gitignore，属可重建产物。按 [project-artifact-policy.md](project-artifact-policy.md) 的说法，确认当前构建可跑通后即可清理。

### 2.3 本地回滚快照（`.local-backups/`，135 文件）

9 个批次的源码/配置快照：

```
badcase-pool-upload-20261025      feedback-withdrawal-20261025T0521Z
journey-v3                        project-slim-20261025-before
remove-chat-audit-link-20261024    remove-otel-trace-20261024   (58 文件, 818 KB)
seat-view                          server-operations-split-20261024
workflow-retire-20261024
```

其中 `remove-otel-trace-20261024` 保存了 3 份 `application.yaml`、2 份 `schema_pg.sql`、2 份 `20261025_*.sql` 的旧副本，全部可从 git 历史找回。任务已结束，建议整体清理。

### 2.4 被 git 跟踪的编译残留与垃圾文件

| 文件 | 问题 |
|------|------|
| `frontend/vite.config.js` | **`vite.config.ts` 的 tsc 编译输出**。Vite 的 `DEFAULT_CONFIG_FILES` 顺序为 `vite.config.js` → `.mjs` → `.ts`，**`.js` 会抢先被加载**，导致修改 `vite.config.ts` 永不生效。实测 `frontend/node_modules/vite/dist/node/constants.js` 已确认该顺序。 |
| `frontend/vite.config.d.ts` | 同上，tsc 产物 |
| `frontend/tsconfig.app.tsbuildinfo`、`tsconfig.node.tsbuildinfo` | 增量编译缓存，不应入库 |
| `frontend/fcheck.mjs` | **0 字节**空文件 |
| `frontend/.env` | 无密钥（仅 `VITE_API_BASE_URL`、`VITE_APP_NAME`），但按惯例应改为 `.env.example` 入库 |

这三行 `vite.config.*` 应删掉 `.js`/`.d.ts`，并把 `*.tsbuildinfo` 补进 `.gitignore`。

### 2.5 未被任何文档引用的配图（28/32，15.3 MB）

`assets/` 共 32 个文件，仅在文档中被引用 4 个：`starlineagent-architecture-v2.png`、`starlineagent-chain-v2.png`、`starlineagent-module-layering.png`、`starlineagent-module-layering-v3.png`（另有 `langfuse.png` 不在本目录）。

未引用且明显是**历史版本堆积**的：

| 主题 | 并存版本 | 说明 |
|------|----------|------|
| `starlineagent-chain` | 无后缀 / v2 / v3 / v4 | 4 个版本，1.72 MB + 1.23 MB + 0.62 MB + 0.56 MB |
| `starlineagent-module-layering` | 无后缀 / v2 / v3 | |
| `starlineagent-framework` | 无后缀 / v2 | |
| `qa-home` | 无后缀 / v2 / v2-confirm | |
| `admin-overview` | 无后缀 / v2 | |
| `rag-misconceptions` | 无后缀 / v2 | |

另有 8 张 admin 后台截图（`admin-datasets.png`、`admin-models.png`、`admin-trace.png` 等，共约 4.5 MB）与 3 个未被引用的 SVG，均无文档指向。

建议：每个主题只留最新版；截图类若不再用于 README，整批删除。

### 2.6 AI 工具残留目录

| 目录 | 状态 | 内容 |
|------|------|------|
| `.workbuddy-ai/` | **未跟踪且未 gitignore**（`git status` 可见） | 5 篇 memory（53 KB）+ 1 份 HTML 体检报告 |
| `.hallmark/` | 已 gitignore | 单文件 `salt` |
| `.claude/` | 已 gitignore | `settings.local.json` |
| `.idea/` | 已 gitignore | 8 个 IDE 配置文件 |

`.workbuddy-ai/` 应补进 `.gitignore`（或删除），否则每次 `git status` 都是噪声。另外 `design.md` 放在仓库根目录记录前端设计系统，与 `docs/design/` 平行，位置不一致。

---

## 三、Java 死代码

### 3.1 完全无外部引用的类（7 个）

| 文件 | 行数 | 判定 |
|------|------|------|
| `rag/.../rag/dto/CompletionPayload.java` | 44 | **重复实现**：`agent/dto/AgentCompletionPayload` 已取代它，全仓无引用 |
| `rag/.../rag/dto/MetaPayload.java` | 22 | **重复实现**：`agent/dto/AgentMetaPayload` 已取代 |
| `rag/.../rag/dto/RecommendedQuestionsPayload.java` | 52 | 无引用 |
| `rag/.../knowledge/controller/request/KnowledgeDocumentCreateRequest.java` | 55 | 请求 DTO 无调用方 |
| `infra-ai/.../infra/util/LogSafe.java` | 55 | 日志脱敏工具，全仓 0 引用 |
| `rag/.../knowledge/dao/handler/GroundingChunkListTypeHandler.java` | 70 | `BaseTypeHandler`，但全仓无 `typeHandler=` 注册、无 `type-handlers-package` 配置 |
| `rag/.../knowledge/dao/handler/SourceRefListTypeHandler.java` | 70 | 同上 |

前两个是**模块拆分遗留**：SSE 事件载荷从 `rag` 迁到 `agent` 后，`rag/dto` 的旧版本没有删除。

### 3.2 整包未启用

| 包 | 文件数 | 证据 |
|----|--------|------|
| `framework/idempotent/` | 6（`IdempotentConsume`、`IdempotentConsumeAspect`、`IdempotentConsumeStatusEnum`、`IdempotentSubmit`、`IdempotentSubmitAspect`、`SpELUtil`） | 全仓 `@IdempotentConsume` / `@IdempotentSubmit` **0 处使用**，两个切面永不触发 |
| `framework/context/ApplicationContextHolder` | 1 | 全仓 0 引用（而且本身是反模式） |
| `framework/trace/RagTraceContext` | 1 | 仅自身文件出现；结合 `.local-backups/remove-otel-trace-20261024`，应为 OTel 下线后的残留 |

### 3.3 同类重复：`SourceType` 双份

```
rag/.../ingestion/domain/enums/SourceType.java
rag/.../knowledge/enums/SourceType.java
```

这是全仓唯一一对「不同包、同名类」，语义重叠，应合并。

---

## 四、冗余设计

### 4.1 三份 `application.yaml` 并存且已漂移

| 文件 | 大小 |
|------|------|
| `bootstrap/src/main/resources/application.yaml` | 11.7 KB（本地默认） |
| `resources/docker/server/application.yaml` | 10.7 KB（服务器部署） |
| `resources/docker/server/releases/starline-20261022/application.yaml` | 11.8 KB（发版快照） |

三份**不是简单副本**，已经产生实质差异：`resources/docker/server` 版本多出 `logback` 段落（日志文件、滚动策略），`bootstrap` 版本多出 `agent.tool-rag` / `rag.feedback.bad-case` 完整段落。任何一次配置改动都要手工同步三处，是典型的隐性故障源。

建议：只保留 `bootstrap` 一份作为唯一来源，部署差异通过 `application-{profile}.yaml` 覆盖，发布快照不再复制整份配置。

### 4.2 SQL 三处重复：78 个文件里只有 56 份唯一内容

| 重复组 | 位置 |
|--------|------|
| 12 个迁移脚本 | `resources/database/migrations/*.sql` ≡ `resources/docker/server/releases/starline-20261022/*.sql`（同名同哈希） |
| `261104_event_search_v1.sql` | `resources/database/upgrades/v1.1.0/` ≡ `docs/server-operations/.event-search-20261104/` |
| `261104_journey_v3.sql` | `upgrades/v1.1.0/` ≡ `docs/server-operations/.journey-v3-20261104/` |
| `261104_seat_view.sql`、`261104_seat_view_demo.sql` | `upgrades/v1.1.0/` ≡ `docs/server-operations/.seat-view-20261104/` |
| `snapshot-metadata.sql` | 在两个 `docs/server-operations/.xxx` 快照目录中各一份 |
| `261024_01/02_otel_trace_*.sql` | `upgrades/v2.0.0/` ≡ release 目录 |

同一份 DDL 存在 2–3 个副本，执行时极易「改了 A 忘了 B」。

### 4.3 影子代码库：57 个 `.java` 不在 Maven 构建中

Maven 只编译 8 个模块的 `src/{main,test}/java`，但有 57 个 Java 文件游离在外：

```
resources/initializer/common/              10 个
resources/initializer/starline-events/     13 个
resources/regression/agent-compaction/      7 个
resources/regression/agent-memory/          8 个
resources/regression/agent-memory-audit/    4 个
resources/regression/agent-memory-evaluation/ 7 个
resources/regression/agent-long-term-memory/ 5 个
resources/regression/agent-run-gate-audit/  2 个
```

问题在于它们**重造了主工程已有的能力**：

- `initializer/common/` 自带一整套迷你基础库：`JdbcClient`、`RedisRespClient`、`SimpleJson`、`StarlineHttpClient`、`ApplicationYamlConfig` —— 相当于在 `infra-ai`/`framework` 之外又维护一份 JDBC/Redis/JSON/HTTP 客户端。
- `resources/regression/` 下出现 **3 套各写各的 SSE 客户端**：`agent-compaction/CompactionSseClient`、`agent-memory/AgentChatClient`、`agent-long-term-memory/MemorySseClient`。

这些文件没有 `pom.xml`、没有编译校验、没有测试覆盖，改主工程的接口签名它们不会报错，只会在真正跑回归时炸。建议收敛为一个 `tools` 模块（纳入 Maven，靠 `exec-maven-plugin` 或独立 main 运行），至少让它们参与编译。

### 4.4 前端样式：三套令牌叠加

`main.tsx` 只 import 了两个 CSS：`globals.css`（152.4 KB）与 `redesign.css`（17.6 KB）。但实际生效的令牌定义有三层：

1. `globals.css` 顶部 `@import` 引入 `tokens.css`（5.4 KB）+ `base.css`（3.1 KB）
2. `globals.css` 自身又定义了一遍 `:root { --background: 210 40% 98%; … }`
3. `redesign.css` 再覆盖一遍 `:root { --background: 220 28% 98%; … }`

而 [design.md](../design.md) 第 9 行明确写着「颜色、字体、圆角、间距、图表色和阴影**统一定义在** `tokens.css`……组件以语义令牌取色」。文档与实现已经不一致：改 `tokens.css` 大概率被后加载的 `redesign.css` 覆盖，排查时非常费时。

建议：`tokens.css` 作为唯一 `:root` 定义处，`globals.css` 只留 Tailwind 指令与基础重置，`redesign.css` 只留组件级覆盖。

### 4.5 前端未达代码（5 个文件）

以 `main.tsx` 为根的可达性分析（131 个源文件，123 个可达）后确认无任何 `import`：

| 文件 | 说明 |
|------|------|
| `frontend/src/components/common/Loading.tsx` | 全站 0 处 `from "@/components/common/Loading"` |
| `frontend/src/hooks/useAuth.ts` | 只是 `useAuthStore` 的薄包装，所有页面都直接用了 `useAuthStore` |
| `frontend/src/components/ui/separator.tsx` | 无引用（`dropdown-menu.tsx`/`select.tsx` 用的是 Radix 自己的 Separator，不是这个封装） |
| `frontend/src/lib/markdownToText.ts` | 无引用 |
| `frontend/src/lib/source.ts` | 无引用 |

附带：`frontend/src/utils/helpers.ts` 仅 `RelativeTime.tsx` 一个使用方（用 `formatTimestamp`），其中导出的 `truncate()` 函数无任何调用（页面里的 `truncate` 都是 Tailwind 类名）。

### 4.6 未使用的依赖

**前端 npm（5 个运行时依赖，全仓 0 引用）**：

```
@radix-ui/react-progress   @radix-ui/react-tabs   @tanstack/react-table
react-dropzone             recharts
```

`ui/table.tsx` 是手写 `React.forwardRef` 表格，图表是自研 `SimpleLineChart` + `vizTokens`，所以 `@tanstack/react-table` 和 `recharts` 没被用上；文件上传也未使用 `react-dropzone`。

**后端 Maven（2 条 `dependencyManagement` + 2 个属性从未被任何模块引用）**：

- `bizlog-sdk`（`com.mouzt`）—— 8 个模块 pom 中无一声明，Java 中 0 处 `import com.mouzt`
- `javax-validation-api` 2.0.1 —— 代码统一用 `jakarta.validation`（28 处），`javax.validation` 0 处

### 4.7 文档冗余

| 位置 | 问题 |
|------|------|
| `docs/design/` | `event-trip-planning.md` 与 `event-trip-planning-v3.md` + `event-trip-planning-v3-implementation.md` 三代并存；`event-search-coverage-v1.md` 与 `-implementation.md` 重叠 |
| `docs/git-push-record.md` | 104.5 KB 单文件，属于操作流水，价值低于体积 |
| `docs/server-operations.md` + `docs/server-operations/` | 均已被 gitignore，却仍在工作区（后者占 1 GB） |
| `docs/` 下 74 个 `.md` | `change-log.md`（索引）+ `change-log/` 12 篇日报，索引里已列出全部条目，属正常模式，不必动 |

### 4.8 包结构小瑕疵

`rag` 模块同时存在 `com.nageoffer.ai.starlineagent.rag.*` 与 `com.nageoffer.ai.starlineagent.rag.rag.*` 两层同名嵌套，路径阅读成本高，建议把内层 `rag` 改名为 `core` 或 `query`。

---

## 五、配置为「关」的备用子系统（需单独决策）

这一类**不是无用文件，而是被 feature flag 长期关闭的实现**。默认配置下它们从不执行，但仍随包发布、仍要跟着接口变更维护：

| 子系统 | 默认值（`bootstrap/application.yaml`） | 对应代码 |
|--------|--------------------------------------|----------|
| 向量库 | `rag.vector.type: pg` | `MilvusVectorStoreService`、`MilvusVectorRetrieverService`、`MilvusVectorStoreAdmin`（约 500 行） |
| 关键词检索 | `rag.keyword.type: none` + `channels.keyword.enabled: false` | `EsClientConfig`、`KeywordSyncingVectorStoreService` |
| 知识图谱 | `rag.graph.type: none` + `channels.graph.enabled: false` | `LightRagClient`、`GraphQueryService`、`GraphSyncVectorStorePostProcessor` |
| 联网搜索 | `channels.web-search.enabled: false` | `WebSearchChannel` |
| SiliconFlow 供应商 | `ai.providers` 中**没有 `siliconflow` 条目** | `SiliconFlowChatClient`、`SiliconFlowEmbeddingClient`（`ModelProvider` 枚举里保留了 `SILICON_FLOW`，但任何配置都选不中它） |
| 对象存储 | `rag.storage.type: s3` | `OssObjectStorageClient`（阿里云 OSS 分支） |
| 短链路降级 | `rag.feedback.bad-case.detectors.behavior/sampling: OFF` | 对应 detector 实现 |

**SiliconFlow 是唯一确定「永远装配不上」的一项**（配置里连 provider 条目都没有，只剩注释提到），建议直接删除或补上配置。

其余几项属于「可插拔后端」的正常取舍。若短期不打算启用，建议在文档里显式标注「未验证/实验性」，否则每次接口重构都要为这些代码付出成本。

---

## 六、建议处置顺序

### P0 —— 纯删，无风险（释放约 1.29 GB）

```powershell
# 1) 上线快照里的 JAR / 前端包（只留 md / sql 说明）
Get-ChildItem docs\server-operations -Recurse -File -Include *.jar,*.tar.gz | Remove-Item -Force

# 2) 构建产物
Get-ChildItem -Recurse -Directory -Filter target | Remove-Item -Recurse -Force
Remove-Item frontend\dist, frontend\.output -Recurse -Force

# 3) 已完成的回滚快照
Remove-Item .local-backups -Recurse -Force

# 4) 被跟踪的编译残留 + 空文件
Remove-Item frontend\vite.config.js, frontend\vite.config.d.ts
Remove-Item frontend\tsconfig.app.tsbuildinfo, frontend\tsconfig.node.tsbuildinfo, frontend\fcheck.mjs
```

并在 `.gitignore` 中补上：

```
frontend/*.tsbuildinfo
frontend/vite.config.js
frontend/vite.config.d.ts
.workbuddy-ai/
```

> 注意：删除 `frontend/vite.config.js` 后，Vite 才会真正加载 `vite.config.ts`，请确认后者的内容与当前 `.js` 一致（目前两者内容相同，安全）。

### P1 —— 删死代码（需一次 `mvn -q compile` + 前端构建验证）

- 删除 7 个无引用类（3.1 节）
- 删除整包 `framework/idempotent/`、`framework/context/ApplicationContextHolder`、`framework/trace/RagTraceContext`
- 删除 5 个前端死文件（4.5 节）与 `helpers.ts` 中的 `truncate`
- 移除 5 个 npm 依赖、2 条 Maven `dependencyManagement`（含 `bizlog-sdk.version`、`javax-validation-api.version` 两个属性）

### P2 —— 结构性收敛

- 三份 `application.yaml` 合一，部署差异走 profile
- SQL 只保留 `resources/database/`，发布目录改为引用而非复制
- `resources/initializer` + `resources/regression` 收进 Maven 模块
- CSS 令牌统一到 `tokens.css`
- 合并 `SourceType` 双份；重命名 `rag.rag` 层级
- `assets/` 按「每主题只留最新版」清理

---

## 七、看起来像冗余、实际不是的部分

避免误删，以下内容经核对是**合理设计**，不建议清理：

- **`mcp-server` 独立模块**（63 个 Java 文件）：它是独立 Spring Boot 应用，`agent` 通过 `agent.mcp.servers[0].url: http://localhost:9099` 走 MCP 协议调用，与 `agent/tool` 下的 `McpToolProxy` 是客户端/服务端关系，不是重复实现。
- **`resources/docker/server/releases/starline-20261022/`**：发布快照本身有价值（含 compose、nginx、broker 配置），但其中的 SQL 与 JAR 属复制品，只需保留清单即可。
- **`frontend/src/components/ui/` 下的 Radix 封装**：虽然部分组件引用较少，但 `dropdown-menu`/`select`/`dialog` 等仍是实际依赖，只有 `separator.tsx` 确认无引用。
- **`docs/change-log.md` + `docs/change-log/`**：索引 + 每日详情的正常拆分。
- **`framework` 模块中仅被本模块引用的类**（`GlobalExceptionHandler`、`WebAutoConfiguration`、`DataBaseConfiguration` 等）：由 Spring 自动配置或全局异常处理入口触发，不是死代码。
