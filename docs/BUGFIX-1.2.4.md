# CodeMentor 1.2.4 模型空内容修复

发布日期：2026-08-24。

## 根因

旧版设置页只要求模型返回很短的 `OK`，没有验证真实导师使用的 JSON Output 协议。DeepSeek V4 默认开启思考模式，思考过程位于 `reasoning_content`，最终回答位于 `content`；CodeMentor 不能把内部推理冒充导师结果。DeepSeek 官方还明确记录 JSON Output 偶尔会返回空 `content`，并建议修改提示以及合理设置 `max_tokens`。

- [DeepSeek JSON Output 文档](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)
- [DeepSeek 思考模式文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)

因此连接与鉴权可以正常，但真实代码评审仍可能得到空最终答案；旧版第二次尝试又原样重发同一个请求，无法缓解服务商已知问题。

## 修复

- 仅对 `api.deepseek.com` 显式发送 `thinking: {"type":"disabled"}`，其他兼容服务不接收该私有字段。
- DeepSeek 结构化请求使用 8,192 tokens 输出预算，其他端点保持 2,400。
- 首次空答案或格式失败后，第二次请求追加明确的最终 JSON/纯文本修复要求并降低温度。
- 空 content 会按输出长度耗尽、内容过滤、仅有 reasoning 或普通空答案分类；不记录或展示 `reasoning_content`。
- 设置页改用合成导师 JSON 探测，不发送项目数据；显示成功即代表真实导师所需的基础协议可用。

接收侧的 2 MiB HTTP 正文、64 Ki 字符最终 content、字段长度、建议数量、证据核验、发送授权和取消边界全部保留。

## 验证

本机端到端测试会让临时服务第一次返回 reasoning-only 空 content，第二次返回合法评审 JSON。测试只有在真实 CodeMentor worker 经过 HTTP、修改后的协议重试、解析器和 callback 得到成功 `ReviewResult` 时才通过。

最终发布测试、Plugin Verifier、安装包大小和 SHA-256 见 [安装指南](INSTALL.md#124-发布验证状态)。
