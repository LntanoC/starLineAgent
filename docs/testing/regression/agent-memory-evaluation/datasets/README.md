# 星序记忆测评数据

`starline-events.json` 是星序 Agent 的行为回归数据集，包含 **3 个用例、7 轮**：演出查询工具调用与来源表述、艺人个人资料问题的工具边界，以及相对时间查询。断言只依赖稳定的用户输入和工具行为，不写死任何实时演出场次。

| 用例 ID | 轮数 | 用途 |
| --- | ---: | --- |
| `starline-basic` | 3 | 工具查询、来源等级说明和跨轮记忆 |
| `starline-model-boundary` | 2 | 艺人生日问题不调用演出查询，后续演出问题调用 |
| `starline-relative-time` | 2 | 相对时间查询先取当前日期，并记住搜索范围 |

运行条件：星序模板已激活，MCP 已配置 `YDC_API_KEY` 且可调用 `search_artist_events`。实时搜索结果会随日期和索引变化，因此本数据集只断言工具选择与稳定事实，不断言具体演出结果。

运行、选子集和看报告见[上一级 README](../README.md)。这套数据用于基础行为回归，不代表总体回答质量；默认预算下不保证触发裁剪或摘要，需结合报告判断。

## 加自己的用例

复制下面内容保存为 UTF-8 `.json` 文件，再用 `--dataset 文件路径` 运行；多个用例放进一个数组，`id` 不重复。

```json
[
  {
    "id": "my-memory",
    "tags": ["retention"],
    "turns": [
      { "id": "remember", "question": "我最常去看演出的城市是杭州，请记住。" },
      {
        "id": "recall",
        "question": "我最常去哪个城市看演出？只回答城市名，不要查询资料。",
        "expectAll": ["杭州"],
        "forbidTools": ["search_artist_events"]
      }
    ]
  }
]
```

- `expectAll` 必须全部包含；`expectAny` 至少包含一个；`forbidAny` 都不能包含。这些是文本子串断言，不等同于完整语义评分。
- `expectTools` / `forbidTools` 指定必须调用 / 禁止调用的工具；`tags` 用于选择子集，`description` 可补充说明。
- 不写断言的轮次保留回答供抽查，不计入通过率。实时事件事实不要写进固定答案。
