# 演出查询覆盖 v1 实施说明

依据：[原技术设计](event-search-coverage-v1.md)。2026-11-04先独立发布P0，再发布发现管道、分页和界面。出行的路线、时间轴、费用算法不改。

## 查询与解析

- `ArtistEventSearchMcpExecutor`只校验/序列化，`event/EventSearchService`共享来源、缓存和候选。发现不要求城市，默认未来365天；recent90天、all730天，includeEnded才包括近30天。
- 最多6条query、每query最多2页、每页20条，总原始预算200条、4并发、发现8秒。城市查询优先执行城市模板，3秒预算适配观演规划既存4秒边界。未完成查询、原始预算和队列拥塞都会报告；已成功页面不会随下一页超时丢失。
- 数据库ACTIVE且公开来源是唯一白名单；删除无效official-domains配置。每次请求重新核验来源，来源停用立即使旧游标失效；观演客户端移除绕过来源核验的独立缓存。
- 高德公开行政区391条名/adcode快照在`mcp-server/src/main/resources/event-search/cities.json`，可用city-aliases配置别名；匹配所有位置，局部拆分多城市、多日期。未知城市/日期保留显式标记。
- 采取原文A2的保守口径：无年份日期保留null/dateUnknown及窗口内dateCandidates，候选年份不能用于开场、购票或提醒。区间保留端点，不生成未经证明的中间场次。排除明确开票日期，场馆/票价/开场仍仅来自明确标签；不跨城市借场馆。
- 规范化IDN/子域名/追踪参数，保留活动ID；同最终URL仅抽取一次，保留命中query。跳转最多3跳，每跳公有DNS检查且socket固定到已验证IP，HTTPS验证原主机证书；不跟随私网、凭据URL或非HTTP(S)。受时间预算限制未解析的来源仍会记录过滤原因。

## 上游能力和可靠性

- [腾讯官方SearchPro说明](https://cloud.tencent.cn/developer/mcp/server/11764)列出Cnt/Site/Freshness，没有Page/Offset；真实发送Cnt/Site，不伪造翻页，coverage.providerPageLimit始终说明其边界。发布时间过滤不代替演出日期窗口。
- [腾讯REST输出契约](https://cloud.tencent.com/document/product/1806/121811)与线上核验：站点查询可能只返回Query/RequestId/Version而省略Pages；仅这种无Error、无Msg的有效响应按空命中处理，不能误报来源失败。任意空对象、业务Error或Msg拦截仍视为失败。
- 已配置YDC_API_KEY时合并[You.com搜索](https://you.com/docs/api-reference/search/v1-search)的结果，GET传count和页偏移offset，兼容新旧摘录格式；没有Key时不假装启用备用源。
- provider页面缓存和合并快照5分钟、同条件singleflight；有界队列、按provider并发限制，网络/5xx/429允许一次重试，连续5次失败熔断30秒，之后恢复探测。日志记录query hash/count/reason/耗时，不输出用户地址、搜索Key或原始URL。
- 来源页、相关性页、日期/城市后仍有候选的页计数保持单调；`drafts/merged`可因一页多场次大于页面数。`filterUnits`区分页面过滤和场次过滤，未知日期/过时信息保留而非伪称已过滤。
- source.status区分有命中、执行过站点query但未命中、查询失败、尚未观察到。配置来源数不等于已证实全部来源可检索。

## 分页与界面

- pageSize默认20，上限50（配置max-items同样为50）；城市轮转保证较早分页的城市多样性，同城保持日期顺序。`merged`是分页前数、`returned`是本页数、`truncated`是剩余数，后续页沿用快照顺序。
- 游标绑定查询、来源版本和5分钟期限，服务器只保留有界快照，无业务内部ID。失效提示重新查询。
- 新增登录后专用`POST /starline/event-search`，仅桥接启用的search_artist_events，不接受任意工具名。前端显示覆盖状态、窗口、来源状态、城市分组、未知日期和加载更多；失败保留已有卡片。无coverage的历史结果回退原始文本。
- 初始化工具说明、技能和内置提示词区分“发现”与“规划”，部署只运行增量SQL并刷新两个精确元数据缓存。

## 验证与持续评测

- 离线黄金集覆盖12条三城市、多城市同页、日期形式/跨年/开票隔离、来源302/URL安全、来源禁用、来源失败、分页计数和缓存；提供方HTTP stub核对真实请求参数。
- `node frontend/tests/event-search.check.mjs`读取Java测试生成JSON验证前端契约；浏览器检查城市分组、加载更多、过期游标、桌面和手机布局。
- `node scripts/evaluate-event-coverage.mjs --self-test`验证比较器；实际评测输入为人工保存的`[{artist,observedAt,result,reference:[{city,eventDate,sourceUrl}]}]`。先将分页结果合并为待比较快照（returned同步items.length、truncated=0），再运行`node scripts/evaluate-event-coverage.mjs snapshots.json report.json`。
- 每周人工选择固定3个艺人（薛之谦、林俊杰、周杰伦），对比公开票务证据，仅记录来源、日期、城市和差异。未加载分页不算确定遗漏，未知日期单独列出；不将实时搜索结果写死进CI。未创建定时任务，也不绕过登录/验证码。
- P95目标需在线样本压测验证；离线测试和少量线上冒烟不能证明覆盖率或P95。发现结果始终是有预算的检索，不承诺票务平台场次全量。
