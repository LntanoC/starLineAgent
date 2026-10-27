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

### 本次提交

- 全量项目改动提交：`bed12fc3`（`chore: sync all local project changes`）。
- 推送目标：`origin/main`；推送记录随本次推送一起提交。

## 2026-10-25：按用户要求推送当前工作区全部改动

### 范围与核对

本次盘点到 516 个现有改动路径：新增 132、修改 92、删除 292。提交时一并纳入本推送记录。范围包括本轮前已经存在的本地改动，按用户明确要求全量推送。

凭据扫描检查了所有当前可读的修改/新增文件：未发现私钥、常见供应商密钥格式或实际凭据字面量；唯一命中是前端修改密码表单的字段名（currentPassword/newPassword），不是密码值。resources/docker/server/.env.example 是环境变量模板，不含实际 .env。

### 功能与清理概要

- 星序提醒：改为 iCal 日历订阅，覆盖匿名 feed token、取消墓碑、活动时间变化后的去重与同步。
- MCP：接入演出搜索与腾讯元宝；增加独立工具注册表、Tool RAG 检索和懒加载，并清理旧演示业务工具。
- Bad Case：扩展候选池、信号检测、去重、证据补全、低置信自动评审、人工样本和后台审核操作。
- 前端：增加移动端布局、MCP 工具管理、Bad Case 审核和反馈原因交互，并调整意图路由编辑。
- 初始化、数据库和部署：更新星序模板、迁移脚本、Compose/Nginx 与服务器发布快照。
- 框架清理：删除 GitHub 根目录上游 README、上游发布介绍，以及旧电商/企业演示资产；保留星序运行与开发说明。
- 逐类、类/方法级细节见 docs/change-log.md、docs/change-log/2026-10-24.md、docs/change-log/2026-10-25.md；服务器命令及结果见 docs/server-operations/2026-10-24.md 和 docs/server-operations/2026-10-25.md。

### 全量文件清单

#### 新增文件（含未跟踪文件）

- .workbuddy-ai/memory/2026-10-25.md
- .workbuddy-ai/memory/方案B-彻底清理电商资产与星序落位.md
- .workbuddy-ai/memory/简历改写-大厂Agent岗.md
- .workbuddy-ai/memory/项目描述-RAG与ToolRAG与评测.md
- .workbuddy-ai/memory/演出查询MCP工具设计方案-v2.md
- .workbuddy-ai/memory/已批未宣场景改造方案.md
- .workbuddy-ai/memory/自研ToolSearch实现方案.md
- .workbuddy-ai/memory/badcase统一候选池与分级入口技术方案.md
- .workbuddy-ai/memory/create_reminder工具设计.md
- .workbuddy-ai/memory/iCal日历订阅改造方案.md
- .workbuddy-ai/memory/mcp-server精简与演出查询实现方案.md
- .workbuddy-ai/memory/MCP调用改造方案-ToolRAG与懒加载.md
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/badcase/AgentBadCaseScanReader.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/badcase/AgentRuntimeBadCaseDetector.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/badcase/ConversationBehaviorDetector.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/badcase/UserCorrectionDetector.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/controller/McpToolAdminController.java
- agent/src/test/java/com/nageoffer/ai/starlineagent/agent/badcase/AgentRuntimeBadCaseIntegrationTest.java
- agent/src/test/java/com/nageoffer/ai/starlineagent/agent/badcase/ConversationSignalTest.java
- docs/bad-case-candidate-pool-usage.md
- docs/change-log/2026-10-25.md
- docs/design/bad-case-candidate-pool.md
- docs/server-operations/2026-10-25.md
- framework/src/main/java/com/nageoffer/ai/starlineagent/framework/badcase/api/BadCaseDetector.java
- framework/src/main/java/com/nageoffer/ai/starlineagent/framework/badcase/api/BadCaseIntake.java
- framework/src/main/java/com/nageoffer/ai/starlineagent/framework/badcase/api/BadCaseSanitizer.java
- framework/src/main/java/com/nageoffer/ai/starlineagent/framework/badcase/api/BadCaseSignal.java
- frontend/src/pages/admin/badcase/BadCaseEvalPanel.tsx
- frontend/src/pages/admin/mcp-tools/McpToolPage.tsx
- frontend/src/services/mcpToolService.ts
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/YdcApiKeyConfiguredCondition.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/YuanbaoApiKeyConfiguredCondition.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchProperties.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/WebSearchClient.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/YouComWebSearchClient.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/YuanbaoWebSearchClient.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/config/ToolRagProperties.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/request/McpToolConfigureRequest.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/request/McpToolUpdateRequest.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/vo/McpToolRegistryVO.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/McpToolDO.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/entity/McpToolVectorDO.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/McpToolMapper.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/McpToolVectorMapper.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseAlertService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseAutoReviewService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseDedupPolicy.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseDetectionCoordinator.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseEvalSampleService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseEvidenceEnricher.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseIntakeService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/UnvotedSampleDetector.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/UserFeedbackBadCaseDetector.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/McpToolRegistryServiceImpl.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/McpToolVectorService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/McpToolRegistryService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/ToolRagService.java
- rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseCandidatePolicyTest.java
- rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCasePostgresIntegrationTest.java
- resources/database/migrations/20261025_bad_case_candidate_pool.sql
- resources/database/migrations/20261025_mcp_tool_registry.sql
- resources/database/migrations/20261025_starline_delivery_failure.sql
- resources/database/migrations/20261026_starline_ical.sql
- resources/docker/server/releases/starline-20261022/20261025_bad_case_candidate_pool.sql
- resources/docker/server/releases/starline-20261022/20261025_mcp_tool_registry.sql
- resources/docker/server/releases/starline-20261022/20261025_starline_delivery_failure.sql
- resources/docker/server/releases/starline-20261022/20261026_starline_ical.sql
- resources/docker/server/releases/starline-20261022/deploy-badcase-candidate-pool.sh
- resources/docker/server/releases/starline-20261022/deploy-badcase-mode-hardening.sh
- resources/docker/server/releases/starline-20261022/deploy-feedback-withdrawal.sh
- resources/docker/server/releases/starline-20261022/deploy-mcp-server.sh
- resources/docker/server/releases/starline-20261022/deploy-starline-ical.sh
- resources/docker/server/releases/starline-20261022/verify-badcase-candidate-pool.py
- resources/initializer/starline-events/agent-profile.properties
- resources/initializer/starline-events/AgentProfileInitMain.java
- resources/initializer/starline-events/AgentSkillInitMain.java
- resources/initializer/starline-events/checksums.sha256
- resources/initializer/starline-events/cleanup.sql
- resources/initializer/starline-events/CleanupMain.java
- resources/initializer/starline-events/docs/knowledge/platform/大麦.md
- resources/initializer/starline-events/docs/knowledge/platform/猫眼.md
- resources/initializer/starline-events/docs/knowledge/platform/票星球.md
- resources/initializer/starline-events/docs/knowledge/platform/秀动.md
- resources/initializer/starline-events/docs/knowledge/platform/永乐票务.md
- resources/initializer/starline-events/docs/knowledge/policy/提醒与核验规则.md
- resources/initializer/starline-events/DocumentInitMain.java
- resources/initializer/starline-events/InitializeMain.java
- resources/initializer/starline-events/initializer.properties
- resources/initializer/starline-events/intents/000-starline.properties
- resources/initializer/starline-events/intents/100-event.properties
- resources/initializer/starline-events/intents/200-reminder.properties
- resources/initializer/starline-events/intents/210-reminder-rules.properties
- resources/initializer/starline-events/intents/300-source-check.properties
- resources/initializer/starline-events/intents/400-platform.properties
- resources/initializer/starline-events/intents/410-platform-capability.properties
- resources/initializer/starline-events/intents/500-system.properties
- resources/initializer/starline-events/IntentTreeInitMain.java
- resources/initializer/starline-events/knowledge-bases.properties
- resources/initializer/starline-events/KnowledgeBaseInitMain.java
- resources/initializer/starline-events/mcp-tools/current_date.properties
- resources/initializer/starline-events/mcp-tools/search_artist_events.properties
- resources/initializer/starline-events/mcp-tools/youcom_search.properties
- resources/initializer/starline-events/McpToolInitMain.java
- resources/initializer/starline-events/PreflightMain.java
- resources/initializer/starline-events/prompts/agent-main.txt
- resources/initializer/starline-events/prompts/context-compaction.txt
- resources/initializer/starline-events/prompts/memory-extraction.txt
- resources/initializer/starline-events/questions.properties
- resources/initializer/starline-events/README.md
- resources/initializer/starline-events/SampleQuestionInitMain.java
- resources/initializer/starline-events/ValidateDatasetMain.java
- resources/initializer/starline-events/VerifyMain.java
- resources/initializer/starline-events/WarmupMain.java
- resources/regression/agent-memory-evaluation/datasets/starline-events.json
- resources/regression/agent-memory-evaluation/prepare_bad_case_import.py
- scripts/deploy-badcase-candidate-pool.ps1
- scripts/deploy-badcase-candidate-pool.sh
- scripts/deploy-badcase-mode-hardening.sh
- scripts/deploy-feedback-withdrawal.sh
- scripts/deploy-mcp-tool-rag.sh
- scripts/deploy-starline-ical.sh
- scripts/test-badcase-candidate-pool.ps1
- scripts/verify-badcase-candidate-pool.py
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/StarlineCalendarController.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlineCalendarTokenDO.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/mapper/StarlineCalendarTokenMapper.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineCalendarTokenService.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineIcsBuilder.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineInvocationContext.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineRuleViolationException.java
- starline/src/test/java/com/nageoffer/ai/starlineagent/starline/service/StarlineRuleBadCaseTest.java

#### 修改文件

- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/config/ReActAgentProvider.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/controller/AgentMessageFeedbackController.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/controller/vo/AgentMessageVO.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/dao/mapper/AgentMessageMapper.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/AgentMessageFeedbackService.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/impl/AgentChatServiceImpl.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/service/impl/AgentConversationServiceImpl.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/AgentMcpClients.java
- agent/src/main/java/com/nageoffer/ai/starlineagent/agent/tool/AgentToolCatalog.java
- bootstrap/src/main/resources/application.yaml
- docs/change-log.md
- docs/product/starline-business-slice-setup.md
- docs/server-operations.md
- frontend/src/components/agent/AgentLayout.tsx
- frontend/src/components/agent/AgentSidebar.tsx
- frontend/src/components/agent/AgentTurn.tsx
- frontend/src/components/common/DislikeReasonDialog.tsx
- frontend/src/pages/admin/AdminLayout.tsx
- frontend/src/pages/admin/badcase/BadCaseDetailDialog.tsx
- frontend/src/pages/admin/badcase/BadCasePage.tsx
- frontend/src/pages/admin/badcase/BadCaseReviewForm.tsx
- frontend/src/pages/admin/intent-tree/IntentEditPage.tsx
- frontend/src/pages/admin/intent-tree/IntentListPage.tsx
- frontend/src/pages/admin/intent-tree/IntentTreePage.tsx
- frontend/src/pages/StarlineRemindersPage.tsx
- frontend/src/router.tsx
- frontend/src/services/agentService.ts
- frontend/src/services/agentSkillService.ts
- frontend/src/services/badCaseService.ts
- frontend/src/services/intentTreeService.ts
- frontend/src/services/starlineService.ts
- frontend/src/stores/agentChatStore.ts
- frontend/src/styles/globals.css
- frontend/src/types/agent.ts
- mcp-server/pom.xml
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/common/YouComSearchMcpExecutor.java
- mcp-server/src/main/resources/application.yml
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/config/McpHttpProtocolTest.java
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/executor/common/YouComSearchMcpExecutorLiveTest.java
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/executor/common/YouComSearchMcpExecutorTest.java
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/executor/McpToolResultsTest.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/ingestion/service/impl/IntentTreeServiceImpl.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/ingestion/service/IntentTreeService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/AgentSkillController.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/controller/vo/AgentSkillToolOptionVO.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/intent/DefaultIntentClassifier.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/intent/IntentNode.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/intent/IntentNodeRegistry.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/intent/IntentResolver.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/dao/mapper/MessageFeedbackMapper.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseProperties.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRecord.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseRepository.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/controller/BadCaseController.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/feedback/job/BadCaseScanJob.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/AgentSkillAdminService.java
- rag/src/main/java/com/nageoffer/ai/starlineagent/rag/service/impl/AgentSkillAdminServiceImpl.java
- rag/src/test/java/com/nageoffer/ai/starlineagent/rag/feedback/badcase/BadCaseServiceTest.java
- resources/database/init_data_pg.sql
- resources/database/schema_pg.sql
- resources/deployment/nginx.conf
- resources/deployment/README.md
- resources/deployment/starline-app.compose.yaml
- resources/docker/server/.env.example
- resources/docker/server/application.yaml
- resources/docker/server/nginx.conf
- resources/docker/server/README.md
- resources/docker/server/releases/starline-20261022/application.yaml
- resources/docker/server/releases/starline-20261022/init_data_pg.sql
- resources/docker/server/releases/starline-20261022/nginx.conf
- resources/docker/server/releases/starline-20261022/schema_pg.sql
- resources/docker/server/releases/starline-20261022/starline-app.compose.yaml
- resources/docker/server/starline-app.compose.yaml
- resources/initializer/common/InitializationActions.java
- resources/initializer/common/InitializerDataset.java
- resources/initializer/README.md
- resources/regression/agent-compaction/README.md
- resources/regression/agent-memory-evaluation/AgentMemoryEvaluationMain.java
- resources/regression/agent-memory-evaluation/datasets/README.md
- resources/regression/agent-memory-evaluation/EvaluationSelfTestMain.java
- resources/regression/agent-memory-evaluation/README.md
- resources/regression/agent-memory/README.md
- resources/regression/agent-memory/turns.properties
- resources/regression/README.md
- starline/pom.xml
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/request/SaveStarlineEventRequest.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/vo/StarlineEventVO.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlineEventDO.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/mapper/StarlineEventMapper.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineEventService.java
- system/src/main/java/com/nageoffer/ai/starlineagent/user/config/SaTokenConfig.java

#### 删除文件

- bootstrap/src/main/resources/starline-application.yaml
- docs/releases/README.md
- docs/releases/v1.0.0.md
- docs/releases/v1.1.0.md
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/bit/BitDataSourceConfig.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/bit/BitProperties.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/config/bit/BitSchemaInitializer.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/AfterSaleDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/CartDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/CouponDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/LogisticsTraceDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/OrderDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/OrderItemDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/ProductDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/ProductSkuDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/TicketDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/entity/UserCouponDO.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/handler/BitMetaObjectHandler.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/handler/JsonbStringTypeHandler.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/AfterSaleMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/CartMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/CouponMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/LogisticsTraceMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/OrderItemMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/OrderMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/ProductMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/ProductSkuMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/TicketMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/mapper/UserCouponMapper.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/result/AfterSaleDetailResult.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/result/CartLineResult.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/result/HeldCouponResult.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/result/ProductSummaryResult.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/dao/result/SkuDetailResult.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitAfterSaleApplyMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitAfterSaleQueryMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitCartQueryMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitCartUpdateMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitCouponQueryMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitCouponRules.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitLogisticsQueryMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderAddressMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderCancelMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderCreateMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderPayMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderQueryMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitOrderReleaser.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitPendingOrderTimeoutTask.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitProductSearchMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitTicketSubmitMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/bit/BitToolSupport.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/common/WeatherMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/AssetMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/AssetRenewalMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/LeaveApplyMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/LeaveMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/MeetingRoomMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/SalesMcpExecutor.java
- mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/TicketMcpExecutor.java
- mcp-server/src/main/resources/db/bit-schema.sql
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/bit/BitSchemaScriptTest.java
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/config/bit/BitSchemaInitializerTest.java
- mcp-server/src/test/java/com/nageoffer/ai/starlineagent/mcp/executor/enterprise/MeetingRoomMcpExecutorTest.java
- README.md
- resources/initializer/bit-selection/agent-profile.properties
- resources/initializer/bit-selection/AgentProfileInitMain.java
- resources/initializer/bit-selection/AgentSkillInitMain.java
- resources/initializer/bit-selection/biz-cleanup.sql
- resources/initializer/bit-selection/biz-data/01-product.sql
- resources/initializer/bit-selection/biz-data/02-coupon.sql
- resources/initializer/bit-selection/biz-data/03-order.sql
- resources/initializer/bit-selection/biz-data/04-logistics.sql
- resources/initializer/bit-selection/biz-data/05-after-sale.sql
- resources/initializer/bit-selection/biz-data/06-cart.sql
- resources/initializer/bit-selection/biz-data/07-user-coupon.sql
- resources/initializer/bit-selection/BizDataInitMain.java
- resources/initializer/bit-selection/checksums.sha256
- resources/initializer/bit-selection/cleanup.sql
- resources/initializer/bit-selection/CleanupMain.java
- resources/initializer/bit-selection/docs/knowledge/faq/error-code/常见错误提示与处理.md
- resources/initializer/bit-selection/docs/knowledge/faq/trouble/AirPods与AppleWatch常见故障排查.md
- resources/initializer/bit-selection/docs/knowledge/faq/trouble/iPhone常见故障排查.md
- resources/initializer/bit-selection/docs/knowledge/faq/trouble/Mac常见故障排查.md
- resources/initializer/bit-selection/docs/knowledge/manual/app/比特严选App使用指南.md
- resources/initializer/bit-selection/docs/knowledge/manual/app/Apple账户与iCloud基础.md
- resources/initializer/bit-selection/docs/knowledge/manual/network/连接WiFi与网络异常排查.md
- resources/initializer/bit-selection/docs/knowledge/manual/network/设备联动.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/耳机用户手册.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/平板用户手册.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/日常保养与耗材更换.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/手机操作进阶.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/手机用户手册.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/系统更新与版本管理.md
- resources/initializer/bit-selection/docs/knowledge/manual/product/新设备开机设置与数据迁移.md
- resources/initializer/bit-selection/docs/knowledge/policy/invoice-vip/发票开具规则.md
- resources/initializer/bit-selection/docs/knowledge/policy/invoice-vip/会员积分规则与到期提醒.md
- resources/initializer/bit-selection/docs/knowledge/policy/invoice-vip/会员权益体系.md
- resources/initializer/bit-selection/docs/knowledge/policy/logistics/发货时效与配送范围.md
- resources/initializer/bit-selection/docs/knowledge/policy/logistics/配送服务.md
- resources/initializer/bit-selection/docs/knowledge/policy/logistics/物流异常处理.md
- resources/initializer/bit-selection/docs/knowledge/policy/promotion/促销活动与优惠券规则.md
- resources/initializer/bit-selection/docs/knowledge/policy/return/7天无理由退换政策.md
- resources/initializer/bit-selection/docs/knowledge/policy/return/价保政策.md
- resources/initializer/bit-selection/docs/knowledge/policy/return/退换货流程与申请方式.md
- resources/initializer/bit-selection/docs/knowledge/policy/return/退换货条件.md
- resources/initializer/bit-selection/docs/knowledge/policy/warranty/保修凭证要求.md
- resources/initializer/bit-selection/docs/knowledge/policy/warranty/保修政策总则.md
- resources/initializer/bit-selection/docs/knowledge/policy/warranty/各品类保修期与保修范围.md
- resources/initializer/bit-selection/docs/knowledge/policy/warranty/过保维修政策与流程.md
- resources/initializer/bit-selection/docs/knowledge/policy/warranty/维修服务报价表.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/AIRPODS-5.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/AIRPODS-MAX-2.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/AIRPODS-PRO-3.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/AIRTAG.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/HOMEPOD-MINI.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/HOMEPOD.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IMAC.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-11.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-AIR-11.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-AIR-13.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-MINI.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-PRO-11.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPAD-PRO-13.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-16.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-17.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-17E.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-18-PRO-MAX.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-18-PRO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-AIR.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/IPHONE-DUO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/MAC-MINI.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/MAC-STUDIO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/MACBOOK-AIR.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/MACBOOK-NEO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/MACBOOK-PRO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/STUDIO-DISPLAY-XDR.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/STUDIO-DISPLAY.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/VISION-PRO.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/WATCH-SE-3.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/WATCH-SERIES-12.md
- resources/initializer/bit-selection/docs/knowledge/product/detail/WATCH-ULTRA-4.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/通用配件选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/AirTag配件选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/AppleVisionPro配件选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/AppleWatch表带选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/iPad配件选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/iPhone配件选购指南.md
- resources/initializer/bit-selection/docs/knowledge/product/guide/Mac配件选购指南.md
- resources/initializer/bit-selection/DocumentInitMain.java
- resources/initializer/bit-selection/InitializeMain.java
- resources/initializer/bit-selection/initializer.properties
- resources/initializer/bit-selection/intents/100-support.properties
- resources/initializer/bit-selection/intents/110-support-presale.properties
- resources/initializer/bit-selection/intents/111-support-presale-recommend.properties
- resources/initializer/bit-selection/intents/112-support-presale-spec.properties
- resources/initializer/bit-selection/intents/113-support-presale-compat.properties
- resources/initializer/bit-selection/intents/114-support-presale-promotion.properties
- resources/initializer/bit-selection/intents/120-support-usage.properties
- resources/initializer/bit-selection/intents/121-support-usage-operation.properties
- resources/initializer/bit-selection/intents/122-support-usage-network.properties
- resources/initializer/bit-selection/intents/123-support-usage-app.properties
- resources/initializer/bit-selection/intents/124-support-usage-linkage.properties
- resources/initializer/bit-selection/intents/125-support-usage-maintenance.properties
- resources/initializer/bit-selection/intents/126-support-usage-firmware.properties
- resources/initializer/bit-selection/intents/130-support-aftersale.properties
- resources/initializer/bit-selection/intents/131-support-aftersale-warranty.properties
- resources/initializer/bit-selection/intents/132-support-aftersale-return.properties
- resources/initializer/bit-selection/intents/133-support-aftersale-logistics.properties
- resources/initializer/bit-selection/intents/134-support-aftersale-invoice.properties
- resources/initializer/bit-selection/intents/200-shop.properties
- resources/initializer/bit-selection/intents/210-shop-product.properties
- resources/initializer/bit-selection/intents/220-shop-cart.properties
- resources/initializer/bit-selection/intents/230-shop-coupon.properties
- resources/initializer/bit-selection/intents/240-shop-cart-update.properties
- resources/initializer/bit-selection/intents/250-shop-order-create.properties
- resources/initializer/bit-selection/intents/260-shop-order-pay.properties
- resources/initializer/bit-selection/intents/300-order.properties
- resources/initializer/bit-selection/intents/310-order-query.properties
- resources/initializer/bit-selection/intents/320-order-logistics.properties
- resources/initializer/bit-selection/intents/330-order-after-sale.properties
- resources/initializer/bit-selection/intents/340-order-after-sale-apply.properties
- resources/initializer/bit-selection/intents/350-order-cancel.properties
- resources/initializer/bit-selection/intents/360-order-address-change.properties
- resources/initializer/bit-selection/intents/400-feedback.properties
- resources/initializer/bit-selection/intents/410-feedback-fault.properties
- resources/initializer/bit-selection/intents/420-feedback-suggestion.properties
- resources/initializer/bit-selection/intents/430-feedback-complaint.properties
- resources/initializer/bit-selection/intents/440-feedback-handoff.properties
- resources/initializer/bit-selection/intents/500-chat.properties
- resources/initializer/bit-selection/intents/510-chat-greeting.properties
- resources/initializer/bit-selection/intents/520-chat-about-bot.properties
- resources/initializer/bit-selection/intents/530-chat-out-of-scope.properties
- resources/initializer/bit-selection/intents/540-chat-current-date.properties
- resources/initializer/bit-selection/IntentTreeInitMain.java
- resources/initializer/bit-selection/knowledge-bases.properties
- resources/initializer/bit-selection/KnowledgeBaseInitMain.java
- resources/initializer/bit-selection/PreflightMain.java
- resources/initializer/bit-selection/prompts/agent-main.txt
- resources/initializer/bit-selection/prompts/context-compaction.txt
- resources/initializer/bit-selection/prompts/conversation-summary.txt
- resources/initializer/bit-selection/prompts/memory-extraction.txt
- resources/initializer/bit-selection/prompts/system-chat.txt
- resources/initializer/bit-selection/questions.properties
- resources/initializer/bit-selection/README.md
- resources/initializer/bit-selection/SampleQuestionInitMain.java
- resources/initializer/bit-selection/skills/010-after-sale-apply.properties
- resources/initializer/bit-selection/skills/020-order-change.properties
- resources/initializer/bit-selection/skills/030-fault-diagnosis.properties
- resources/initializer/bit-selection/skills/040-order-checkout.properties
- resources/initializer/bit-selection/skills/050-iot-bundle.properties
- resources/initializer/bit-selection/skills/after-sale-apply.md
- resources/initializer/bit-selection/skills/fault-diagnosis.md
- resources/initializer/bit-selection/skills/iot-bundle.md
- resources/initializer/bit-selection/skills/order-change.md
- resources/initializer/bit-selection/skills/order-checkout.md
- resources/initializer/bit-selection/ValidateDatasetMain.java
- resources/initializer/bit-selection/VerifyMain.java
- resources/initializer/bit-selection/WarmupMain.java
- resources/initializer/enterprise-knowledge-base/AgentSkillInitMain.java
- resources/initializer/enterprise-knowledge-base/checksums.sha256
- resources/initializer/enterprise-knowledge-base/cleanup.sql
- resources/initializer/enterprise-knowledge-base/CleanupMain.java
- resources/initializer/enterprise-knowledge-base/docs/knowledge/biz/biz-ins/互联网保险系统数据安全规范.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/biz/biz-oa/OA系统数据安全规范文档.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/group-finance/开票信息.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/hr/公司规章制度.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/hr/人事制度.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/hr/薪资与福利政策.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/hr/员工培训.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/hr/招聘信息.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/it/IT支持.md
- resources/initializer/enterprise-knowledge-base/docs/knowledge/group/it/IT资产管理.md
- resources/initializer/enterprise-knowledge-base/DocumentInitMain.java
- resources/initializer/enterprise-knowledge-base/InitializeMain.java
- resources/initializer/enterprise-knowledge-base/initializer.properties
- resources/initializer/enterprise-knowledge-base/intents/000-group.properties
- resources/initializer/enterprise-knowledge-base/intents/010-group-hr.properties
- resources/initializer/enterprise-knowledge-base/intents/011-group-hr-leave.properties
- resources/initializer/enterprise-knowledge-base/intents/012-group-hr-leave-apply.properties
- resources/initializer/enterprise-knowledge-base/intents/020-group-it.properties
- resources/initializer/enterprise-knowledge-base/intents/021-group-it-asset.properties
- resources/initializer/enterprise-knowledge-base/intents/022-group-it-asset-renewal.properties
- resources/initializer/enterprise-knowledge-base/intents/030-group-finance.properties
- resources/initializer/enterprise-knowledge-base/intents/040-group-finance-invoice.properties
- resources/initializer/enterprise-knowledge-base/intents/050-group-admin.properties
- resources/initializer/enterprise-knowledge-base/intents/051-group-admin-meeting-query.properties
- resources/initializer/enterprise-knowledge-base/intents/052-group-admin-meeting-book.properties
- resources/initializer/enterprise-knowledge-base/intents/100-biz.properties
- resources/initializer/enterprise-knowledge-base/intents/110-biz-oa.properties
- resources/initializer/enterprise-knowledge-base/intents/120-biz-oa-intro.properties
- resources/initializer/enterprise-knowledge-base/intents/130-biz-oa-security.properties
- resources/initializer/enterprise-knowledge-base/intents/140-biz-ins.properties
- resources/initializer/enterprise-knowledge-base/intents/150-biz-ins-intro.properties
- resources/initializer/enterprise-knowledge-base/intents/160-biz-ins-arch.properties
- resources/initializer/enterprise-knowledge-base/intents/170-biz-ins-security.properties
- resources/initializer/enterprise-knowledge-base/intents/200-sales.properties
- resources/initializer/enterprise-knowledge-base/intents/210-sales-data.properties
- resources/initializer/enterprise-knowledge-base/intents/300-sys.properties
- resources/initializer/enterprise-knowledge-base/intents/310-sys-welcome.properties
- resources/initializer/enterprise-knowledge-base/intents/320-sys-about-bot.properties
- resources/initializer/enterprise-knowledge-base/intents/330-sys-feedback.properties
- resources/initializer/enterprise-knowledge-base/intents/340-sys-current-date.properties
- resources/initializer/enterprise-knowledge-base/intents/400-support.properties
- resources/initializer/enterprise-knowledge-base/intents/410-support-ticket.properties
- resources/initializer/enterprise-knowledge-base/IntentTreeInitMain.java
- resources/initializer/enterprise-knowledge-base/knowledge-bases.properties
- resources/initializer/enterprise-knowledge-base/KnowledgeBaseInitMain.java
- resources/initializer/enterprise-knowledge-base/PreflightMain.java
- resources/initializer/enterprise-knowledge-base/prompts/finance-invoice.txt
- resources/initializer/enterprise-knowledge-base/prompts/sys-feedback.txt
- resources/initializer/enterprise-knowledge-base/questions.properties
- resources/initializer/enterprise-knowledge-base/SampleQuestionInitMain.java
- resources/initializer/enterprise-knowledge-base/skills/010-leave-apply.properties
- resources/initializer/enterprise-knowledge-base/skills/020-meeting-room-booking.properties
- resources/initializer/enterprise-knowledge-base/skills/leave-apply.md
- resources/initializer/enterprise-knowledge-base/skills/meeting-room-booking.md
- resources/initializer/enterprise-knowledge-base/ValidateDatasetMain.java
- resources/initializer/enterprise-knowledge-base/VerifyMain.java
- resources/initializer/enterprise-knowledge-base/WarmupMain.java
- resources/regression/agent-concurrency/ConcurrencyGateProbeMain.java
- resources/regression/agent-concurrency/ConcurrencyProbe.java
- resources/regression/agent-concurrency/ConcurrencyRegressionMain.java
- resources/regression/agent-concurrency/ConcurrencyReportMain.java
- resources/regression/agent-concurrency/ConcurrencySseClient.java
- resources/regression/agent-concurrency/ConcurrencySseSelfTestMain.java
- resources/regression/agent-concurrency/README.md
- resources/regression/agent-concurrency/regression.properties
- resources/regression/agent-concurrency/run.sh
- resources/regression/agent-memory-evaluation/datasets/bit-selection.json
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/config/StarlinePropertiesConfiguration.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineMailProperties.java
- starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlineReminderScheduler.java

### 推送状态

目标为 origin/main。全量改动已本地提交；本次推送按此清单执行，推送结果以 Git 命令回执确认。

暂存核对：原盘点的 516 个路径之外，本记录文件自身也随提交更新；展开重命名后的完整提交范围为 517 个文件路径。Git 暂存摘要为新增 110、修改 93、删除 270、重命名 22 组（495 条 name-status 记录）。

- 本次变更记录文件自身：`docs/git-push-record.md`，包含以上全量清单与范围说明。


## 2026-10-25 本地改动推送记录

### 推送范围

用户授权将当前本地 `main` 相对 `origin/main` 的项目改动推送到远端。推送前保留应用代码、项目配置、初始化/回归资源及项目文档；服务器命令和操作明细留在本地。`docs/server-operations.md` 与 `docs/server-operations/2026-10-25.md` 不纳入本次提交。Git 差异中没有 `target/`、`dist/`、`node_modules/`、JAR、压缩包等生成构建产物；`.local-backups/` 由忽略规则排除。

### 本次新增工作区改动摘要

- `pom.xml`：排除 AgentScope Core 传递引入、项目源码未使用的 `sqlite-jdbc`。
- `framework/pom.xml`：排除 RocketMQ 依赖链中未使用的 `netty-codec-native-quic` 原生 QUIC 组件。
- `docs/change-log.md`：更新 2026-10-25 项目改动索引。
- `docs/change-log/2026-10-25.md`：记录本次项目瘦身、验证与发布概况。
- `docs/maintenance/project-artifact-policy.md`：新增构建产物、源码备份与凭据的本地维护策略。
- `.workbuddy-ai/memory/项目瘦身方案.md`：归档项目体积分析与分阶段瘦身方案。
- `.workbuddy-ai/memory/2026-10-25.md`：补充本次项目分析和处理摘要。
- `docs/git-push-record.md`：登记本次推送范围与排除项。

此前已提交但尚未推送的项目代码变更，逐项内容与路径清单见本文件前面的 2026-10-24、2026-10-25 推送记录及 `docs/change-log/2026-10-25.md`。推送前将复核实际暂存路径并再次确认排除项。

### 推送状态

目标：`origin/main`。推送结果将在 Git 命令返回后补记。服务器操作日志及生成构建产物不进入此次提交。

暂存复核结果：暂存区共 497 个路径；服务器操作记录路径为 0；`target/`、`dist/`、`node_modules/`、JAR、ZIP、TAR、GZ 等生成构建产物为 0。`docs/server-operations.md` 和 `docs/server-operations/2026-10-25.md` 保持在本地工作区，未暂存。

推送执行结果：提交 `eb9b1e75` 已在本地生成。HTTPS 推送因 GitHub 连接重置失败，HTTP/1.1 重试超时；只读远端查询返回空响应，SSH 端口 22 也超时，因此无法确认远端已更新。服务器操作日志仍只保留在本地。待网络恢复后可再次执行 `git push origin main`；当前提交未推送。

## 2026-10-25 推送重试补充

抓取远端后，`origin/main` 位于 `7956e49c`；该提交与本地筛选分支并行，树差异为 11 个路径，其中 2 个是服务器操作记录。为避免改写远端历史，本次重试将以远端当前提交为父提交，只提交项目瘦身方案、变更摘要、维护策略及 Maven 依赖配置；服务器操作记录、部署脚本更新和生成构建产物不纳入本次提交。此前尚未推送的筛选提交保留在本地备份分支。
### 本次重试结果

提交 `6bd96f4a`（项目瘦身文档与 Maven 依赖配置）已发起推送，但 GitHub 连接在返回回执前重置；随后 `git fetch origin` 连接超时。因此当前无法确认远端是否已接收该提交。网络恢复后先 `git fetch origin` 并核对 `origin/main` 与本地 `main`，确认提交状态后再决定是否重试，避免重复推送。服务器操作记录与部署脚本更新仍保留在本地。

## 2026-10-25 远端复核与推送重试

GitHub 页面截图显示 `main` 最新提交仍为 `7956e49`。本地 `main` 当前包含其后续项目瘦身提交 `6bd96f4a`，此前推送回执因网络中断未确认；本次按用户先前授权再次推送。

### 本次推送范围

- `6bd96f4a`：`.workbuddy-ai/memory/项目瘦身方案.md`、`docs/change-log.md`、`docs/change-log/2026-10-25.md`、`docs/git-push-record.md`、`docs/maintenance/project-artifact-policy.md`、`framework/pom.xml`、`pom.xml`。
- 本记录更新：补充远端复核依据和本次重试范围。
- 排除本地未提交的服务器操作记录、部署脚本改动、个人工作记忆记录及构建产物。

推送前将核对待推送提交与文件范围；结果在命令返回后补记。

推送结果：`git push origin main` 再次因 GitHub HTTPS 连接重置（`OpenSSL SSL_read: Connection was reset, errno 10054`）未返回成功回执。远端页面在本次重试前显示仍为 `7956e49`；本地提交 `6bd96f4a` 与 `f4e2606e` 尚不能确认已到达远端，等待网络恢复后再通过 `git fetch` 核实。服务器操作记录、部署脚本改动和构建产物仍未纳入提交。

## 2026-10-25 SSH over 443 推送准备

用户在 Windows CMD 通过 `ssh.github.com:443` 成功以 GitHub 账号 `LntanoC` 完成 SSH 认证。随后只读查询 SSH URL 得到 `main`=`7956e49c6f635700ce9d474f9ca8016dbe6f26d9`，确认此前 HTTPS 失败后远端仍未更新。此次改用 SSH over 443 推送，不更改 `origin` 配置。

### 本次待推送提交与范围

- `6bd96f4a`：项目瘦身文档、Maven 依赖排除配置及变更记录。
- `f4e2606e`：Git 推送范围与记录。
- 本提交：补充 SSH 认证、远端核验及推送准备记录。
- 仍排除本地服务器操作记录、部署脚本改动、个人工作记忆记录和构建产物。

推送结果待 SSH over 443 命令返回后补记。

### SSH over 443 推送结果

推送成功：`7956e49c..ac1c418d main -> main`。随后通过同一 SSH URL 执行只读核验，远端 `main`=`ac1c418dd8c0e887bf6e2dcdfa626c317aa5bca1`，与本地 `HEAD` 完全一致。服务器操作记录、部署脚本改动、个人工作记忆记录及构建产物仍保留在本地，未随本次推送上传。

## 2026-10-26 移除服务器操作日志与一次性部署脚本

### 推送范围

按用户确认，仅从 Git 跟踪中移除服务器操作日志和一次性部署脚本；文件副本保留在本地，并在 `.gitignore` 增加规则防止重新跟踪。保留 Compose、Nginx、服务栈、数据库初始化/迁移配置以及其他可复现部署所需配置。

### 文件摘要

- `.gitignore`：忽略 `docs/server-operations*`、`scripts/deploy-*` 和历史发布目录中的 `deploy-*.sh`。
- `docs/change-log.md`：增加 2026-10-26 变更索引。
- `docs/change-log/2026-10-26.md`：记录 21 个文件从 Git 跟踪移除并保留本地的范围。
- `docs/git-push-record.md`：登记本次删除范围和待推送状态。
- 删除 Git 跟踪：`docs/server-operations.md`、`docs/server-operations/2026-10-21.md` 至 `2026-10-25.md`；`scripts/deploy-agent-audit-server.sh`、`scripts/deploy-badcase-candidate-pool.ps1`、`scripts/deploy-badcase-candidate-pool.sh`、`scripts/deploy-badcase-mode-hardening.sh`、`scripts/deploy-feedback-withdrawal.sh`、`scripts/deploy-mcp-tool-rag.sh`、`scripts/deploy-starline-ical.sh`、`scripts/deploy-workflow-retirement.sh`；`resources/docker/server/releases/starline-20261022/deploy-agent-audit-server.sh`、`deploy-badcase-candidate-pool.sh`、`deploy-badcase-mode-hardening.sh`、`deploy-feedback-withdrawal.sh`、`deploy-mcp-server.sh`、`deploy-starline-ical.sh`、`deploy-workflow-retirement.sh`。

推送前核对暂存文件，仅包含上述 21 个删除和 4 个记录/忽略规则文件；构建产物不纳入。推送结果待命令返回后补记。

### 推送结果

提交 `9ac01792` 已通过 SSH over 443 推送成功（`ac1c418d..9ac01792 main -> main`）。远端 `main` 查询结果为 `9ac01792f5ca2791d73b72fe96a713f05a9f2857`，与本地提交一致。远端当前树中服务器操作日志和一次性部署脚本为 0 个；21 个原文件均保留在本地。Compose、Nginx 等基础设施配置仍在 Git 跟踪中。

## 2026-10-26 全量本地变更推送准备

本次按用户授权推送当前项目全部已暂存改动。主要内容：

- 演出查询业务：MCP 查询结果支持结构化演出卡片；查询执行器接入官网白名单校验，并从管理接口读取实时生效的平台来源配置。
- 白名单管理：调整管理员配置与启停逻辑，移除旧审核状态模型；补充服务端内部配置接口及权限控制。
- 数据库与初始化：新增 Agent 提示词兼容、演出卡片、白名单管理迁移；同步初始化数据、schema、Starline initializer 资源及服务器发布快照。
- 文档整理：增加 `docs/README.md` 和部署快速启动手册，将数据库、部署、初始化、Bad Case、审计追踪、可观测性、测试等说明归入 `docs/` 对应分类，并更新文档链接和变更日志。
- 项目清理：删除旧电商知识样例与不再保留的项目工作记忆资料。

暂存区检查：102 个变更路径；`git diff --cached --check` 通过。未纳入本次提交的有本地服务器操作日志、忽略的构建产物、环境变量文件和凭据。提交前再次核对完整暂存清单如下。

### 暂存路径清单
    D	.workbuddy-ai/memory/2026-10-23.md
    D	.workbuddy-ai/memory/2026-10-24.md
    D	.workbuddy-ai/memory/2026-10-25.md
    D	.workbuddy-ai/memory/MCP调用改造方案-ToolRAG与懒加载.md
    D	.workbuddy-ai/memory/MEMORY.md
    D	.workbuddy-ai/memory/agent-链路追踪自建方案.md
    D	.workbuddy-ai/memory/badcase-分析链路实施方案.md
    D	.workbuddy-ai/memory/badcase-逻辑详解.md
    D	.workbuddy-ai/memory/badcase多入口改造技术方案.md
    D	.workbuddy-ai/memory/badcase统一候选池与分级入口技术方案.md
    D	.workbuddy-ai/memory/badcase规则Agent化改造方案.md
    D	.workbuddy-ai/memory/create_reminder工具设计.md
    D	.workbuddy-ai/memory/iCal日历订阅改造方案.md
    D	.workbuddy-ai/memory/mcp-server精简与演出查询实现方案.md
    D	.workbuddy-ai/memory/workflow-下线改造技术方案.md
    D	.workbuddy-ai/memory/已批未宣场景改造方案.md
    D	.workbuddy-ai/memory/方案B-彻底清理电商资产与星序落位.md
    D	.workbuddy-ai/memory/日志持久化与降噪技术方案.md
    D	.workbuddy-ai/memory/演出查询MCP工具设计方案-v2.md
    D	.workbuddy-ai/memory/演出查询MCP工具设计方案.md
    D	.workbuddy-ai/memory/票务查询MCP方案-豆包模式.md
    D	.workbuddy-ai/memory/简历改写-大厂Agent岗.md
    D	.workbuddy-ai/memory/自研ToolSearch实现方案.md
    D	.workbuddy-ai/memory/项目描述-RAG与ToolRAG与评测.md
    D	.workbuddy-ai/memory/项目瘦身方案.md
    A	docs/README.md
    R100	docs/agent-audit-usage.md	docs/agent/audit-usage.md
    R100	docs/bad-case-candidate-pool-usage.md	docs/bad-case/candidate-pool-usage.md
    R097	docs/design/bad-case-analysis.md	docs/bad-case/design/analysis.md
    R100	docs/design/bad-case-candidate-pool.md	docs/bad-case/design/candidate-pool.md
    R100	docs/design/offline-bad-case-plan.md	docs/bad-case/design/offline-analysis.md
    M	docs/change-log.md
    M	docs/change-log/2026-10-26.md
    R051	resources/database/README.md	docs/database/postgres.md
    A	docs/deployment/README.md
    R100	resources/docker/graphrag/README.md	docs/deployment/optional-stacks/graphrag.md
    R093	resources/docker/lightweight/README.md	docs/deployment/optional-stacks/lightweight.md
    A	docs/deployment/quick-start.md
    R100	resources/docker/server/README.md	docs/deployment/server-config.md
    M	docs/design/agent-audit-tracing-design.md
    M	docs/examples/pdf-ingestion-example.md
    M	docs/git-push-record.md
    R086	resources/initializer/README.md	docs/initialization/README.md
    R062	resources/initializer/starline-events/README.md	docs/initialization/starline-events.md
    R100	docs/dashboard-statistics.md	docs/observability/dashboard-statistics.md
    M	docs/product/starline-business-slice-setup.md
    R097	frontend/TESTING.md	docs/testing/frontend.md
    R092	rag/src/test/resources/fixtures/chunking/README.md	docs/testing/rag-chunking-fixtures.md
    R090	resources/regression/README.md	docs/testing/regression/README.md
    R100	resources/regression/agent-compaction/README.md	docs/testing/regression/agent-compaction/README.md
    R100	resources/regression/agent-long-term-memory/README.md	docs/testing/regression/agent-long-term-memory/README.md
    R097	resources/regression/agent-memory-audit/README.md	docs/testing/regression/agent-memory-audit/README.md
    R100	resources/regression/agent-memory-audit/results/2026-09-27/management-results.md	docs/testing/regression/agent-memory-audit/results/2026-09-27/management-results.md
    R085	resources/regression/agent-memory-audit/results/2026-09-27/report.md	docs/testing/regression/agent-memory-audit/results/2026-09-27/report.md
    R100	resources/regression/agent-memory-audit/results/2026-09-27/revival-results.md	docs/testing/regression/agent-memory-audit/results/2026-09-27/revival-results.md
    R089	resources/regression/agent-memory-audit/results/2026-09-27/summary-results.md	docs/testing/regression/agent-memory-audit/results/2026-09-27/summary-results.md
    R100	resources/regression/agent-memory-evaluation/README.md	docs/testing/regression/agent-memory-evaluation/README.md
    R100	resources/regression/agent-memory-evaluation/datasets/README.md	docs/testing/regression/agent-memory-evaluation/datasets/README.md
    R100	resources/regression/agent-memory/README.md	docs/testing/regression/agent-memory/README.md
    R100	resources/regression/agent-run-gate-audit/README.md	docs/testing/regression/agent-run-gate-audit/README.md
    M	frontend/src/components/agent/AgentTurn.tsx
    A	frontend/src/components/agent/StarlineEventCards.tsx
    M	frontend/src/pages/admin/starline/PlatformSourceRegistryPage.tsx
    M	frontend/src/services/starlinePlatformSourceService.ts
    M	mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchMcpExecutor.java
    M	mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/ArtistEventSearchProperties.java
    A	mcp-server/src/main/java/com/nageoffer/ai/starlineagent/mcp/executor/starline/OfficialSourceRegistryClient.java
    M	resources/database/init_data_pg.sql
    A	resources/database/migrations/20261026_starline_agent_prompt_compat.sql
    A	resources/database/migrations/20261026_starline_event_cards.sql
    A	resources/database/migrations/20261026_starline_whitelist_admin_control.sql
    M	resources/database/schema_pg.sql
    D	resources/deployment/README.md
    A	resources/docker/server/releases/starline-20261022/20261026_starline_agent_prompt_compat.sql
    A	resources/docker/server/releases/starline-20261022/20261026_starline_event_cards.sql
    M	resources/docker/server/releases/starline-20261022/init_data_pg.sql
    M	resources/docker/server/releases/starline-20261022/nginx.conf
    M	resources/docker/server/releases/starline-20261022/schema_pg.sql
    M	resources/docker/server/releases/starline-20261022/starline-app.compose.yaml
    D	resources/docs/knowledge/biz/biz-ins/互联网保险系统数据安全规范.md
    D	resources/docs/knowledge/biz/biz-oa/OA系统数据安全规范文档.md
    D	resources/docs/knowledge/group/group-finance/开票信息.md
    D	resources/docs/knowledge/group/hr/人事制度.md
    D	resources/docs/knowledge/group/hr/公司规章制度.md
    D	resources/docs/knowledge/group/hr/员工培训.md
    D	resources/docs/knowledge/group/hr/招聘信息.md
    D	resources/docs/knowledge/group/hr/薪资与福利政策.md
    D	resources/docs/knowledge/group/it/IT支持.md
    M	resources/initializer/starline-events/agent-profile.properties
    M	resources/initializer/starline-events/checksums.sha256
    M	resources/initializer/starline-events/mcp-tools/search_artist_events.properties
    M	resources/initializer/starline-events/prompts/agent-main.txt
    M	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/StarlinePlatformSourceAdminController.java
    A	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/StarlinePlatformSourceInternalController.java
    M	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/request/SaveStarlinePlatformSourceRequest.java
    R083	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/request/ReviewStarlinePlatformSourceRequest.java	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/request/SetStarlinePlatformSourceEnabledRequest.java
    M	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/controller/admin/vo/StarlinePlatformSourceVO.java
    M	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlinePlatformSourceDO.java
    D	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/entity/StarlineSourceReviewDO.java
    D	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/dao/mapper/StarlineSourceReviewMapper.java
    M	starline/src/main/java/com/nageoffer/ai/starlineagent/starline/service/StarlinePlatformSourceService.java
    M	system/src/main/java/com/nageoffer/ai/starlineagent/user/config/SaTokenConfig.java

## 2026-10-27：注册、RAG 与提醒功能更新

### 本次推送说明

本次推送包含功能提交 `4ce56386` 和本中文说明。本次功能提交包含 31 个项目文件，其中修改 24 个、新增 7 个；本说明会随推送一并提交。中文摘要如下：

- **新增用户注册**：增加普通用户注册 API 和注册页面；新用户只能获得 `USER` 角色，注册成功后自动登录；密码使用 BCrypt 保存，并兼容旧明文账户登录后的渐进升级；移除登录页预填的管理员凭据。
- **完善 RAG 检索**：补齐 PostgreSQL 和 Milvus 的 metadata 标量过滤；修正 PostgreSQL 检索事务内的向量参数设置；更新意图检索提示片段与初始化校验配置，避免导入时覆盖同名文档。
- **优化提醒展示**：演出提醒卡片在手机端显示添加提醒入口，在桌面端提示使用手机操作。
- **整理初始化与文档**：修正退役 Trace 表的初始化预检引用，清除主 schema 中重复的 iCal 表结构，并记录本轮修改与部署结果。

### 新增文件（7 个）

- `frontend/src/pages/RegisterPage.tsx`：注册表单和注册成功后的自动进入聊天流程。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/vector/MetadataFilterSupport.java`：metadata 条件校验、JSON 序列化及 Milvus 表达式构造。
- `resources/initializer/starline-events/prompts/intent-snippets/platform-capability.txt`：平台能力意图的检索提示片段。
- `resources/initializer/starline-events/prompts/intent-snippets/reminder-rules.txt`：提醒规则意图的检索提示片段。
- `resources/initializer/starline-events/prompts/intent-snippets/source-check.txt`：来源核验意图的检索提示片段。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/controller/request/RegisterRequest.java`：注册请求模型。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/service/UserPasswordService.java`：BCrypt 密码编码、旧密码验证和升级判定。

### 修改文件（24 个）

- `docs/change-log.md`、`docs/change-log/2026-10-26.md`：补充变更索引、RAG/提醒/注册实现及构建部署结果。
- `docs/initialization/starline-events.md`：补充分步初始化说明。
- `frontend/src/components/agent/StarlineEventCards.tsx`：按设备宽度调整提醒入口显示。
- `frontend/src/pages/LoginPage.tsx`：增加注册入口并移除预填管理员账号密码。
- `frontend/src/router.tsx`：注册 `/register` 路由。
- `frontend/src/services/authService.ts`：接入注册 API。
- `frontend/src/stores/authStore.ts`：保存注册后的登录状态。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/retrieval/RetrieveRequest.java`：声明 metadata 标量过滤条件。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/vector/MilvusVectorRetrieverService.java`：将过滤条件下推到 Milvus 查询。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/vector/PgVectorRetrieverService.java`：参数化 JSONB 过滤，并在只读事务中设置检索参数。
- `rag/src/main/java/com/nageoffer/ai/starlineagent/rag/core/vector/PgVectorStoreAdmin.java`：统一 HNSW 索引名并按当前 schema 校验。
- `resources/database/schema_pg.sql`：删除重复的 iCal 日历 token 表定义。
- `resources/initializer/common/InitializationActions.java`：移除对已退役 Trace 表的预检引用。
- `resources/initializer/starline-events/checksums.sha256`：同步初始化资源校验和。
- `resources/initializer/starline-events/initializer.properties`：设置同名文档默认不覆盖。
- `resources/initializer/starline-events/intents/210-reminder-rules.properties`：使用默认召回预算并加载提醒规则提示片段。
- `resources/initializer/starline-events/intents/300-source-check.properties`：使用默认召回预算并加载来源核验提示片段。
- `resources/initializer/starline-events/intents/410-platform-capability.properties`：使用默认召回预算并加载平台能力提示片段。
- `system/pom.xml`：添加 Spring Security Crypto 依赖。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/controller/AuthController.java`：公开 `POST /auth/register`。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/service/AuthService.java`：增加注册服务定义。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/service/impl/AuthServiceImpl.java`：注册校验、普通用户创建、重复用户名处理、注册后登录及旧密码升级。
- `system/src/main/java/com/nageoffer/ai/starlineagent/user/service/impl/UserServiceImpl.java`：让管理端建号、重置密码和用户改密保存哈希。

### 本次排除项

- `resources/docker/server/releases/starline-20261022/schema_pg.sql`：服务器发布快照，按要求不推送。
- `.workbuddy-ai/`：本地工作记忆，不属于项目交付文件。
- 服务器操作日志、`target/` 构建产物及临时部署包：均不进入 Git。
