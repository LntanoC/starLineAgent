# 星序初始化器

初始化模板位于 [`starline-events.md`](starline-events.md)，声明星序人设、提醒与平台知识库、MCP 工具注册表、Agent 技能和示例问题。RAG 现在直接在已启用知识库中检索，不再使用意图树进行路由或澄清；实时演出事实通过 MCP 搜索，不写进静态知识文档。

## 模板内容

- 2 个知识库：提醒与核验规则、票务平台说明。
- 6 篇知识文档：规则 1 篇、平台说明 5 篇。
- 3 个 MCP 工具，由 `mcp-tools/*.properties` 独立声明。
- 6 条知识型示例问题；预热不执行创建提醒等写操作。
- 星序助手人设和 3 个 Agent 提示词槽位。

演出查询需要 MCP 服务配置 `YDC_API_KEY`。未配置时，联网搜索 MCP 工具不注册；日期工具仍可用。不要将密钥写入本页。

## 初始化影响

完整初始化会执行模板中的 `cleanup.sql`，清理会话、消息、知识库、文档、技能和示例问题，再创建星序数据。它不会创建或灌入电商业务库，也不会删除遗留的 `t_intent_node` 表或其中的数据。该表已不参与当前 RAG 运行链路，待新版运行验证后再单独迁移清理。

管理员密码使用环境变量 `STARLINEAGENT_ADMIN_PASSWORD` 注入，不保存在仓库。初始化前先设置该变量。

模板文件实际位于 `resources/initializer/starline-events/`。以下命令均从项目根目录执行。

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

本地验证只读取模板，不连接服务器，也不改数据。完整初始化会清理线上数据；未备份并核实影响前不要运行。

```bash
java -cp /tmp/starlineagent-initializer-classes \
  com.nageoffer.ai.starlineagent.initializer.InitializeMain \
  --agent-type-dir resources/initializer/starline-events \
  --confirm RESET-STARLINE-EVENTS
```

如果修改模板中的文档、提示词或 properties，先重算校验和：

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
| `McpToolInitMain` | 按模板配置 MCP 工具注册表 | 是 |
| `AgentProfileInitMain` | 新建并激活星序人设 | 是 |
| `SampleQuestionInitMain` | 重建欢迎页示例问题 | 是 |
| `VerifyMain` | 核验服务中的初始化结果 | 否 |
| `WarmupMain` | 运行示例问题预热，会写入真实对话记录 | 是 |
| `InitializeMain` | 按顺序完成清理、重建、校验和预热 | 是 |

每个入口都会校验模板并使用 `initializer.properties` 指定的服务连接。`--skip-warmup` 可跳过完整初始化最后的示例问答，但不会跳过清理和重建。
