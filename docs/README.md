# Starline 文档导航

项目设计、开发、部署、测试与维护说明集中在此目录。资源目录只保留程序、Agent 初始化器和测试直接读取的配置、提示词、知识文档与样例数据。

## 文档分类

| 分类 | 内容 |
| --- | --- |
| 部署与运行 | 本地构建、服务器环境、Docker Compose、快速启动：deployment/ |
| 数据库 | 全量初始化、升级迁移规则：database/ |
| 初始化 | 初始化器用法与星序数据模板说明：initialization/ |
| Agent | 审计链路及运行说明：agent/ |
| Bad Case | 候选池使用、规则设计与离线分析：bad-case/ |
| 可观测性 | 管理端指标和运行统计口径：observability/ |
| 测试与回归 | 前端测试、RAG 夹具和 Agent 回归套件说明：testing/ |
| 架构设计 | 技术设计与历史改造方案：design/ |
| 产品 | 业务切片和验收约定：product/ |
| 维护 | 项目文件与构建产物维护规则：maintenance/ |
| 示例 | 文档解析等示例：examples/ |
| 变更记录 | 按日记录项目文件变化：change-log/ |
| 服务器操作日志 | 按日记录 SSH/SCP/部署命令及结果：server-operations/ |

## 常用入口

- [快速部署手册](deployment/quick-start.md)
- [服务器 Docker 配置说明](deployment/server-config.md)
- [数据库初始化与升级](database/postgres.md)
- [初始化器使用说明](initialization/README.md)
- [Agent 审计说明](agent/audit-usage.md)
- [Bad Case 候选池说明](bad-case/candidate-pool-usage.md)
- [回归与测评总览](testing/regression/README.md)
- [每日变更记录](change-log.md)

## 资源目录与文档的边界

Agent 知识库 Markdown 位于 resources/docs/knowledge/ 和 resources/initializer/starline-events/docs/knowledge/，由初始化器作为业务知识导入；提示词、properties、JSON/TXT 用例和 RAG/回归 fixture 是运行或验证输入。这些不是项目说明页，仍留在 resources/ 与测试资源目录，避免改变加载路径。