# CodeMentor 1.2.6 安装与生命周期指南

补丁版本日期：2026-08-24。

## 兼容范围

- IntelliJ IDEA 2024.1.x（build `241.*`）。
- 从源码构建使用 JDK 17；从 ZIP 安装后使用 IDEA 自带的 JetBrains Runtime。
- AI 功能需要使用者自行准备兼容模型服务及 API Key，可能产生第三方费用。
- 默认端点是智谱开放平台 HTTPS 接口，默认模型是 `glm-5.2`；二者均可配置。

## 从源码构建

Windows PowerShell：

```powershell
.\gradlew.bat clean test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin
```

Linux / macOS：

```bash
chmod +x ./gradlew
./gradlew clean test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin
```

成功后目标安装包位于 `build/distributions/code-mentor-1.2.6.zip`。Gradle Wrapper 从官方地址下载 9.0.0，并使用仓库中固定的 SHA-256 校验发行包；CI 还会在执行 Wrapper 前校验仓库内 launcher JAR。首次运行会下载目标 IDE 和 Plugin Verifier，因此耗时和磁盘占用会较大。本仓库当前 1.2.6 产物的实测结果见文末“发布验证状态”。

开发时可运行：

```powershell
.\gradlew.bat runIde
```

这会启动独立的沙箱 IDEA，不会把开发版插件安装到日常 IDE。

## 从 ZIP 安装

1. 打开 `Settings / Preferences → Plugins`。
2. 点击齿轮菜单，选择 `Install Plugin from Disk...`。
3. 选择 `code-mentor-1.2.6.zip`，不要提前解压；正式交付前请确认它已完成最终验证并核对发布方提供的 SHA-256。
4. 按提示重启 IDE。
5. 进入 `Settings / Preferences → Tools → CodeMentor` 完成下一节配置。

## 首次配置

1. 从“常用模型预设”选择智谱 GLM-5.2、DeepSeek V4 Flash/Pro、通义千问 Qwen Plus、OpenAI GPT-4.1 mini 或 Gemini 3.7 Flash，也可以保留“自定义”并手动填写地址。
2. 预设只填充 API 地址和模型名，API Key 输入框保持空白；选择预设不会删除 PasswordSafe 中已有的凭据。DeepSeek 官方当前使用 `deepseek-v4-flash` 或 `deepseek-v4-pro`，旧的 `deepseek-chat` / `deepseek-reasoner` 已停用。
3. 输入 API Key。插件只写入 IntelliJ PasswordSafe，不会把已保存密钥回填到设置页。
4. 按需选择“初学、进阶、JavaWeb”教学级别、文件排除规则和“每次手动评审或解释前显示完整脱敏预览”。评审和解释会使用不同的教学目标，同一级别下的讲解重点也不同。
5. 保存时自动评审默认关闭，建议先仅使用手动评审。
6. 点击“测试连接”。结果会显示在设置页内，不会用成功/失败弹窗打断配置；测试发送固定、合成的导师 JSON 协议提示，不读取或发送项目代码、规则、历史或会话内容。

端点默认必须使用 HTTPS。无路径根地址会补全 `/chat/completions`，以 `/v1` 结尾的 Base URL 会在其后补全该路径，完整地址不会重复追加；连接测试会显示实际请求地址。URL 中的用户名/密码、查询参数和片段会被拒绝。只有本机模型或调试代理确有需要时，才可显式开启“允许 HTTP 本机回环端点”；允许范围仅为 `localhost`、`127.0.0.0/8` 和 `::1`，普通远程 HTTP 始终拒绝。

首次需要把某项目的未缓存评审/解释上下文发送到当前端点时，会出现可滚动的脱敏预览，最多展示 32,000 个字符；超出时会明确标记截断。选择“发送并记住此地址”后，授权只匹配这个项目和规范化端点。切换到未授权端点时，需要通过新的手动评审/解释重新确认；模型名变化本身不创建新的授权范围。

## 升级与回退

升级时直接通过 `Install Plugin from Disk...` 选择同一插件 ID 的新 ZIP 并重启。非敏感设置、PasswordSafe 凭据、项目 workspace 数据和学习历史通常会保留。

升级到 1.2.6 不改变任何请求发送范围、脱敏规则、授权流程与本地数据格式；这是一次内部质量重构（模型客户端骨架统一、连接测试独立成类、Coordinator 守卫集中、提示词证据长度与校验常量共用），对外行为与 1.2.5 一致。自 1.2.4 起，连接测试使用固定合成 JSON 协议提示且不读取项目数据；官方 DeepSeek 请求显式关闭思考并为结构化结果提供 8,192 tokens 生成预算，其他端点仍为 2,400。Base URL 会保存为规范化后的最终请求端点；如果它与旧授权端点不同，下一次需要发送的手动评审/解释会重新显示预览并要求确认。自 1.1.0 起，插件不再把完整代码或模型证据写入学习历史；1.2.0 起也不会写入追问问题或回答，但不会擅自删除旧开发版本已经写入的 JSONL。如需消除旧记录，请升级后使用侧栏“更多… → 隐私与数据 → 清除全部本地学习记录”。

1.2.0 更新侧栏交互但保留原有快捷键：空状态可直接评审、解释或打开设置；评审、追问等待和练习生成均可取消；失败后可重试或打开设置；次要操作移到“更多…”菜单。同一次评审内的讲解/示例展开状态与滚动位置会在反馈、验证和追问刷新时保持。追问区只显示当前会话中每条建议最近一次问题与回答，新评审、会话重置或项目关闭后消失。修改验证只对完整方法开放；选中片段仍可评审/解释，但需把光标放在完整方法内重新评审后才能验证修改。

回退前先通过“导出复习”保留所需摘要，并备份本地数据。较新版本的数据格式不保证能被任意旧版本完整识别。回退时先禁用或卸载当前版本，再安装此前保存的 ZIP。

## 安全卸载与 UI 清理

卸载只移除插件代码，不会自动删除 API Key、设置、项目授权或本地历史。若希望彻底清理，请在卸载前优先使用插件 UI：

1. 等待侧栏中正在进行的操作结束；评审/解释可点击“取消本次请求”，追问等待时可点击“取消追问”，练习可点击“取消生成”。修改验证没有单独取消入口，但新评审、同一文件继续变化或 reset 会取消它。取消不能召回服务端已经接收的数据。
2. 如需保留内容，点击“导出复习”。
3. 点击侧栏“更多… → 隐私与数据…”：
   - 选择“撤销本项目发送授权”；插件会重置会话并尽力取消该项目仍在进行的模型任务，但无法召回服务端已经收到的数据；
   - 再次打开并选择“清除全部本地学习记录”。该操作会先重置所有已打开项目的 session、缓存和在途学习任务，再删除所有项目的活动、轮转和临时轮转历史文件，且不可撤销。
4. 打开 `Settings / Preferences → Tools → CodeMentor`：
   - 关闭评审和保存触发；
   - 点击“撤销已打开项目的发送授权”；
   - 勾选“删除 PasswordSafe 中已保存的 API Key”，点击 `Apply`。
5. 打开 `Settings / Preferences → Plugins → Installed`，找到 CodeMentor，选择 `Uninstall` 并重启 IDE。

设置页的撤销按钮只处理当前已打开的项目，并会尽力取消这些项目中的模型任务。更换端点、关闭总功能开关或更改/清除 API Key 也会取消所有已打开项目请求。已关闭项目的 workspace 授权元数据不会被该按钮遍历；这不会允许卸载后的插件发送数据，但若需要清除磁盘痕迹，可按下一节兜底处理。追问问题与回答不需要磁盘清理：它们只存在于当前内存会话，不写入学习历史或 Markdown 导出。

## UI 不可用时的手工兜底

先完全关闭 IntelliJ IDEA，并备份需要保留的文件：

- 学习历史：删除 IntelliJ system 目录下 `codementor/learning-events.jsonl`、`learning-events.jsonl.1` 和 `learning-events.jsonl.rotation.tmp`。
- 应用设置：删除 IntelliJ config 目录下的 `options/codeMentor.xml`。
- 项目授权：只从项目 workspace 元数据中移除 `CodeMentorProjectConsent` 组件。不要直接删除整个 `.idea/workspace.xml`，其中还包含其他 IDE 状态。
- API Key：在 IDE 实际使用的 PasswordSafe 后端中查找服务名 `CodeMentor`、用户名 `apiKey`。具体后端与管理方式取决于操作系统和 IDE 配置。

可通过 `Help → Diagnostic Tools → Special Files and Folders` 查看当前 IDE 实际使用的 config 和 system 根目录。完整数据边界见仓库根目录的 `PRIVACY.md`。

## Marketplace 签名与发布

仓库中的普通 CI 只测试、验证和构建插件，不持有作者证书，也不会自动发布。直接构建的 ZIP 不代表已由维护者签名或已通过 JetBrains Marketplace 审核。

维护者要运行 `signPlugin` / `publishPlugin` 流程，必须自行准备 JetBrains Marketplace 账号和私密凭据，通常包括：

- `CERTIFICATE_CHAIN`
- `PRIVATE_KEY`
- `PRIVATE_KEY_PASSWORD`
- `PUBLISH_TOKEN`

这些值只能通过本地安全环境变量或 CI Secret 注入，不得提交到仓库、构建日志或发布产物。启用自动签名/发布前还要在 `build.gradle.kts` 显式配置任务并人工确认目标 channel；项目默认不执行发布操作。

## 1.2.6 发布验证状态

- 验证日期：2026-08-24。
- 完整测试套件：132 项，0 失败、0 错误、0 跳过，共 29 个测试套件。
- `verifyPluginProjectConfiguration` 与 `verifyPluginStructure`：通过。
- Plugin Verifier 1.409：`com.codementor:1.2.6` 对 IntelliJ IDEA 2024.1.1（IC-241.15989.150）为 `Compatible`。
- ZIP 内容：`code-mentor/lib/code-mentor-1.2.6.jar`；JAR 内已核对 `META-INF/plugin.xml`、`LICENSE`、`NOTICE`、`PRIVACY.md` 和 `pluginIcon.svg`。
- 插件元数据：ID `com.codementor`，版本 `1.2.6`，兼容范围 `241` 至 `241.*`。
- 安装包大小：277,275 bytes（约 270.8 KiB）。
- SHA-256：`D56C8A658161D3086EB67B45DA0584665EA2004E10816FE0487CFCD301D861A5`。

该 ZIP 是本地构建产物，未执行 Marketplace 签名或发布。
