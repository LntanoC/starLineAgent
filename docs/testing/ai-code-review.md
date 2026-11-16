# OpenCodeReview 使用说明

## 本地 Codex 委托审查

本机安装的 `open-code-review-delegate` Skill 使用 OpenCodeReview CLI 确定审查文件和规则，由 Codex 审查，不需要为 OCR 配置模型 API。重新开始 Codex 任务后可请求使用该 Skill。CLI 版本固定为 1.13.10。

在仓库根目录执行 `ocr delegate preview --format json` 可预览工作区审查范围；该模式包含暂存、未暂存和未跟踪文件。审查指定提交可执行 `ocr delegate preview --commit <提交号> --format json`。预览中的排除文件不代表已审查，应核对排除原因。

## GitHub Actions

`.github/workflows/ai-review.yml` 在可信贡献者从本仓库创建或更新非草稿 PR 时审查；外部 fork 和草稿跳过，直接推送 main 不触发。它会向 DeepSeek 发送审查所需的代码差异及上下文，并在 PR 发布评论；先人工判断评论，不作为合并必需检查。

在 GitHub 仓库 Settings → Secrets and variables → Actions 中设置：

- Secret `DEEPSEEK_API_KEY`：填写 DeepSeek API 密钥，不要写入仓库、日志或聊天。
- Variable `OCR_LLM_MODEL`：填写账户可用的模型名称，例如 `deepseek-flash`；确保账户有可用额度。模型配置与计费以 DeepSeek 当前文档为准。

工作流使用 `https://api.deepseek.com/chat/completions`，固定上游 Action 提交和 OCR 1.13.10。每次 PR 更新都会触发一次审查并产生模型 API 用量；新运行会取消该 PR 尚未完成的旧运行，但已发送的请求仍可能计费。草稿转为可审查时也会触发。未配置密钥或模型时，符合条件的工作流会失败并提示配置。

`.opencodereview/rule.json` 让 Java 测试和前端测试绕过工具的默认测试文件排除规则；它不是文件白名单。使用 `ocr delegate preview` 或 `ocr review --preview` 检查具体文件及排除原因。

接入后应通过小范围 PR 验证模型连通、行内评论位置、测试文件覆盖及费用；真实密钥和模型尚未配置前，不能视为远端验证通过。审查日志和 PR 评论可能包含代码内容，注意仓库可见性。Review 不替代编译、测试或人工判断。官方 Action 检出基础分支读取规则，因此本 PR 修改的规则在合并到基础分支后生效。
