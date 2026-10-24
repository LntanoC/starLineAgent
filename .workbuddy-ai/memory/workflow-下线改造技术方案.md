# workflow 入口下线改造技术方案（交付 Codex 执行）

> 目标：保留 Agent 流里的 RAG 逻辑（`KnowledgeSearchFacade` 完整复用检索内核），彻底下线并删除 workflow 编排入口及其独有代码、独有数据库表、前端对应页面与组件、初始化文档相关内容。
>
> 依据：本方案所有文件路径均经本地源码实地排查确认（2026-10-24）。删除范围以「编译能过、Agent 链路完整、接口可冒烟」为验收标准。



---

## 0. 一句话结论

workflow 的「编排层」可以整体删除，因为 Agent 已通过 `KnowledgeSearchFacade` 完整复用了 RAG 检索内核（改写→意图→歧义检测→多通道检索→精排→证据闸门→二次 LLM）。要删的是 workflow 独有的 **Controller / 流式编排 / 会话管理 / 链路追踪 / 会话记忆 / 聊天限流 / 反馈 MQ** 这一层，**检索内核与 Agent 正在 import 的共享类一根都不能动**。

---

## 1. 执行红线（严禁删除/修改的共享内核）

Agent 模块对 rag 模块的全部 import 已精确核实为 **11 个类**，这 11 个类及其传递依赖是红线：

```
rag.controller.request.MessageFeedbackRequest        ← AgentMessageFeedbackController/Service 用
rag.core.intent.IntentNode / IntentNodeRegistry       ← AgentToolCatalog 用
rag.core.prompt.AgentPromptResolver / AgentPromptSlot ← ReActAgentProvider / memory 用
rag.core.skill.AgentSkill / AgentSkillRegistry        ← SkillLoadTool / AgentToolCatalog 用
rag.dao.entity.MessageFeedbackDO                      ← AgentMessageFeedbackService 用（表 t_message_feedback）
rag.dao.mapper.MessageFeedbackMapper                  ← AgentMessageFeedbackService 用
rag.feedback.badcase.*                                ← AgentBadCaseMessageProvider 用（整个 bad case 体系）
rag.service.KnowledgeSearchFacade                     ← KnowledgeSearchTool 用
```

`KnowledgeSearchFacade` 的 7 个传递依赖（共享检索内核，红线）：

```java
QueryRewriteService, IntentResolver, IntentGuidanceService,
RetrievalEngine, CitationContextEnricher, RAGPromptService, LLMService
```

**共享 Controller（保留）：**

| Controller                                        | 路由                   | 原因                                                           |
| ------------------------------------------------- | -------------------- | ------------------------------------------------------------ |
| `RAGSettingsController`                           | `/rag/settings`      | 返回 `engine.type` + 检索/后端/模型配置，前端初始化依赖                        |
| `DashboardController`                             | `/admin/dashboard/*` | 注入 `DashboardService` 接口，Agent 侧由 `AgentDashboardService` 实现 |
| `GraphController`                                 | `/admin/kg/*`        | 知识图谱管理（Agent 检索内核仍用）                                         |
| `IntentTreeController`                            | `/intent-tree/*`     | 意图树管理                                                        |
| `QueryTermMappingController`                      | `/mappings/*`        | 查询词映射                                                        |
| `AgentProfileController` / `AgentSkillController` | 对应路由                 | Agent 人设/技能管理                                                |

---

## 2. 后端删除清单（按包分组，路径省略 `rag/src/main/java/com/nageoffer/ai/starlineagent/` 前缀）

### 2.1 Controller（删 5 个）

```
rag/controller/RAGChatController.java            # /rag/v3/chat + /rag/v3/stop
rag/controller/ConversationController.java        # /conversations 系列（workflow 会话）
rag/controller/RagTraceController.java            # /rag/traces/runs 系列（workflow 链路）
rag/controller/MessageFeedbackController.java     # /conversations/messages/{id}/feedback（Agent 用自己的 AgentMessageFeedbackController）
rag/controller/RecommendedQuestionController.java # /conversations/messages/{id}/recommended-questions
```

### 2.2 Service 接口 + 实现（删）

```
rag/service/RAGChatService.java + impl/RAGChatServiceImpl.java
rag/service/ConversationService.java + impl/ConversationServiceImpl.java
rag/service/ConversationMessageService.java + impl/ConversationMessageServiceImpl.java
rag/service/ConversationGroupService.java + impl/ConversationGroupServiceImpl.java
rag/service/impl/ConversationTitleGenerator.java
rag/service/MessageFeedbackService.java + impl/MessageFeedbackServiceImpl.java   # 注意：只删 Service，DO/Mapper/Request 保留
rag/service/RecommendedQuestionService.java + impl/RecommendedQuestionServiceImpl.java + impl/RecommendedQuestionGenerator.java
rag/service/RagTraceQueryService.java + impl/RagTraceQueryServiceImpl.java
rag/service/RagTraceRecordService.java + impl/RagTraceRecordServiceImpl.java
```

### 2.3 流式编排 / 处理器 / 追踪 / MQ / 记忆 / 限流（删）

```
rag/service/pipeline/StreamChatContext.java
rag/service/pipeline/StreamChatPipeline.java
rag/service/handler/StreamCallbackFactory.java
rag/service/handler/StreamChatEventHandler.java
rag/service/handler/StreamChatHandlerParams.java
rag/trace/RagStreamTraceSupportImpl.java
rag/trace/StreamChatTraceRunner.java
rag/mq/MessageFeedbackConsumer.java
rag/mq/event/MessageFeedbackEvent.java
rag/core/memory/ConversationMemoryService.java
rag/core/memory/ConversationMemoryStore.java
rag/core/memory/ConversationMemorySummaryService.java
rag/core/memory/DefaultConversationMemoryService.java
rag/core/memory/JdbcConversationMemoryStore.java
rag/core/memory/JdbcConversationMemorySummaryService.java
rag/service/ratelimit/ChatQueueLimiter.java
rag/service/ratelimit/FairDistributedRateLimiter.java
```

### 2.4 配置类（删 workflow 独有配置）

```
rag/config/ChatRateLimiterConfig.java              # 聊天限流装配（仅被 ChatQueueLimiter/FairDistributedRateLimiter 引用）
rag/config/MemoryProperties.java                   # 会话记忆配置（historyKeepTurns/summary*）
rag/config/RAGRateLimitProperties.java             # 聊天限流参数
rag/config/RagTraceProperties.java                 # workflow 链路追踪开关（rag.trace.enabled，非 agent.trace）
rag/config/validation/MemoryConfigValidator.java
rag/config/validation/ValidMemoryConfig.java
```

### 2.5 DAO Entity + Mapper（删 5 组，对应 5 张独有表）

```
rag/dao/entity/ConversationDO.java          + rag/dao/mapper/ConversationMapper.java            # t_conversation
rag/dao/entity/ConversationMessageDO.java   + rag/dao/mapper/ConversationMessageMapper.java     # t_message
rag/dao/entity/ConversationSummaryDO.java   + rag/dao/mapper/ConversationSummaryMapper.java     # t_conversation_summary
rag/dao/entity/RagTraceRunDO.java           + rag/dao/mapper/RagTraceRunMapper.java             # t_rag_trace_run
rag/dao/entity/RagTraceNodeDO.java          + rag/dao/mapper/RagTraceNodeMapper.java            # t_rag_trace_node
```

### 2.6 Request / VO / BO（删 workflow 独有 DTO）

```
rag/controller/request/ConversationCreateRequest.java
rag/controller/request/ConversationUpdateRequest.java
rag/controller/request/RagTraceRunPageRequest.java
rag/controller/request/DocumentSourceRequest.java        # 若确认仅 RAG 聊天来源使用；否则按编译结果保留
rag/controller/vo/ConversationVO.java
rag/controller/vo/ConversationMessageVO.java
rag/controller/vo/RagTraceRunVO.java
rag/controller/vo/RagTraceNodeVO.java
rag/controller/vo/RagTraceDetailVO.java
rag/service/bo/ConversationCreateBO.java
rag/service/bo/ConversationMessageBO.java
rag/service/bo/ConversationSummaryBO.java
```

### 2.7 bad case 体系的 workflow Provider（删 1 个）

```
rag/feedback/badcase/RagBadCaseMessageProvider.java   # 依赖 t_message/t_conversation 读 workflow 消息
```

> 删除后 bad case 体系只剩 `AgentBadCaseMessageProvider`。请确认 `BadCaseAnalyzer` 中 `providers.stream().map(...).findFirst()`（第 38-42 行附近）在只剩单 provider 时不会空指针/顺序异常，逻辑应为「只有一个 provider 就直接用它」。

### 2.8 需要「修改」而非删除的后端文件

**`rag/controller/RAGSettingsController.java`**（保留，但移除 workflow 独有字段）：

- 移除注入：`MemoryProperties`、`RAGRateLimitProperties`、`RagTraceProperties`
- 移除 `toRagSettings()` 中 `.memory(...)`、`.rateLimit(...)` 两段，以及 `FeatureSettings.trace` 字段
- 对应修改 `SystemSettingsVO.RagSettings`（删 `MemorySettings`、`RateLimitSettings` 及 `FeatureSettings.trace`）

**`rag/config/OrchestrationMode.java` 与 `OrchestrationProperties.java`**（保留，最小改动）：

- `starlineagent.engine.type` 配置保留，但固定为 `agent`（见第 6 节配置改正）
- `OrchestrationMode.WORKFLOW` 枚举值可保留（避免 `AgentPromptSlot`/`DefaultIntentClassifier` 的 `Set.of(WORKFLOW)`/`==WORKFLOW` 分支报错），列为「后续可选清理」；不要在本轮强删 WORKFLOW 枚举值

**`rag/admin/service/impl/DashboardServiceImpl.java`**（workflow 版看板实现，删）：

- 它带 `@ConditionalOnProperty(... havingValue="workflow", matchIfMissing=true)`，依赖 `t_rag_trace` 表
- 删除后 `DashboardService` 接口只剩 `AgentDashboardService`（agent 模块，`@ConditionalOnAgentEngine`）实现
- **注意**：删除后 `DashboardServiceImpl` 引用的 RAG 统计 SQL（读 `t_rag_trace`/`t_message`）一并消失，前端看板需同步简化（见第 4 节）

---

## 3. 数据库改造

### 3.1 删除的 5 张表（均无外键约束，schema_pg.sql 已核实无 FOREIGN KEY）

| 表                        | 对应 DO/Mapper          | 说明            |
| ------------------------ | --------------------- | ------------- |
| `t_conversation`         | ConversationDO        | workflow 会话   |
| `t_message`              | ConversationMessageDO | workflow 消息   |
| `t_conversation_summary` | ConversationSummaryDO | workflow 会话摘要 |
| `t_rag_trace_run`        | RagTraceRunDO         | workflow 链路运行 |
| `t_rag_trace_node`       | RagTraceNodeDO        | workflow 链路节点 |

### 3.2 删表 DDL（执行前先备份）

```sql
-- 1. 备份（任选其一，务必先做）
-- pg_dump -U <user> -d starline -t t_conversation -t t_message -t t_conversation_summary -t t_rag_trace_run -t t_rag_trace_node > /tmp/workflow_drop_backup_$(date +%F).sql

-- 2. 删除（CASCADE 兜底索引/视图）
DROP TABLE IF EXISTS t_rag_trace_node CASCADE;
DROP TABLE IF EXISTS t_rag_trace_run CASCADE;
DROP TABLE IF EXISTS t_conversation_summary CASCADE;
DROP TABLE IF EXISTS t_message CASCADE;
DROP TABLE IF EXISTS t_conversation CASCADE;
```

### 3.3 保留但需知晓的表

- **`t_message_feedback`**：Agent 复用 `MessageFeedbackMapper` 写反馈（agent 消息的反馈也进这张表），**保留**。删 `t_message` 后，其中历史 workflow 消息的反馈会成孤儿数据，可选清理（`DELETE FROM t_message_feedback WHERE message_id NOT IN (SELECT id FROM t_agent_message)`，由 Codex 评估是否执行）。
- `t_agent_profile` / `t_agent_prompt` / `t_agent_skill` / `t_intent_node` / `t_query_term_mapping` 等：共享，保留。
- `init_data_pg.sql` 只含 `t_agent_profile`/`t_agent_prompt` 的种子数据，**不涉及 workflow 表，无需改种子**。

---

## 4. 前端改造

### 4.1 删除（workflow 独有页面/组件/store/service）

```
frontend/src/pages/ChatPage.tsx
frontend/src/stores/chatStore.ts
frontend/src/hooks/useChat.ts
frontend/src/components/layout/MainLayout.tsx
frontend/src/components/layout/Header.tsx
frontend/src/components/layout/Sidebar.tsx
frontend/src/components/chat/                  # 整个目录（ChatInput/MessageList/MessageItem/MarkdownRenderer/SourcesPanel/SourcesButton/SourceCitation/SourceIcon/ThinkingIndicator/WelcomeScreen/FeedbackButtons/ThumbIcons/DislikeReasonDialog/RecommendedQuestions/RecommendedQuestionsButton/QuestionRail 等 16 个文件）
frontend/src/components/session/               # SessionList.tsx / SessionItem.tsx
frontend/src/services/chatService.ts
frontend/src/services/ragTraceService.ts
frontend/src/services/sessionService.ts
frontend/src/stores/engineStore.ts             # EngineGate 简化后不再需要
frontend/src/pages/admin/traces/               # RagTracePage/RagTraceDetailPage/traceUtils/components 整个目录
frontend/src/pages/admin/dashboard/WorkflowDashboard.tsx
```

> 已核实：Agent 前端反馈走 `agentService.ts` 的 `/agent/v1/messages/{id}/feedback`，不依赖 `chatService.ts` 的 `submitFeedback`/`cancelFeedback`/`generateRecommendedQuestions`，故 `chatService.ts` 可整删。

### 4.2 修改（删引用 + 简化）


| 文件 | 改动 |
|---|---|
| `components/common/EngineGate.tsx` | 去掉 `useEngineStore`，直接 `return <AgentChatPage />`；删除 `ChatPage`/`engineStore` import |
| `router.tsx` | 删除 `/admin/traces`、`/admin/traces/:traceId` 两条路由及 `RagTracePage`/`RagTraceDetailPage` import |
| `stores/authStore.ts` | 删除 `import { useChatStore }`；删除 login/logout 中 `useChatStore.getState().cancelGeneration()` 与 `useChatStore.setState({sessions:[],...})` 两段（保留 `useAgentChatStore`） |
| `services/dashboardService.ts` | 删除 `import type { EngineType }`；`DashboardPerformance` 简化为仅 `AgentDashboardPerformance`；`DashboardOverview.engine` 改为 `"agent"` 字面量 |
| `pages/admin/dashboard/DashboardCommon.tsx` | 去掉 `EngineType` 依赖，engine 判断简化为固定 `agent` |
| `pages/admin/dashboard/DashboardPage.tsx` | 删除 `workflowPerformance` 及 `WorkflowDashboard` 渲染分支，只渲染 `AgentDashboard` |
| `pages/admin/settings/SystemSettingsPage.tsx` | 删除 engine.type 的 `workflow: "工作流编排管线"` 选项；删除 trace/memory/rateLimit 相关展示段 |
| `services/settingsService.ts` | 删除 `SystemSettings` 类型中 memory/rateLimit/trace 相关字段定义 |
| `types/index.ts` | 检查并删除仅 workflow 使用的类型（如 `SourceRef` 若仅 chat 侧用则删，`PersistedMessageStatus` 等共享类型保留） |

---

## 5. 初始化文档与配置改正

| 文件 | 改动 |
|---|---|
| `bootstrap/src/main/resources/application.yaml` | 第 37-39 行 `engine.type` 注释改为「固定 agent，workflow 已下线」；确认 `eval.enabled` 保留（eval 独立于 workflow） |
| `resources/database/schema_pg.sql` | 删除 5 张表的 `CREATE TABLE` 定义（`t_conversation`、`t_message`、`t_conversation_summary`、`t_rag_trace_run`、`t_rag_trace_node`） |
| `resources/docker/server/releases/starline-20261022/schema_pg.sql` | 同步删除 5 张表定义（本地发布快照） |
| `README.md` | 改正架构描述：去掉「workflow 编排管线」入口说明，明确 Agent 为唯一入口、RAG 降级为 Tool |
| `docs/dashboard-statistics.md` | 删除 workflow 看板统计口径说明，保留 Agent 看板口径 |
| `docs/server-operations.md` | 若含 engine 切换/workflow 相关操作，改正为仅 Agent |
| `docs/change-log.md` | 新增一条变更记录（建议由 Codex 在完成改造后补记，注明日期与改动摘要） |

> `docs/change-log/2026-10-*.md`、`docs/releases/*.md` 为历史记录，原则上不改写历史，只追加新记录。

---

## 6. 执行顺序（分 6 阶段，每阶段可编译/可验证）

**阶段 0：备份**
- `git commit` 当前基线
- 数据库 5 张表 `pg_dump` 备份

**阶段 1：后端编排层删除**（删 2.1 Controller + 2.2 RAGChat/StreamChatPipeline/handler/trace）
- 验证：`mvn -pl rag,agent -am compile` 通过（此时 Conversation*/RagTrace* 仍未删，无编译破坏）

**阶段 2：后端独有 Service + DAO + 组件删除**（删 2.2 其余 + 2.3 + 2.4 + 2.5 + 2.6 + 2.7 + 2.8 修改）
- 验证：`mvn -pl rag,agent,starline,system -am compile` 通过；重点确认 `RAGSettingsController`、`BadCaseAnalyzer`、`DashboardService` 装配无缺 Bean

**阶段 3：前端删除与修改**（第 4 节全部）
- 验证：`vite build` 通过；TypeScript 无未引用错误

**阶段 4：数据库删表 + schema 改正**（第 3 节 + 第 5 节 schema）
- 验证：服务重启后 `/rag/settings`、`/agent/v1/meta`、`/admin/dashboard/overview` 正常

**阶段 5：文档改正**（第 5 节剩余）
- 验证：文档与实际代码/配置一致

**阶段 6：端到端冒烟**
- Agent 问答（含 `search_knowledge` 工具检索）正常
- 反馈、bad case 扫描正常
- 管理看板数据正常（Agent 版）

---

## 7. 风险与注意

1. **`t_message.trace_id` 列缺失的历史隐患**：线上 `t_message` 无 `trace_id` 列而 `ConversationMessageDO` 声明了该字段，删除 `ConversationMessageDO` 后此隐患随表一并消除（本次删除顺带解决）。
2. **`BadCaseAnalyzer` 单 provider**：删 `RagBadCaseMessageProvider` 后需人工确认 `findFirst()` 链路不空指针（见 2.7）。
3. **`OrchestrationMode.WORKFLOW` 枚举保留**：本轮不强删枚举值，避免 `AgentPromptSlot`/`DefaultIntentClassifier` 编译失败，作为后续可选清理项记录。
4. **`t_message_feedback` 孤儿数据**：可选清理，非必须（见 3.3）。
5. **`DocumentSourceRequest` 归属**：若编译报「被保留类引用」，说明它是共享的，改判保留（见 2.6 备注）。
6. **共享内核零改动**：第 1 节红线清单中的类一个字节都不能动，否则 Agent 链路即断。
