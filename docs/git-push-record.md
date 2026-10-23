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
