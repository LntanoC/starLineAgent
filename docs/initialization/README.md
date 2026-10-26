# 星序初始化器

初始化模板位于 [`starline-events`](starline-events.md)，声明星序人设、提醒与平台知识库、纯知识路由意图树、独立 MCP 工具注册表和示例问题。实时演出事实通过 MCP 搜索，不写进静态知识文档。

## 模板内容

- 2 个知识库：提醒与核验规则、票务平台说明。
- 6 篇知识文档：规则 1 篇、平台说明 5 篇。
- 8 个知识路由意图节点；3 个 MCP 工具由 `mcp-tools/*.properties` 单独声明。
- 6 条知识型示例问题；不在初始化预热时执行创建提醒等写操作。
- 星序助手人设和 3 个 Agent 提示词槽位。

演出查询需要 MCP 服务配置 `YDC_API_KEY`。未配置时，联网搜索 MCP 工具不注册；日期工具仍可用。是否已配置以服务器环境为准；不要将密钥写入本页。

## 初始化影响

完整初始化会执行模板的 `cleanup.sql`，清除既有会话、消息、知识库、文档、意图树、技能和示例问题，再创建星序数据。它不会创建或灌入电商业务库。**本次开发只新增模板，不执行线上初始化。** 只有完成数据库备份、核实清理范围并决定切换线上知识资产后，才运行完整流程。

管理员密码使用环境变量 `STARLINEAGENT_ADMIN_PASSWORD` 注入，不保存在仓库。初始化前先设置该变量。

模板文件实际位于 resources/initializer/starline-events/。以下命令均从项目根目录执行。

## 本地验证与初始化

在项目根目录使用 Java 17：

```bash
mkdir -p /tmp/starlineagent-initializer-classes
javac -encoding UTF-8 -d /tmp/starlineagent-initializer-classes \
  resources/initializer/common/*.java \
  resources/initializer/starline-events/*.java

java -cp /tmp/starlineagent-initializer-classes \
  com.nageoffer.ai.starlineagent.initializer.ValidateDatasetMain \
  --agent-type-dir resources/initializer/starline-events
```

本地验证只读取模板，不连接服务器，也不改数据。完整初始化命令会清理线上数据，请勿在未备份、未确认前运行：

```bash
java -cp /tmp/starlineagent-initializer-classes \
  com.nageoffer.ai.starlineagent.initializer.InitializeMain \
  --agent-type-dir resources/initializer/starline-events \
  --confirm RESET-STARLINE-EVENTS
```

如果修改了模板中的文档、意图、提示词或 properties，先重算校验和：

```bash
(cd resources/initializer/starline-events && \
  find . -type f ! -name '*.java' ! -name 'checksums.sha256' \
  | sed 's|^./||' | LC_ALL=C sort | xargs sha256sum > checksums.sha256)
```

## 入口类

| 入口 | 作用 | 会修改服务数据 |
| --- | --- | --- |
| `ValidateDatasetMain` | 校验本地模板文件、数量、引用和 SHA-256 | 否 |
| `PreflightMain` | 检查服务、Admin 身份、数据库、Redis 和系统空闲状态 | 否 |
| `CleanupMain` | 按模板清理现有数据，需要精确确认词 | 是 |
| `KnowledgeBaseInitMain` / `DocumentInitMain` | 创建知识库、上传与切分文档 | 是 |
| `IntentTreeInitMain` | 重建知识路由意图树 | 是 |
| `McpToolInitMain` | 按已审核模板配置 MCP 工具注册表 | 是 |
| `AgentProfileInitMain` | 新建并激活星序人设 | 是 |
| `SampleQuestionInitMain` | 重建欢迎页示例问题 | 是 |
| `VerifyMain` | 核验服务中的初始化结果 | 否 |
| `WarmupMain` | 运行示例问题预热，会写入真实对话记录 | 是 |
| `InitializeMain` | 按顺序完成清理、重建、校验和预热 | 是 |

每个入口都会校验模板并使用 `initializer.properties` 指定的服务连接。`--skip-warmup` 可跳过完整初始化最后的示例问答，但不会跳过清理和重建。
