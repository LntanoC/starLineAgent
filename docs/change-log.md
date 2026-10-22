# 项目变更记录

## 2026-10-22：星序活动信息与邮箱提醒首个业务切片

本记录对应本地 Git 工作区当前未提交的星序 MVP 变更。以下逐项说明文件、类/方法和职责。

### 后端业务模块

- starline/pom.xml：新增 Maven 子模块 starline，依赖 system 与 Spring Mail，作为星序活动信息和提醒业务域。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/config/StarlinePropertiesConfiguration.java：StarlinePropertiesConfiguration 启用 StarlineMailProperties 配置绑定，使邮件发件配置可注入。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/controller/StarlineEventController.java：StarlineEventController 提供活动提醒 REST API；listMine() 查询当前登录用户的记录，create(request) 创建活动/提醒，cancelReminder(id) 取消提醒。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/controller/request/SaveStarlineEventRequest.java：SaveStarlineEventRequest 定义创建请求字段，包括艺人、活动、信息状态、来源确认、开票/提醒时间和邮箱。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/controller/vo/StarlineEventVO.java：StarlineEventVO 定义返回前端的活动及提醒状态数据结构。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/dao/entity/StarlineEventDO.java：StarlineEventDO 映射数据库表 t_starline_event_reminder，保存用户、活动、来源和提醒投递状态。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/dao/mapper/StarlineEventMapper.java：StarlineEventMapper 提供 MyBatis-Plus 数据访问；claimForSending(id, now) 仅在状态仍为 PENDING 时原子改为 SENDING，避免并发重复发送。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/service/StarlineEventService.java：StarlineEventService 承担业务校验和状态流转。listMine() 按当前用户筛选并按创建时间倒序查询；create(request) 校验信息状态、必填字段、来源 URL、来源确认、未来开票/提醒时间及邮箱，待公布记录只保存状态、不创建提醒；cancelReminder(id) 仅允许所有者取消仍处于待发送状态的提醒；findDue(now) 查询到期的待提醒记录；claim(id) 抢占发送权；mark(id, status, sentAt) 更新投递结果；私有方法 required、trim、validateUrl、parseTime、toVO 分别负责必填/空白处理、HTTP(S) 来源校验、时间解析和实体到响应对象转换。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/service/StarlineMailProperties.java：StarlineMailProperties 绑定 starline.email 配置，提供发件地址。
- starline/src/main/java/com/nageoffer/ai/ragent/starline/service/StarlineReminderScheduler.java：StarlineReminderScheduler.dispatchDueReminders() 按配置周期扫描到期记录、抢占发送、投递包含活动和来源链接的邮件，并将状态更新为 SENT 或 FAILED；仅当 starline.email.enabled=true 时启用。

### 后端装配与配置

- pom.xml：把 starline 加入根 Maven reactor，参与统一构建。
- bootstrap/pom.xml：让应用启动模块依赖 starline，运行时装载该业务模块。
- bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java：在应用的 Mapper 扫描范围增加 com.nageoffer.ai.ragent.starline.dao.mapper，让星序 Mapper 被 Spring/MyBatis 注册。
- bootstrap/src/main/resources/application.yaml：通过可选导入加载 starline-application.yaml，未提供邮件配置时应用仍可启动。
- bootstrap/src/main/resources/starline-application.yaml：新增邮件 SMTP 主机、端口、用户名、密码、TLS、发件地址和轮询间隔配置；邮件提醒默认关闭，配置值从环境变量读取。

### Web 前端

- frontend/src/components/agent/AgentSidebar.tsx：AgentSidebar 增加“星序活动提醒”入口，导航至提醒页面。
- frontend/src/router.tsx：注册 /starline/reminders 路由，并放在现有登录校验组件 RequireAuth 内。
- frontend/src/pages/StarlineRemindersPage.tsx：新增 StarlineRemindersPage 页面。组件通过 refresh() 加载列表、submit(event) 提交已公布或待公布信息、cancel(id) 取消待发送提醒；页面展示来源确认、开票和提醒时间、邮箱、待公布状态及提醒状态，并明确说明信息由用户录入、邮件需配置 SMTP。
- frontend/src/services/starlineService.ts：定义信息/提醒状态类型、活动数据接口和创建请求接口；listStarlineEvents() 查询列表，createStarlineEvent(request) 创建记录，cancelStarlineReminder(id) 取消提醒。

### 数据库

- resources/database/schema_pg.sql：在 PostgreSQL 基础 schema 中增加 t_starline_event_reminder 表和用户记录、到期提醒索引，供全新数据库初始化使用。
- resources/database/migrations/V001__starline_event_reminders.sql：新增可重复执行的 PostgreSQL 初始化迁移，创建同一活动提醒表及索引，供已有数据库部署时执行。

### 产品与运维文档

- docs/product/starline-agent-sequence-v0.1.svg：新增星序 Agent 业务闭环时序图，展示用户查询意图、来源确认、信息展示、提醒创建、邮件发送与取消等交互。
- docs/product/starline-business-slice-setup.md：说明首个业务切片范围、用户录入与待公布规则、数据库初始化方式、SMTP 环境变量及 API 路径。
- docs/server-operations.md：新增腾讯云 PostgreSQL/Redis 部署操作记录，记录执行命令、服务健康状态、端口绑定及凭据文件权限；未记录密码值。
- docs/change-log.md：新增本项目变更日志，按文件记录本轮代码、配置、数据库和文档改动及类/方法职责。

## 验证记录

本次仅核对了 Git 工作区状态及相关文件内容；没有运行构建或测试命令。


## 2026-10-22：星序前端标题调整

- frontend/src/pages/StarlineRemindersPage.tsx：在 StarlineRemindersPage 组件中，将页面主标题从“演出信息与提醒”改为“星序starline”，使星序业务页直接呈现产品名称；页面说明文案和全局 RAgent 浏览器标签标题保持原样。
- docs/change-log.md：追加本次标题调整记录，遵循项目文件改动均需登记的约定。
## 2026-10-22：前端品牌统一为 starline星序

- frontend/index.html：将浏览器文档标题改为“starline星序”。
- frontend/.env：将 VITE_APP_NAME 改为“starline星序”；保留 VITE_API_BASE_URL=/api/ragent，因为后端 context path 仍使用该前缀。
- frontend/package.json、frontend/package-lock.json：将前端包名由 ragent-frontend 改为 starline-frontend，并同步锁文件中的根包名。
- frontend/src/components/agent/AgentLayout.tsx：AgentLayout 顶栏品牌字标改为“starline / 星序”；GitHub 入口改为 LntanoC/starLineAgent 仓库，星数读取对应新仓库。
- frontend/src/components/agent/AgentChatInput.tsx：输入框提示改为“给 starline星序 发送消息”。
- frontend/src/components/layout/Sidebar.tsx：侧栏品牌改为“starline星序”；原框架文档入口改为星序项目仓库。
- frontend/src/components/layout/Header.tsx：GitHub 仓库入口改为 LntanoC/starLineAgent。
- frontend/src/components/agent/AgentSidebar.tsx：原框架官方文档入口改为“星序项目仓库”，链接指向 LntanoC/starLineAgent。
- frontend/src/pages/admin/AdminLayout.tsx：管理后台品牌改为“starline星序管理后台”，GitHub 入口改为星序仓库。
- frontend/src/pages/ChangeLogsPage.tsx：业务变更审计页品牌改为“starline星序”。
- frontend/src/pages/StarlineRemindersPage.tsx：星序提醒页主标题统一为“starline星序”。
- frontend/src/hooks/useGitHubStars.ts：星数 API 切换到用户的 starLineAgent 仓库，并使用 starline 命名的缓存键，避免显示原 RAgent 仓库的星数。
- docs/change-log.md：追加本次品牌调整记录。
- 兼容性边界：前端 API 路径 /api/ragent、框架属性 ragent.engine.type、现有 ragent localStorage 键及 TESTING.md 中的后端路径说明继续保留；这些是后端协议、框架配置、浏览器状态兼容和排障信息，不是产品品牌文案。
## 2026-10-22：后端 Ragent 类型标识符改为 Starline

### 类名与构造器

- bootstrap/src/main/java/com/nageoffer/ai/ragent/StarlineApplication.java（原 RagentApplication.java）：启动入口类改名；main(String[] args) 仍负责 SpringApplication.run，只将启动类引用更新为 StarlineApplication.class。Mapper 扫描范围不变；@EnableLogRecord 的 tenant 标识同步从 ragent 改为 starline。
- agent/src/main/java/com/nageoffer/ai/ragent/agent/trace/StarlineAttributes.java（原 RagentAttributes.java）：OpenTelemetry 属性常量类改名；私有构造器同步改名，属性常量和值保持不变，避免改变现有追踪数据字段。
- agent/src/main/java/com/nageoffer/ai/ragent/agent/trace/StarlineOtelTracingMiddleware.java（原 RagentOtelTracingMiddleware.java）：Agent OpenTelemetry tracing middleware 改名；StarlineOtelTracingMiddleware(Tracer) 构造器同步改名，追踪逻辑不变。
- rag/src/main/java/com/nageoffer/ai/ragent/rag/config/StarlineWebMvcConfiguration.java（原 RagentWebMvcConfiguration.java）：WebMvcConfigurer 配置类改名，拦截器和 MVC 配置逻辑不变。
- system/src/main/java/com/nageoffer/ai/ragent/audit/service/impl/StarlineOperatorGetService.java（原 RagentOperatorGetService.java）：审计日志操作人信息解析服务类改名，实现逻辑不变。
- resources/initializer/common/StarlineHttpClient.java（原 RagentHttpClient.java）：初始化/回归工具使用的 HTTP API 客户端类改名；StarlineHttpClient(InitializerConfig) 构造器同步改名。login、get、postJson、putJson、postEmpty、delete、uploadDocument、close 等方法名和行为不变；更新其注释、multipart boundary 前缀和错误文案中的产品名。
- rag/src/test/java/com/nageoffer/ai/ragent/StarlineCoreApplicationTests.java（原 RagentCoreApplicationTests.java）：核心应用测试类改名。
- rag/src/test/java/com/nageoffer/ai/ragent/TestStarlineApplication.java（原 TestRagentApplication.java）：RAG 测试启动配置类改名。
- agent/src/test/java/com/nageoffer/ai/ragent/agent/trace/StarlineOtelTracingMiddlewareTest.java（原 RagentOtelTracingMiddlewareTest.java）：tracing middleware 测试类及文件改名。
- 本轮没有声明过名称含 Ragent 的业务方法；除构造器外，方法名保持不变。

### 引用点与文件职责

以下文件仅更新上述类的 import、类型引用、构造调用或注释，原方法业务逻辑未改：

- Agent tracing 实现：agent/src/main/java/com/nageoffer/ai/ragent/agent/trace/AgentRunTracer.java、AgentToolBodyTracer.java、AgentTraceEnrichmentMiddleware.java、AgentTracingConfiguration.java。
- Agent 测试与探针：agent/src/test/java/com/nageoffer/ai/ragent/agent/service/handler/AgentRunHandleTest.java、agent/src/test/java/com/nageoffer/ai/ragent/agent/trace/AgentRunTracerTest.java、AgentToolBodyTracerTest.java、AgentTraceEnrichmentMiddlewareTest.java、AgentTraceScenarioTest.java、LangfuseReadbackProbe.java。
- RAG 启动和测试：rag/src/test/java/com/nageoffer/ai/ragent/core/parser/mineru/MinerUPdfUploadFlowTest.java。
- 登录拦截配置注释：system/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenConfig.java，更新所引用 MVC 配置类的名称。
- 初始化与回归工具 HTTP 客户端调用点：resources/initializer/common/InitializationActions.java、InitializerContext.java；resources/regression/agent-compaction/CompactionRegressionMain.java、CompactionSseClient.java；resources/regression/agent-concurrency/ConcurrencyGateProbeMain.java、ConcurrencyRegressionMain.java、ConcurrencySseClient.java、ConcurrencySseSelfTestMain.java；resources/regression/agent-long-term-memory/LegacyMemoryCleanup.java、LongTermMemoryMain.java、MemoryRecallCheck.java、MemorySseClient.java、NaturalMemoryCheck.java；resources/regression/agent-memory-audit/MemoryAuditMain.java；resources/regression/agent-memory-evaluation/AgentMemoryEvaluationMain.java；resources/regression/agent-memory/AgentChatClient.java、RegressionContext.java。

### 范围说明

- 本轮将源码、测试和工具代码中类名含 Ragent 的类型及相应 Java 引用改名；当前 Java 源码/测试中已无旧类名声明或引用。
- com.nageoffer.ai.ragent 包命名空间、HTTP API context path /api/ragent、旧 OpenTelemetry 属性键 ragent.* 仍保留。这些是跨模块命名空间或运行期协议/追踪数据契约，未随 Java 类型改名而改变。
- docs/change-log.md：记录本轮类名、构造器和调用点调整。
- 未运行构建或测试命令。
- agent/src/main/java/com/nageoffer/ai/ragent/agent/trace/README.md：将 tracing 说明中对 RagentAttributes 的类名引用更新为 StarlineAttributes；OpenTelemetry 属性键名称不变。