# Ragent 数据初始化器

把一个已经运行的 Ragent 环境，重置成模板声明的确定状态。以默认模板 `enterprise-knowledge-base` 为例，
执行成功后环境中存在模板定义的 2 个知识库、10 份文档、29 个意图节点、2 份技能手册和 15 条示例
问题，且这 15 个问题都已被真实提问过一遍，答案和推荐追问都已落库。

当前有两套模板，用 `--agent-type-dir` 选：

| 模板 | 面向 | 确认词 | 额外要求 |
| --- | --- | --- | --- |
| `enterprise-knowledge-base` | 企业内部知识助手，`agent` 档 | `RESET-ENTERPRISE-KNOWLEDGE-BASE` | 预热会查询 MCP 数据 |
| `bit-selection` | 比特严选电商 Agent，`agent` 档 | `RESET-BIT-SELECTION` | 另有业务库，见该目录下的 README |

两套模板的数据是互斥的：任何一套跑完，另一套的知识库、意图、技能、示例问题和记忆都已被清掉。下文
以 `enterprise-knowledge-base` 举例，换成另一套只要同时换掉目录和确认词。

它是一次性 Java 17 CLI，不启动 Spring、不监听端口，也不需要单独的 Maven 模块。

> 完整初始化会删除现有业务数据，只应在允许重置的演示、教程或测试环境中执行。

## 快速执行

执行前确认五件事：

- JDK 17 可用，`java` 与 `javac` 都在 PATH 中；
- RagentAI、PostgreSQL、Redis 已启动；
- 服务读的是 `bootstrap/src/main/resources/application.yaml`，也就是模板所指向的那一份；
- 服务运行在 `ragent.demo-mode=false`，否则写接口会被拒绝；
- 服务运行在 `ragent.engine.type=agent`，模板中的实时数据问题由 Agent 调用 MCP 工具；
- `mcp-server` 已启动，模板里有 9 个意图节点挂着 MCP 工具，预热会真的调到它。

以下命令在项目根目录执行，先编译再初始化：

```bash
rm -rf /tmp/ragent-initializer-classes && mkdir -p /tmp/ragent-initializer-classes

javac -encoding UTF-8 \
  -d /tmp/ragent-initializer-classes \
  resources/initializer/common/*.java \
  resources/initializer/enterprise-knowledge-base/*.java

java -cp /tmp/ragent-initializer-classes \
  com.nageoffer.ai.ragent.initializer.InitializeMain \
  --agent-type-dir resources/initializer/enterprise-knowledge-base \
  --confirm RESET-ENTERPRISE-KNOWLEDGE-BASE
```

看到 `[initializer] SUCCESS` 才表示全部完成，任何一步失败都会以非零状态退出。唯一的例外是最后的预热，
单轮问答失败重试后仍不通就跳过，不影响整体结果，跳了哪几轮会在结尾列出来。

最后一步是预热：串行提问 `questions.properties` 里的每个问题，让会话、消息、检索链路和 RAG 追踪
都产生真实数据。每个答案结束后还会调用一次推荐追问接口，把追问补进消息，否则历史会话点开是空的。
这一步依赖模型，15 轮提问通常需要几分钟。只想拿到数据、不想等模型的话，加 `--skip-warmup`。

编译这一步在源码变化后必须重跑。直接复用 `/tmp` 中的旧 class，会出现配置项缺失这类与当前源码
不一致的错误。

不放心就先加 `--dry-run` 跑一遍。它照样连接 RagentAI、PostgreSQL 和 Redis 并执行全部预检，打印
清理范围与后续创建动作，但不清理也不创建业务数据。

改过 `docs/`、`intents/`、`prompts/`、`skills/` 或任何 properties 之后，先重算完整性基线，否则预检会以
`文件 checksum 不一致` 失败：

```bash
(cd resources/initializer/enterprise-knowledge-base && \
  find . -type f \( -name '*.md' -o -name '*.sql' -o -name '*.properties' -o -name '*.txt' \) \
  | sed 's|^\./||' | LC_ALL=C sort | xargs shasum -a 256 > checksums.sha256)
```

只有 `sha256sum` 的环境把 `shasum -a 256` 换成 `sha256sum` 即可，两者输出格式一致。

## 命令一览

正常情况只用 `InitializeMain`。其余入口用于排障或恢复中断的步骤，每个入口都会先执行自己所需的
预检，都接受相同的参数。入口类随模板目录走，只声明了对应资产的模板才带 `AgentProfileInitMain`
与 `BizDataInitMain` 这两个。

| 入口 | 作用 | 是否修改业务数据 |
| --- | --- | --- |
| `ValidateDatasetMain` | 校验本地模板：目录、元数据引用、数量和 SHA-256，不连接任何服务 | 否 |
| `PreflightMain` | 校验 RagentAI 登录、Admin 身份、后端类型、PostgreSQL、Redis 和系统空闲状态 | 否 |
| `CleanupMain` | 只执行清理，要求 `--confirm` | 是 |
| `KnowledgeBaseInitMain` | 创建或复用知识库 | 是 |
| `DocumentInitMain` | 上传、切分或替换文档 | 是 |
| `IntentTreeInitMain` | 重建意图树 | 是 |
| `AgentSkillInitMain` | 重建智能体技能，须先有意图树提供它解锁的 MCP 节点 | 是 |
| `AgentProfileInitMain` | 新建模板声明的人设、写槽位并激活，内置智能体不动 | 是 |
| `BizDataInitMain` | 清空并重灌业务库演示数据，不建库不建表 | 是 |
| `SampleQuestionInitMain` | 重建欢迎页示例问题 | 是 |
| `VerifyMain` | 校验当前初始化结果 | 否 |
| `WarmupMain` | 串行提问演示问题并补齐推荐追问，只补对话数据，不动知识库和意图 | 是 |
| `InitializeMain` | 完整清理、重建、校验和预热 | 是 |

公共参数：

| 参数 | 说明 |
| --- | --- |
| `--agent-type-dir <目录>` | 必填，指定智能体类型目录 |
| `--config <文件>` | 可选，替换默认的 `<agent-type-dir>/initializer.properties` |
| `--confirm <确认词>` | 清理类入口必填，当前模板为 `RESET-ENTERPRISE-KNOWLEDGE-BASE` |
| `--dry-run` | 只预检和打印计划，不修改业务数据 |
| `--skip-warmup` | 跳过最后的串行提问，其余步骤照常 |

改完模板只想本地自查，用 `ValidateDatasetMain`，它不需要服务在线：

```bash
java -cp /tmp/ragent-initializer-classes \
  com.nageoffer.ai.ragent.initializer.ValidateDatasetMain \
  --agent-type-dir resources/initializer/enterprise-knowledge-base
```

想确认环境这一侧是否就绪，用 `PreflightMain`，参数同上。

## 执行流程

```text
模板数据 + 当前环境配置 -> 预检 -> 定向清理 -> 重建 -> 结果校验 -> 预热
```

`InitializeMain` 依次执行：模板校验、环境预检、文档物理清理、数据库与 Redis 清理、知识库创建、
文档上传与切分、意图树创建、技能写入、示例问题写入、结果校验、演示问题预热。声明了业务库与人设
的模板还会在清理之后灌业务库数据、在技能之后新建并激活人设。

技能必须排在意图树之后：技能的 `tool-ids` 只能引用意图树里已启用的 MCP 节点，先写技能会被服务端
直接拒掉。

人设必须排在清理之后：清理按 `builtin = 0` 删非内置人设，先建的那份会被它删掉。

预热逐题调用 `/agent/v1/chat`，每题一个独立会话，上一题读完 SSE 才发下一题。问题之间没有先后依赖，
所以提问顺序每次随机，让初始化产生的会话列表不与欢迎页示例问题一一对齐；实际顺序由日志中的
`warmup.shuffle-seed` 给出，把它填回 `initializer.properties` 就能复现某次运行。

配了 `follow-ups` 的问题会在同一会话内继续追问，用来演示指代消解和多轮上下文，追问本身不进示例
问题表。每轮答案落库后再调用一次 `/conversations/messages/{messageId}/recommended-questions`，与前端
在答案结束后的行为一致。

对话接口失败时只断流不发错误事件，所以判定标准是收到终止事件 `done`、没出现 `reject` 或 `cancel`、
模型产出了非空回答，且 `finish` 事件带回了 `messageId` 和 `NORMAL` 状态，缺一即视为本轮失败。推荐
追问返回 `FAILED` 同样算失败，`EMPTY` 是已落库的有效结果。

单轮失败会按 `warmup.max-attempts` 重试，默认连试 3 次、每次间隔 10 秒，用来吃掉网络抖动和模型偶发
超时。重试用尽就跳过该轮继续下一题，不中断整体流程，最后统一列出跳过了哪些轮次。跳过首轮的问题会
连同它的同会话追问一起放弃，因为追问依赖首轮的会话和上下文。推荐追问用尽重试则只是这条消息没有
追问，答案已经落库，本轮照常算成功。

跳过的只是对话数据，知识库、文档和意图不受影响，补齐单独重跑 `WarmupMain` 即可。重试新开会话的那
几次失败尝试会在会话列表里留下半截记录，介意的话重跑前先清一次。

## 安全边界

| 资源 | 处理方式 |
| --- | --- |
| 文档、源文件、Chunk 和索引 | 先逐文档调用 HTTP 删除接口，完成物理资源回收 |
| PostgreSQL 业务数据 | 执行 `cleanup.sql` 中的显式表白名单，不使用 `CASCADE` |
| Redis | 只删除 `initializer.properties` 声明的 Key 和 Pattern，不执行 `FLUSHDB` |
| 用户与 Agent 配置 | 三张表都不整表清空：`t_user` 原样保留，`t_agent_profile` 与 `t_agent_prompt` 只按行删非内置人设 |

执行器还会要求传入精确确认词，在清理前检查近期数据库任务和 Redis 运行状态，用 Redis 锁避免两个
初始化器同时运行，校验运行中后端与 `application.yaml` 声明一致，并在写入后重新查询验证知识库、
文档、Chunk、意图及绑定关系。

RagentAI 服务本身不读初始化锁，所以空闲检查只能证明检查当下没有任务。初始化期间不要让用户访问
系统，最好在站点开放前执行。

## 三个输入

初始化结果完全由以下三部分决定：

1. `enterprise-knowledge-base/` 描述目标状态，包括知识库、文档、意图、提示词、技能、示例问题和
   清理白名单。
2. `bootstrap/src/main/resources/application.yaml` 描述当前运行环境，包括 RagentAI 地址、PostgreSQL、
   Redis、向量后端和对象存储后端。
3. `enterprise-knowledge-base/initializer.properties` 描述本次初始化行为，包括 Admin 账号、超时、
   并发保护、Redis 清理范围和最终数量断言。

`initializer.properties` 通过 `application.config` 指向 `application.yaml`。初始化器据此自动推导
`server.base-url`，并读取数据库和 Redis 连接信息，通常不需要重复配置这些值。同名配置以
`initializer.properties` 为准。

其他规则：

- 相对的 `application.config` 以 `initializer.properties` 所在目录为基准，而不是当前工作目录；
- properties 和 YAML 标量都支持 `${ENV}` 与 `${ENV:default}` 环境变量占位符；
- `server.base-url` 由 `server.address`、`server.port`、SSL 和 context path 自动生成；
- Admin 账号来自 `auth.username` 和 `auth.password`，不要混入 RagentAI 主配置。

初始化器只依赖 JDK。执行数据库操作时，它会依次从当前 Classpath、本机 Maven 缓存和
`bootstrap/target` 启动包中寻找 PostgreSQL JDBC 驱动，都找不到时才需要配置
`database.jdbc-driver-path`。

## 模板目录约定

```text
resources/initializer/
├── common/                         # 通用 CLI 实现
└── <模板目录>/
    ├── docs/                       # 待上传文档
    ├── intents/                    # 意图节点定义
    ├── prompts/                    # 意图引用的提示词，以及人设槽位正文
    ├── skills/                     # 技能定义及其正文手册
    ├── initializer.properties      # 环境入口与执行策略
    ├── knowledge-bases.properties  # 知识库定义及文档目录映射
    ├── questions.properties        # 示例问题、预热题目与同会话追问
    ├── cleanup.sql                 # PostgreSQL 清理白名单
    ├── checksums.sha256            # 模板完整性基线
    ├── agent-profile.properties    # 可选，人设声明及其槽位映射
    ├── biz-data/                   # 可选，业务库演示数据
    ├── biz-cleanup.sql             # 可选，业务库数据清空白名单
    └── *Main.java                  # 命令入口
```

新增智能体类型时，在 `resources/initializer/` 下增加独立英文目录，并完整提供上述模板资产。标注为
可选的三项只有需要自带人设或业务库的模板才提供，缺省即跳过对应步骤。任何会影响初始化结果的文件
变更，都必须同步更新 `checksums.sha256`，命令见「快速执行」。

## 常见错误

- `文件 checksum 不一致`：改过模板文件但没重算基线，按「快速执行」末尾的命令重新生成。
- `缺少配置项 server.base-url`：通常是 `/tmp/ragent-initializer-classes` 中仍有旧 class。删除该目录并
  全量重新编译，不要先手工复制一份重复配置。
- `application.yaml 不存在`：检查 `application.config`。相对路径从当前 properties 文件所在目录解析。
- RagentAI 连接失败：核对 `application.yaml` 中的端口、context path、SSL 和监听地址，并确认服务已启动。
- `检测到运行中的任务`：停止新流量，等待已有文档、RAG、摄取或 Agent 任务结束后重试。
- `连续 N 次提问失败，跳过本轮`：重试没救回来，整体流程继续。零星几轮属正常损耗，大面积跳过说明模型
  或服务端有问题，排查后单独重跑 `WarmupMain` 补数据，不需要重新初始化。
- `SSE 流未收到 done 事件即结束`：服务端在回答过程中抛异常断流，去应用日志里看这一题的真实报错。
- `答案没有落库，finish 事件缺少 messageId`：模型已经回答，但消息持久化失败，按数据库日志排查。
- `连续 N 次生成推荐追问失败`：答案本身没问题，是追问生成这一步的模型调用失败，重跑 `WarmupMain` 即可。
- 写接口被拒绝：确认运行中的服务使用 `ragent.demo-mode=false`。
- PostgreSQL 驱动缺失：先构建 `bootstrap`，或显式配置 `database.jdbc-driver-path`。

需要完整异常栈时，在命令前设置 `RAGENT_INITIALIZER_DEBUG=true`。
