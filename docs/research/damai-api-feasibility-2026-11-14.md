# 大麦演出查询 API 可行性实测

- 测试时间：2026-11-14 23:35:42—23:35:43（北京时间）。
- 测试位置：本地 Windows 开发机，Python 3.12.4 标准库 urllib；未在项目服务器执行命令。
- 目标：验证引用会话中的 `alibaba.damai.ec.search.project.search` 是否可直接查询薛之谦演出。
- 官方文档：https://developer.alibaba.com/docs/api.htm?apiId=39475

## 请求与实际返回

官方请求向 `https://eco.taobao.com/router/rest` 发送 POST，正文为 UTF-8 application/x-www-form-urlencoded。公共参数如下；timestamp 按每次请求的北京时间生成。

```text
method=alibaba.damai.ec.search.project.search
format=json
v=2.0
sign_method=md5
timestamp=2026-11-14 23:35:42
param={"artist_name":"薛之谦","page_number":1,"page_size":10}
```

| 测试 | 附加参数 | HTTP | 实际业务结果 |
| --- | --- | --- | --- |
| 缺少应用凭证 | 无 app_key，无 sign | 200 | code=28，Missing app key |
| 无效应用凭证 | app_key=codex-feasibility-invalid-key，sign 为 32 个 0 | 200 | code=29，Invalid app Key，sub_code=isv.appkey-not-exists |
| 网页匿名搜索 | GET `https://search.damai.cn/searchajax.html?keyword=%E8%96%9B%E4%B9%8B%E8%B0%A6&currPage=1&pageSize=10` | 200 | text/html；验证码拦截脚本，含 captcha、punish、x5sec、RGV587，没有演出 JSON |

官方返回原文（请求 ID 保留以便核对）：

```json
{"error_response":{"code":28,"msg":"Missing app key","sub_msg":"Http传入的参数必需包含app_key字段","request_id":"15rmgjm4zpw9j"}}
{"error_response":{"code":29,"msg":"Invalid app Key","sub_code":"isv.appkey-not-exists","request_id":"15sapklk1ed7j"}}
```

请求头使用 `User-Agent: StarLineAgent-FeasibilityProbe/1.0` 和 `Accept: application/json`。未提供 Cookie 或登录态，未尝试绕过验证码。拦截页内的安全令牌不保存。

## 结论与验证边界

1. 官方网关当前可达，确实执行了应用凭证校验；HTTP 200 不代表搜索成功。
2. 上述错误出现在凭证校验阶段，**不能证明该 API 方法仍可调用、普通开发者能申请权限，或已返回真实演出数据**。
3. 官方文档虽标注“不需要授权”，公共参数仍要求 app_key 和 sign；文档将 session 定义为用户授权信息，因此不能把“不需要授权”理解为匿名开放。
4. 当前环境未发现大麦/淘宝应用凭证，项目中未发现对应凭证配置线索，未完成有效签名查询。没有据此判定官方 API 已停用或不可用。
5. 网页搜索接口在本机本次匿名请求中触发验证码，不适合据此视为可直接接入的稳定数据源；该结果不代表所有客户端和网络的行为。

## 下一步验收条件

- 在淘宝开放平台控制台确认应用可申请 `alibaba.damai.ec.search.project.search` 权限，准备本地保管的 AppKey/AppSecret。
- 按官方签名规则发送有效请求；业务字段放入 JSON 字符串参数 `param`，而不是平铺在公共参数中。密钥不写入仓库、日志或聊天。
- 先查薛之谦全国首屏，确认业务 success 和项目列表，再检查上海、日期过滤、分页、场馆、时间和购票链接。空列表本身不能证明覆盖完整。
- 在取得一次真实业务成功响应之前，StarLineAgent 的大麦官方数据源接入可行性维持“待凭证及权限验证”。

## 执行异常

第一次在沙箱内执行时，三次请求均被本地网络权限阻止（WinError 10013），未取得远端响应。随后通过网络权限提升重试，得到上表真实返回。本轮只执行三次到达远端的查询请求，未部署、未修改应用运行代码。
