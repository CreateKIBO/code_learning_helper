# CodeMentor 1.2.2 OpenAI 兼容端点 404 修复

日期：2026-08-24

## 用户可见问题

设置页名称和常见 SDK 文档让使用者自然填写 `https://api.deepseek.com` 或 `https://api.deepseek.com/v1` 这样的 Base URL，但 1.2.1 传输层直接向该字符串执行 POST，没有追加 Chat Completions 路径。API Key 通过认证后，服务端因此可能返回 404。即使地址完整，DeepSeek 已停用的旧模型名也可能产生相同状态码。

## 1.2.2 行为

- `https://api.deepseek.com` → `https://api.deepseek.com/chat/completions`
- `https://api.deepseek.com/v1` → `https://api.deepseek.com/v1/chat/completions`
- `https://api.deepseek.com/v1/chat/completions` → 保持不变
- 其他已经包含自定义路径的完整端点 → 保持不变
- DeepSeek 官方 `/beta` Base URL → 补全 `/chat/completions`

连接测试在进行中显示规范化后的实际请求地址。404 错误同时给出“检查最终路径”和“检查模型名”两个排查方向。DeepSeek 官方端点使用 `deepseek-chat` 或 `deepseek-reasoner` 时会在本地提示改用 `deepseek-v4-flash` / `deepseek-v4-pro`，不会静默替换模型；第三方兼容端点仍可自行支持旧别名。

## 常用模型预设

设置页提供智谱 GLM-5.2、DeepSeek V4 Flash/Pro、通义千问 Qwen Plus、OpenAI GPT-4.1 mini 和 Gemini 3.7 Flash。预设只包含显示名、API 地址与模型名，不包含 API Key。选择预设会清空尚未保存的 Key 输入框，但不会删除 PasswordSafe 中已经保存的凭据。

## 安全说明

用户在聊天中发送的完整 API Key 没有写入仓库、命令、日志或测试夹具，也没有用于外部请求。端点连通性探测只使用无效占位密钥。该已暴露密钥应在 DeepSeek 控制台立即撤销并轮换。

## 验证状态

- Base URL 规范化、真实本机 HTTP 路径、旧模型拦截、404 诊断、预设匹配和 Key 输入框清空聚焦回归：通过。
- 完整测试套件：117 项，0 失败、0 错误、0 跳过。
- 项目配置与插件结构检查：通过。
- Plugin Verifier 1.409：IDEA 2024.1.1（IC-241.15989.150）`Compatible`。
- 安装包：`build/distributions/code-mentor-1.2.2.zip`，265,427 bytes（约 259.2 KiB）。
- SHA-256：`04253FE4B8B4394063DCBBC2D751552C1788622765B8E70E69325B3EB7099D48`。
