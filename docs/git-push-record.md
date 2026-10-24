# Git 推送记录

## 2026-10-23：星序官方平台来源清单与部署配置

### 本次拟提交范围

本次推送包含以下 7 个已修改文件、17 个新增项目文件，以及本推送记录：

已修改：
- docs/change-log/2026-10-22.md：补充本轮需求与实现记录。
- docs/product/starline-business-slice-setup.md：补充 V002 数据库迁移和官方平台来源清单管理说明及接口路径。
- docs/product/starline-mvp-requirements-v0.1.docx：更新 MVP 需求基线文档。
- docs/server-operations.md：记录星序服务器部署相关操作步骤与结果。
- frontend/src/pages/admin/AdminLayout.tsx：新增“星序来源清单”后台菜单及面包屑。
- frontend/src/router.tsx：注册平台来源清单管理页面路由。
- resources/database/schema_pg.sql：新增官方平台来源及评审记录表结构和索引。

新增后端：
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/StarlinePlatformSourceAdminController.java：提供候选清单查询、创建、更新和评审接口。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/request/SaveStarlinePlatformSourceRequest.java：定义平台来源新增和更新请求字段。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/request/ReviewStarlinePlatformSourceRequest.java：定义产品或研发评审请求字段。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/vo/StarlinePlatformSourceVO.java：定义来源清单和评审结果响应结构。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlinePlatformSourceDO.java：映射平台级官方来源调研数据。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlineSourceReviewDO.java：映射来源的产品与研发评审记录。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/mapper/StarlinePlatformSourceMapper.java：提供平台来源数据访问接口。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/mapper/StarlineSourceReviewMapper.java：提供来源评审记录数据访问接口。
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlinePlatformSourceService.java：实现 listCandidates、create、update、review、listApprovedSources；校验域名唯一性、调研字段及评审类型，并在来源信息变更后重置评审状态。

新增前端：
- frontend/src/pages/admin/starline/PlatformSourceRegistryPage.tsx：提供来源调研清单、编辑和产品/研发评审操作页面。
- frontend/src/services/starlinePlatformSourceService.ts：定义来源清单数据类型及查询、创建、更新和评审 API 调用。

新增数据库与部署：
- resources/database/migrations/V002__starline_platform_sources.sql：为平台来源和评审记录创建迁移表及索引。
- resources/deployment/README.md：说明腾讯云单机 Docker 部署准备、启动及 SSH 隧道访问流程。
- resources/deployment/nginx.conf：配置前端静态资源和 API 反向代理。
- resources/deployment/pgvector.override.yaml：为 PostgreSQL 启用 pgvector 镜像覆盖配置。
- resources/deployment/rocketmq-broker.conf：提供 RocketMQ Broker 配置。
- resources/deployment/starline-app.compose.yaml：定义 RustFS、RocketMQ、星序 API 与 Web 容器服务。

本机工作记忆文件 .workbuddy-ai/memory/MEMORY.md 不纳入提交。

### 推送状态

准备推送至 origin/main。当前环境首次 fetch origin 时无法连接 github.com:443；推送时将再次尝试。

## 2026-10-23：星序初始化数据适配与服务器配置归档

### 本次拟提交范围

本次提交包含 8 个已修改项目文件、6 个新增发布快照文件，以及本推送记录：

已修改：
- docs/change-log.md：更新 2026-10-23 变更概要。
- docs/change-log/2026-10-23.md：记录 Docker 配置快照、前端公网入口、星序 Agent 初始化和管理员创建结果。
- docs/server-operations.md：登记并记录服务器配置同步、前端部署、Agent 数据初始化及管理员创建操作；未记录密码或密钥。
- resources/database/init_data_pg.sql：将企业助手种子改为星序助手和 10 个星序提示词，使用幂等 upsert，并移除弱默认管理员凭据。
- resources/deployment/README.md：更新初始化顺序与管理员账号说明。
- resources/deployment/starline-app.compose.yaml：将 Web 容器映射至 HTTP 80 端口。
- resources/docker/server/README.md：说明服务器发布快照、初始化数据及临时公网访问方式。
- resources/docker/server/starline-app.compose.yaml：同步 Web 端口映射。

新增发布快照：
- resources/docker/server/releases/starline-20261022/init_data_pg.sql：保存适配后的服务器发布种子 SQL，与项目根脚本一致。
- resources/docker/server/releases/starline-20261022/nginx.conf：保存该发布版本的 Nginx 配置。
- resources/docker/server/releases/starline-20261022/pgvector.override.yaml：保存 PostgreSQL pgvector Compose 覆盖配置。
- resources/docker/server/releases/starline-20261022/rocketmq-broker.conf：保存 RocketMQ Broker 配置。
- resources/docker/server/releases/starline-20261022/schema_pg.sql：保存服务器发布版本的 PostgreSQL schema。
- resources/docker/server/releases/starline-20261022/starline-app.compose.yaml：保存服务器发布版本的应用 Compose 配置。

本机工作记忆文件 `.workbuddy-ai/memory/` 按既有推送记录约定不纳入提交。

### 推送状态

准备推送至 `origin/main`。推送前已 fetch，当前本地 `main` 与 `origin/main` 同步；待提交改动通过 `git diff --check`，未发现密钥、口令或私钥文件。

## 2026-10-24：Bad Case 规则适配 Agent 审计数据

### 本次推送范围

本次范围按用户后续明确要求更正为项目工作区全部非忽略变更；初始限定 Bad Case 文件的范围作废。文件总数 283（新增 76、修改 62、删除 134、重命名 11），完整清单见文末“全量推送清单”。

- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRules.java`：`evaluate()` 分流通用、RAG、Agent 规则；`evaluateAgentRules()` 新增 A1–A8 的证据线索检查。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseReport.java`：报告快照新增审计采集状态、轮次、工具调用数和工具输入数据。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseProperties.java`：增加 Agent 规则阈值配置，A1 默认关闭。
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/AgentBadCaseMessageProvider.java`：读取 Agent 审计节点及工具入参，组装 Bad Case 分析现场。
- `bootstrap/src/main/resources/application.yaml`、`resources/docker/server/application.yaml`、`resources/docker/server/releases/starline-20261022/application.yaml`：同步 Agent 规则配置。
- `docs/design/bad-case-analysis.md`：说明 Agent/RAG 规则分流、启发式证据边界与配置。
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRulesTest.java`、`BadCaseAnalyzerTest.java`：覆盖 A1 默认关闭、A2–A8、缺失入参说明和 RAG 回归。
- `docs/change-log.md`、`docs/change-log/2026-10-24.md`：记录逐文件改动、验证和部署结果。
- `docs/server-operations/2026-10-24.md`：按日期记录服务器备份、部署与验证操作，不含凭据。

预期推送目标：`origin/main`。本次包含 `.workbuddy-ai/memory/` 中的本地项目材料；Git 忽略的运行与构建产物不在暂存区。路径筛查未发现密钥类文件；内容扫描唯一命中为 AgentAuditSessionTest 中用于脱敏断言的 `sk-redacted` 固定测试样例，不是实际凭据。

## 2026-10-24：按用户要求推送全部本地改动

### 变更概要

- Agent：新增自建运行审计查询和持久化，移除旧 OTel Trace 实现及 Langfuse 配置，保留 Agent 审计链路。
- Bad Case：新增点踩离线分析、Agent/RAG 消息现场适配、人工判定页面与规则化 Agent 线索。
- 工作流：按方案下线 workflow 相关代码、页面和配置。
- 前端与管理端：更新聊天、运行审计、仪表盘、来源管理和 Bad Case 页面。
- 数据与部署：更新 PostgreSQL schema/迁移、Docker Compose 与服务器发布快照、部署及校验脚本。
- 文档：详细的逐文件类/方法说明及服务器操作结果见 `docs/change-log.md`、`docs/change-log/` 和 `docs/server-operations/`；本次本地工作材料也按用户要求包含。

全量暂存统计：283 个路径（新增 76、修改 62、删除 134、重命名 11）。

### 全量推送清单
- `.gitattributes`
- `.gitignore`
- `.workbuddy-ai/memory/2026-10-23.md`
- `.workbuddy-ai/memory/2026-10-24.md`
- `.workbuddy-ai/memory/MEMORY.md`
- `.workbuddy-ai/memory/agent-链路追踪自建方案.md`
- `.workbuddy-ai/memory/badcase-分析链路实施方案.md`
- `.workbuddy-ai/memory/badcase-逻辑详解.md`
- `.workbuddy-ai/memory/badcase多入口改造技术方案.md`
- `.workbuddy-ai/memory/badcase规则Agent化改造方案.md`
- `.workbuddy-ai/memory/workflow-下线改造技术方案.md`
- `.workbuddy-ai/memory/日志持久化与降噪技术方案.md`
- `.workbuddy-ai/memory/演出查询MCP工具设计方案.md`
- `.workbuddy-ai/memory/票务查询MCP方案-豆包模式.md`
- `README.md`
- `agent/pom.xml`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditController.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditMiddleware.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditNode.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditPageRequest.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditProperties.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditRepository.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditRun.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditSerializer.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditService.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditSession.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditWriter.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/config/ReActAgentProvider.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/controller/AgentMessageFeedbackController.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/enums/AgentToolStatus.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/AgentBadCaseMessageProvider.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/AgentMessageFeedbackService.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/handler/AgentRunHandle.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/handler/AgentStreamEventBridge.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/impl/AgentChatServiceImpl.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/skill/SkillLoadTool.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/AgentRuntimeContextKeys.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/AgentToolExecutionFacts.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/AgentToolExecutionTracker.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/KnowledgeSearchTool.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/McpToolProxy.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/MemoryFlushTool.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentErrorTypes.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentRunTracer.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentToolBodyTracer.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceAttributes.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceContextKeys.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceEnrichmentMiddleware.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceProperties.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceSerializer.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTracingConfiguration.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/GenAiAttributes.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/README.md`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/StarlineAttributes.java`
- `agent/src/main/java/com/nageoffer/ai/starlineagent/agent/trace/StarlineOtelTracingMiddleware.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/admin/AgentDashboardServiceTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/audit/AgentAuditSessionTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/config/ReActAgentProviderTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/service/handler/AgentRunHandleTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/service/handler/AgentStreamEventBridgeTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/service/impl/AgentChatServiceImplTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/tool/MemoryFlushToolTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentRunTracerTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentToolBodyTracerCoverageTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentToolBodyTracerTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceEnrichmentMiddlewareTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceExporterLoggingTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceScenarioTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTraceSerializerTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/AgentTracingConfigurationTest.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/CollectingSpanExporter.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/LangfuseReadbackProbe.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/MutableClock.java`
- `agent/src/test/java/com/nageoffer/ai/starlineagent/agent/trace/StarlineOtelTracingMiddlewareTest.java`
- `assets/langfuse.png`
- `bootstrap/src/main/java/com/nageoffer/ai/starlineagent/config/ModelStreamTraceConfiguration.java`
- `bootstrap/src/main/resources/application.yaml`
- `docs/agent-audit-usage.md`
- `docs/change-log.md`
- `docs/change-log/2026-10-24.md`
- `docs/dashboard-statistics.md`
- `docs/design/agent-audit-tracing-design.md`
- `docs/design/bad-case-analysis.md`
- `docs/design/offline-bad-case-plan.md`
- `docs/design/workflow-retirement.md`
- `docs/git-push-record.md`
- `docs/server-operations.md`
- `docs/server-operations/2026-10-21.md`
- `docs/server-operations/2026-10-22.md`
- `docs/server-operations/2026-10-23.md`
- `docs/server-operations/2026-10-24.md`
- `framework/src/main/java/com/nageoffer/ai/starlineagent/framework/web/GlobalExceptionHandler.java`
- `frontend/src/components/agent/AgentTurn.tsx`
- `frontend/src/components/agent/AgentWaterfall.tsx`
- `frontend/src/components/agent/AgentWelcomeScreen.tsx`
- `frontend/src/components/chat/ChatInput.tsx`
- `frontend/src/components/chat/FeedbackButtons.tsx`
- `frontend/src/components/chat/MessageItem.tsx`
- `frontend/src/components/chat/MessageList.tsx`
- `frontend/src/components/chat/QuestionRail.tsx`
- `frontend/src/components/chat/RecommendedQuestions.tsx`
- `frontend/src/components/chat/RecommendedQuestionsButton.tsx`
- `frontend/src/components/chat/SourceCitation.tsx`
- `frontend/src/components/chat/SourceIcon.tsx`
- `frontend/src/components/chat/SourcesButton.tsx`
- `frontend/src/components/chat/SourcesPanel.tsx`
- `frontend/src/components/chat/ThinkingIndicator.tsx`
- `frontend/src/components/chat/ThumbIcons.tsx`
- `frontend/src/components/chat/WelcomeScreen.tsx`
- `frontend/src/components/common/DislikeReasonDialog.tsx`
- `frontend/src/components/common/EngineGate.tsx`
- `frontend/src/components/common/MarkdownRenderer.tsx`
- `frontend/src/components/document/DocumentPreview.tsx`
- `frontend/src/components/layout/Header.tsx`
- `frontend/src/components/layout/MainLayout.tsx`
- `frontend/src/components/layout/Sidebar.tsx`
- `frontend/src/components/session/SessionItem.tsx`
- `frontend/src/components/session/SessionList.tsx`
- `frontend/src/hooks/useChat.ts`
- `frontend/src/hooks/useStreamResponse.ts`
- `frontend/src/lib/agentTimeline.ts`
- `frontend/src/pages/ChatPage.tsx`
- `frontend/src/pages/admin/AdminLayout.tsx`
- `frontend/src/pages/admin/agent-runs/AgentRunDetailPage.tsx`
- `frontend/src/pages/admin/agent-runs/AgentRunsPage.tsx`
- `frontend/src/pages/admin/badcase/BadCaseDetailDialog.tsx`
- `frontend/src/pages/admin/badcase/BadCasePage.tsx`
- `frontend/src/pages/admin/badcase/BadCaseReviewForm.tsx`
- `frontend/src/pages/admin/dashboard/AgentDashboard.tsx`
- `frontend/src/pages/admin/dashboard/DashboardCommon.tsx`
- `frontend/src/pages/admin/dashboard/DashboardPage.tsx`
- `frontend/src/pages/admin/dashboard/WorkflowDashboard.tsx`
- `frontend/src/pages/admin/settings/SystemSettingsPage.tsx`
- `frontend/src/pages/admin/traces/RagTraceDetailPage.tsx`
- `frontend/src/pages/admin/traces/RagTracePage.tsx`
- `frontend/src/pages/admin/traces/components/FilterBar.tsx`
- `frontend/src/pages/admin/traces/components/PageHeader.tsx`
- `frontend/src/pages/admin/traces/components/RunsTable.tsx`
- `frontend/src/pages/admin/traces/components/StatCard.tsx`
- `frontend/src/pages/admin/traces/components/TraceStatusChip.tsx`
- `frontend/src/pages/admin/traces/traceUtils.ts`
- `frontend/src/router.tsx`
- `frontend/src/services/agentAuditService.ts`
- `frontend/src/services/agentService.ts`
- `frontend/src/services/badCaseService.ts`
- `frontend/src/services/chatService.ts`
- `frontend/src/services/dashboardService.ts`
- `frontend/src/services/ragTraceService.ts`
- `frontend/src/services/sessionService.ts`
- `frontend/src/services/settingsService.ts`
- `frontend/src/stores/authStore.ts`
- `frontend/src/stores/chatStore.ts`
- `frontend/src/stores/engineStore.ts`
- `frontend/src/styles/globals.css`
- `frontend/src/types/index.ts`
- `frontend/tests/agent-timeline.check.mjs`
- `frontend/tests/dashboard-preview.tsx`
- `pom.xml`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/admin/controller/vo/DashboardPerformanceVO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/admin/service/impl/DashboardServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/aop/RagTraceAspect.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/ChatRateLimiterConfig.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/MemoryProperties.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/OrchestrationProperties.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/RAGRateLimitProperties.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/ThreadPoolExecutorConfig.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/validation/MemoryConfigValidator.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/validation/ValidMemoryConfig.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/ConversationController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/RAGChatController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/RAGSettingsController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/RagTraceController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/RecommendedQuestionController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/request/ConversationCreateRequest.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/vo/ConversationMessageVO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/vo/RagTraceDetailVO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/vo/SystemSettingsVO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/memory/ConversationMemoryService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/memory/ConversationMemoryStore.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/memory/DefaultConversationMemoryService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/memory/JdbcConversationMemoryStore.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/memory/JdbcConversationMemorySummaryService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/ConversationDO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/ConversationMessageDO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/ConversationSummaryDO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/RagTraceNodeDO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/RagTraceRunDO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/ConversationMapper.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/ConversationSummaryMapper.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/RagTraceNodeMapper.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/RagTraceRunMapper.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/enums/ConversationMessageOrder.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/enums/SSEEventType.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseAnalyzer.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseMessageProvider.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseProperties.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRecord.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRepairService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseReport.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRepository.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRules.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/controller/BadCaseController.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/job/BadCaseScanJob.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/mq/MessageFeedbackConsumer.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/mq/event/MessageFeedbackEvent.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ConversationGroupService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ConversationMessageService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ConversationService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/MessageFeedbackService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/RAGChatService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/RagTraceQueryService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/RagTraceRecordService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/RecommendedQuestionService.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/bo/ConversationCreateBO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/bo/ConversationSummaryBO.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/handler/StreamCallbackFactory.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/handler/StreamChatEventHandler.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/handler/StreamChatHandlerParams.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/ConversationGroupServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/ConversationMessageServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/ConversationServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/ConversationTitleGenerator.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/MessageFeedbackServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/RAGChatServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/RagTraceQueryServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/RagTraceRecordServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/RecommendedQuestionGenerator.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/RecommendedQuestionServiceImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/pipeline/StreamChatContext.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/pipeline/StreamChatPipeline.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ratelimit/ChatQueueLimiter.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ratelimit/FairDistributedRateLimiter.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/trace/RagStreamTraceSupportImpl.java`
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/trace/StreamChatTraceRunner.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/admin/service/impl/DashboardServiceImplTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/core/memory/JdbcConversationMemoryStoreTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/core/memory/JdbcConversationMemorySummaryServiceTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseAnalyzerTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRulesTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseServiceTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/service/handler/StreamChatEventHandlerTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/service/impl/ConversationMessageServiceImplOrderTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/service/pipeline/StreamChatPipelineTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/rag/service/ratelimit/ChatQueueLimiterTest.java`
- `rag/src/test/java/com/nageoffer/ai/starlineagent/service/ConversationMessageServiceTests.java`
- `resources/database/README.md`
- `resources/database/init_data_pg.sql`
- `resources/database/migrations/20261024_agent_run.sql`
- `resources/database/migrations/20261024_agent_run_verify.sql`
- `resources/database/migrations/20261024_bad_case.sql`
- `resources/database/migrations/20261024_bad_case_trace_backfill.sql`
- `resources/database/migrations/20261024_bad_case_trace_backfill_preview.sql`
- `resources/database/migrations/20261024_bad_case_verify.sql`
- `resources/database/migrations/20261024_workflow_retire.sql`
- `resources/database/schema_pg.sql`
- `resources/database/upgrades/v2.0.0/261024_01_otel_trace_prepare.sql`
- `resources/database/upgrades/v2.0.0/261024_02_otel_trace_retire.sql`
- `resources/docker/langfuse/README.md`
- `resources/docker/langfuse/langfuse-stack-v4.compose.yaml`
- `resources/docker/server/.gitignore`
- `resources/docker/server/README.md`
- `resources/docker/server/application.yaml`
- `resources/docker/server/releases/starline-20261022/20261024_agent_run.sql`
- `resources/docker/server/releases/starline-20261022/20261024_agent_run_verify.sql`
- `resources/docker/server/releases/starline-20261022/20261024_bad_case.sql`
- `resources/docker/server/releases/starline-20261022/20261024_bad_case_verify.sql`
- `resources/docker/server/releases/starline-20261022/20261024_workflow_retire.sql`
- `resources/docker/server/releases/starline-20261022/261024_01_otel_trace_prepare.sql`
- `resources/docker/server/releases/starline-20261022/261024_02_otel_trace_retire.sql`
- `resources/docker/server/releases/starline-20261022/application.yaml`
- `resources/docker/server/releases/starline-20261022/deploy-agent-audit-server.sh`
- `resources/docker/server/releases/starline-20261022/deploy-workflow-retirement.sh`
- `resources/docker/server/releases/starline-20261022/init_data_pg.sql`
- `resources/docker/server/releases/starline-20261022/schema_pg.sql`
- `resources/docker/server/releases/starline-20261022/starline-app.compose.yaml`
- `resources/docker/server/starline-app.compose.yaml`
- `resources/regression/agent-concurrency/ConcurrencyProbe.java`
- `scripts/deploy-agent-audit-server.sh`
- `scripts/deploy-workflow-retirement.sh`
- `scripts/sync-server-docker-config.ps1`
- `scripts/verify-agent-audit-server.py`
- `scripts/verify-workflow-retirement.py`
