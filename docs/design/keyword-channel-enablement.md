# 关键词检索通道开启方案

## 结论

开启不是改一个开关，而是**三层同时到位**：ES 服务端 + 两个配置开关 + 存量数据回填。缺任一层都不会真正生效，且有两个会让启动直接失败的点。

当前状态（`bootstrap/src/main/resources/application.yaml`，与服务器 `resources/docker/server/application.yaml`、发布快照 `releases/starline-20261022/application.yaml` 三处一致）：

```yaml
rag:
  keyword:
    type: none          # 144 行，后端类型，none = 不注册任何关键词读写实现
  search:
    channels:
      keyword:
        enabled: false  # 202 行，通道开关
```

`KeywordSearchChannel` 与 `EsKeywordIndexService` / `EsKeywordRetrieverService` 全部标注 `@ConditionalOnProperty(prefix="rag.keyword", name="type", havingValue="es")`，所以 `type=none` 时这些 bean 根本不存在，`enabled=true` 也只是个不报错的空配置。

## 三个硬约束

### 1. IK 分词器没有 ES 9.x 版本

`rag/pom.xml` 未显式指定 ES 客户端版本，走 Spring Boot 4.1.0 BOM，本地 `.m2` 解析结果是 **elasticsearch-java 9.4.2**。而配置里默认写的是 `ik_max_word` / `ik_smart`。

实测确认：IK 插件官方发布页（`release.infinilabs.com/analysis-ik/stable/`）最高只到 **8.19.17**，**没有任何 9.x 版本**。IK 版本号与 ES 版本严格一一对应，无法跨版本使用。

因此两条路：

| 方案 | ES 版本 | 分词器 | 改动 | 风险 |
| --- | --- | --- | --- | --- |
| **A（推荐）** | 9.4.2 | 内置 `cjk` 或官方 `analysis-smartcn` | 仅改 yaml 的 analyzer 配置 | 客户端与服务端版本完全对齐，零兼容风险 |
| B | 8.19.x | IK 8.19.x（可用自定义词典） | 配置保持 ik_* 不变 | 客户端 9.4.2 对 8.x 服务端需实测，跨大版本不保证 |

**推荐 A**。理由：星序的核心专有名词都是 2–4 字品牌（大麦、猫眼、秀动、票星球、永乐票务），`cjk` 的 bigram 切分对这类词本身就能整词命中（大麦 → 「大麦」），不依赖自定义词典。只有将来需要把「开票时间」「待公布」这类长业务词精确切分时，才值得为 IK 降到 8.x。

`cjk` 是 Lucene 内置分析器，**无需安装任何插件**；`smartcn` 是 Elastic 官方插件，质量略胜，但需 `bin/elasticsearch-plugin install analysis-smartcn`。先用 `cjk` 起步最省事。

配置改为：

```yaml
rag:
  keyword:
    type: es
    es:
      uris: http://127.0.0.1:9200
      index: rag_keyword_store
      analyzer: cjk          # 原 ik_max_word
      search-analyzer: cjk   # 原 ik_smart
```

### 2. ES 变成启动强依赖

`EsKeywordIndexService` 用 `@PostConstruct` 在建 bean 时就调 `ensureSharedIndex()`（第 58–61 行），建索引失败直接抛 `RuntimeException`，bean 初始化失败 → **整个 API 起不来**。

对比之下，检索侧是优雅降级：`EsKeywordRetrieverService.search()` 第 88–91 行 catch 全部异常并 `return List.of()`。

所以操作顺序不能颠倒：**ES 必须先起且索引 mapping 建成功，再重启 starline-api**。ES 挂掉时 API 重启会起不来，但已运行的 API 只是关键词通道返回空、不影响向量召回。

### 3. 存量 6 篇文档不在 ES 里

写入链路本身是自动的：`KeywordSyncVectorStorePostProcessor` 在 `type=es` 时把 `VectorStoreService` 包成 `KeywordSyncingVectorStoreService`，覆盖 `indexDocumentChunks` / `updateChunk` / `deleteDocumentVectors` / `deleteChunkById` / `deleteChunksByIds` 全部写操作。**但只对开启之后的写入生效。**

你这 6 篇文档（policy 1 篇 + platform 5 篇，共 6 个 chunk）是在 `type=none` 时入库的，ES 索引会是空的。而检索侧 `search()` 用 `ignoreUnavailable(true)` + `allowNoIndices(true)`，空索引不报错、只返回空列表 —— **又是一个静默失效**：通道打开了、日志显示执行了、但没有一条命中。

系统没有专用的 reindex / 回填接口。可行回填路径只有两条：

- **批量禁用→启用 chunk**：`KnowledgeChunkServiceImpl` 第 386 行走 `vectorStoreService.indexDocumentChunks(...)`，经装饰器同步写 ES。会重新 embedding（6 个 chunk，成本可忽略）。
- **重新上传文档**：注意 `initializer.properties` 的 `document.replace-existing=false`，走初始化器导入不会覆盖同名文档，需要在控制台手动操作。

推荐第一条，逐文档全选 → 禁用 → 全选 → 启用。

## 执行步骤

### 阶段 1 · 部署 Elasticsearch

服务器 compose 当前只有 postgres 与 redis，需新增。要点：

- 镜像 `docker.elastic.co/elasticsearch/elasticsearch:9.4.2`（与客户端版本精确对齐）
- 单节点：`discovery.type=single-node`、`xpack.security.enabled=false`（内网 + 只监听 127.0.0.1 时）
- 内存：`ES_JAVA_OPTS=-Xms1g -Xmx1g`（当前 6 篇文档规模远用不到更多）
- 宿主机需 `vm.max_map_count=262144`，否则容器起不来
- 数据卷持久化到宿主机，避免重建容器丢索引
- 端口只绑 `127.0.0.1:9200`，不对外暴露

验证：`curl -s localhost:9200` 返回 cluster_name 与 version.number=9.4.2。

### 阶段 2 · 改配置

三处保持一致，避免下次部署被覆盖回去：

1. `bootstrap/src/main/resources/application.yaml`
2. `resources/docker/server/application.yaml`
3. `resources/docker/server/releases/<当前发布>/application.yaml`

每处改三个值：

```yaml
rag.keyword.type:                  none  -> es
rag.keyword.es.analyzer:           ik_max_word -> cjk
rag.keyword.es.search-analyzer:    ik_smart    -> cjk
rag.search.channels.keyword.enabled: false -> true
```

`rag.search.channel-weights.keyword: 1.0` 已经是 1.0，不用改。

注意这两个开关相互独立且都安全：`type=none` + `enabled=true` 只是空配置；`type=es` + `enabled=false` 会在启动时连 ES 建索引但检索不用。真正的组合是两者都为 es/true。

### 阶段 3 · 重启并确认索引建立

重启 starline-api，日志中依次出现：

```
Elasticsearch 关键词检索客户端已初始化, uris=http://127.0.0.1:9200
ES 关键词共享索引已创建, index=rag_keyword_store, analyzer=cjk/cjk
检测到关键词索引实现，向量写入将同步维护关键词索引, vectorStore=...
```

若卡在第三步缺失，说明装饰器没织入，检查 `type` 是否真的生效。

`curl localhost:9200/_cat/indices?v` 应看到 `rag_keyword_store`，mapping 含 content(text)/collection_name(keyword)/doc_id(keyword)/chunk_index(integer) 四个字段。

### 阶段 4 · 回填存量数据

按「阶段 1」第 3 点的第一条路径操作，逐文档批量禁用再启用 chunk。

验证：`curl "localhost:9200/rag_keyword_store/_count"` 返回 `count: 6`（6 篇文档各 1 个 chunk）。若为 0，回填没生效，先别继续。

### 阶段 5 · 验证检索生效

提一个含品牌词与业务词的问题（如「猫眼的开票时间是官方核验的吗」），日志应出现：

```
执行检索通道：KeywordSearch
关键词检索完成（定向），意图 top1=..., 命中 N 库 X 条
```

并确认同一轮的 `向量检索完成` 日志也在，两者都进 RRF 融合。

## 回滚

改回 `rag.keyword.type: none`（或仅 `enabled: false`）重启即可，行为与当前完全一致。ES 容器可停可不删，`type=none` 时应用完全不连它。

索引不用手工清理：`deleteByCollection` / `deleteDocumentIndex` 都已实现，但仅在 `type=es` 时经装饰器触发。若想彻底清空，直接删索引，下次启动会按新的 analyzer 重建。

## 值得先想清楚的两件事

**一、当前规模下收益接近于零。** 6 篇文档、6 个 chunk，向量通道的 topK 20 就能把全库覆盖，关键词通道多召回的边际价值极低。真正需要它的是文档量涨到几十上百篇、多个平台文档开始互相干扰的时候——`大麦.md` 与 `猫眼.md` 语义高度相似，纯向量容易串味，BM25 的精确词面匹配才能把「猫眼」和「大麦」区分开。

**二、它和意图树移除是两件独立的事。** 上一轮提到意图树移除后检索退化为全局单路：那时 `KeywordSearchChannel` 的 `ScopeQuota.split` 会因 `supplementCollections` 为空而全部给主路，关键词通道照常工作。两者无耦合，可以任意先后。

建议：如果近期没有扩充知识库的计划，先不动。如果准备把各平台文档做厚，那就在文档扩充之前把 ES 部署好，避免到时候再回填一批更大的数据。
