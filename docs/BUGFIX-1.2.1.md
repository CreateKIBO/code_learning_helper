# CodeMentor 1.2.1 设置与连接稳定性修复

日期：2026-08-24

## 用户可见修复

- Settings 中的 API Key 状态会在 PasswordSafe 后台读取结束后立即显示“已配置”“尚未配置”或读取失败，不再等关闭设置窗口。
- “测试连接”会在请求成功、失败、超时或后台任务无法启动时恢复为可点击状态，并在原位置显示结果。
- 异常端点只发送响应头、不继续发送正文时，连接测试和正常学习请求最多等待 30 秒。

## 根因与边界

Settings 是模态窗口。旧实现从后台线程调用不带 modality 参数的 `Application.invokeLater`，完成回调被放入 non-modal 队列，只能在 Settings 关闭后执行。1.2.1 仅对这两个纯 Swing 状态更新使用 `ModalityState.any()`，并继续用 disposed 与 generation 防止旧窗口/旧请求回调串线。

传输层旧实现使用流式 `InputStream`，响应正文读取发生在响应 Future 完成之后，端点在响应头后停滞时可能绕过请求超时。1.2.1 使用最大 2 MiB 的有界正文订阅，并对包含完整正文的 Future 显式执行 30 秒超时；超时同时取消 Future 与订阅。

本补丁不改变 API Key 的 PasswordSafe 存储、项目 × 端点授权、发送内容、学习历史或模型教学协议。

## 验证

- 设置 modal queue 对照测试：通过。
- 正常 HTTP 契约、声明长度超限、未知长度超限、错误摘要和正文停滞超时聚焦测试：通过。
- 完整测试套件：109 项，0 失败、0 错误、0 跳过。
- 项目配置与插件结构检查：通过。
- Plugin Verifier 1.409：IDEA 2024.1.1（IC-241.15989.150）`Compatible`。
- 安装包：`build/distributions/code-mentor-1.2.1.zip`，260,704 bytes（约 254.6 KiB）。
- SHA-256：`62EB2B6A08E8659EEC3734EA8A8C644BC68DAC496D14B1F25850FA5A0C98E625`。
