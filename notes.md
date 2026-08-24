# Notes: CodeMentor 1.2.0 体验升级

## Baseline
- 1.1.0 安装包已经通过 62 项测试、插件结构检查和 IDEA 2024.1.1 Plugin Verifier。
- 当前工作区保留 1.1.0 的未提交实现，必须在其上增量修改，不能覆盖或回退。
- 已有核心安全边界：项目与端点授权、脱敏预览、项目路径/排除规则、不可变请求配置和可取消请求。

## Research Findings

### 交互与状态
- `MentorToolWindowPanel.render()` 在每次会话变化时重建全部卡片；卡片本地展开状态和滚动位置会在反馈、验证、追问后丢失。
- 顶部六个按钮放在窄侧栏 `EAST`，动作层级不清晰。保留重评/练习为高频动作，其余归入“更多”菜单。
- 空状态只有不可点击的设置提示；请求中没有取消；失败状态没有就地“打开设置”入口。
- 解释模式仍显示英文 `MEDIUM`，教学级别下拉也直接显示枚举英文。
- 官方 IntelliJ UI 指南建议复用平台组件、垂直 Tool Window 使用水平工具栏、溢出动作分组，并确保键盘可操作与局部错误反馈：
  - https://plugins.jetbrains.com/docs/intellij/user-interface-components.html
  - https://plugins.jetbrains.com/docs/intellij/toolbar.html
  - https://plugins.jetbrains.com/docs/intellij/accessibility.html

### AI 教学闭环
- Prompt 已要求 `summary`，但解析器、服务、缓存和会话只传 `List<Suggestion>`；零建议成功因此显示为空。
- 建议缺少“最小下一步”和“自测问题”；`verificationCriteria` 已存在但 UI 未展示。
- EXPLAIN 复用了评审导向的教学级别焦点，和“不评判、不要求修改”的系统约束冲突。
- 上下文截断状态未告诉模型；超长目标只保留开头，容易丢失 return/catch/收尾路径。
- `evidence` 仅非空校验，尚未要求或本地验证为发送上下文中的原文片段。
- 重复知识点练习只按 REVIEWED 次数计数，会继续推荐已 RESOLVED 或标记误报的知识点。

### 本轮实现范围
- 引入不可变 `ReviewResult(summary, suggestions)`，完整贯穿解析、服务、缓存、会话和 UI。
- 为 Suggestion 增加 `nextStep` 与 `selfCheckQuestion`；显示验证标准，优化提示词顺序和截断约束。
- 新增本地 evidence 原文匹配，阻止无依据建议进入 UI；协议 schema 升级以隔离旧缓存。
- 会话单独保存卡片展开状态，面板按 reviewId 保留滚动位置；追问结果保存原问题。
- 增加快捷追问、请求取消、首次使用主动作、空建议正向状态、失败设置入口和动作分组。
- 设置页中文化教学级别、互斥 Key 删除/输入，并将连接结果放回触发位置。
- 重复知识点从聚合后的未解决且非误报项计算，和学习统计共享同一规则。

### 明确不做
- 不自动修改、插入或复制模型代码到用户源文件。
- 不增加发送的文件范围、历史内容或授权范围。
- 不引入内部/实验性 JetBrains API，也不做与 IDEA 主题冲突的自绘视觉系统。

### 已落地的体验闭环
- 空状态提供评审、解释和模型设置三个直接入口；请求与练习可取消，失败状态可就地重试或打开设置。
- 高频操作留在顶部，发送预览、学习统计、导出、隐私和设置收进“更多”；同一评审重绘时保留卡片展开与滚动位置。
- 建议按“代码依据 → 先想一想 → 最小下一步 → 完成标准 → 折叠讲解/自测/示例”展示，追问保留原问题并提供教学型快捷问题。
- 小练习提示默认隐藏并逐条揭示，避免一开始暴露全部解题路径。
- 模型结果增加整体总结、最小下一步和自测问题；本地拒绝无法在实际发送上下文中定位的 evidence，并显式说明静态分析与截断边界。
- 完整方法修改验证使用活体锚点复定位；整篇文件替换/外部重载会使锚点失效，验证结束后继续编辑也会清除只属于上一文档版本的结论。
- 薄弱点只使用带可靠评审标识的记录做跨评审计数；旧记录仍可统计/导出但不会伪造重复次数，fallback 练习在派发前和响应落地前都会重新检查当前状态。

## Verification Evidence

### Final verification
- 全量回归：106 tests，0 failures / errors / skipped。
- 干净 Gradle 发布流水线、项目配置与结构检查通过。
- Plugin Verifier：IDEA 2024.1.1（IC-241.15989.150）`Compatible`；在线 documented-problems 页面有本机 JBR PKIX 告警，但二进制检查与任务完成。
- 最终 ZIP：257,669 bytes；SHA-256 `F0E1CD2BE49699F192E95AA0D8CE0A84D49DD4DC2AC9D0A159ABAEA7A2E06416`。

## 2026-08-24 设置页异步卡死报告

### 用户可见症状
- 打开设置页后 API Key 状态一直停留在“正在检查”。
- 配置模型与 Key 后点击“测试连接”，按钮一直停留在“测试中”。

### Investigation pending
- 根因已确认：`CodeMentorConfigurable` 从应用后台执行器调用无 modality 参数的 `Application.invokeLater`。后台线程的默认 modality 等价于 non-modal，Settings 对话框打开时回调会排队到窗口关闭后；API Key 标签和连接按钮因此一直 loading。
- JetBrains 官方线程模型说明 `ModalityState.any()` 只适合纯 UI 更新；本场景回调只更新当前 Configurable 的 JLabel/JButton，并有 disposed + generation 守卫，适合统一使用该状态。
- 同类风险：`ModelHttpTransport` 使用 `BodyHandlers.ofInputStream()`，其 completion stage 在正文读取前完成；请求 30 秒 timeout 未必覆盖之后的 `readNBytes`，恶意/异常端点在返回响应头后停住可让连接测试和模型请求长期阻塞。
- 实验进一步确认：只把 InputStream 换成完整正文 `BodySubscriber` 仍不足以让 `HttpRequest.timeout` 在测试服务器“发头后停住”时生效；必须对 `sendAsync` 的完整响应 Future 显式计时并取消订阅。
- 其余默认 `invokeLater` 调用会触及 session/PSI/项目状态，等待模态窗口关闭是安全语义，不应机械替换为 `any()`；Tool Window 的 Swing 回调不属于 Settings 永久 loading 根因。

### Implemented and focused verification
- Settings 的两个纯 UI 完成回调统一经过 `invokeOnSettingsUi(..., ModalityState.any())`，保留 disposed/generation 守卫；executor 拒绝任务时立即恢复标签/按钮。
- HTTP 改为有界 `BodySubscriber` + 完整 `sendAsync` Future 显式超时，超时取消 Future 与订阅；声明长度和未知长度响应都限制为 2 MiB。
- 6 项聚焦测试全部通过，包括真实 modal queue 对照和“响应头后正文停住”的本机 HTTP 测试。

### Final 1.2.1 verification
- 完整测试：109 项，0 failures / errors / skipped。
- `verifyPluginProjectConfiguration`、`verifyPluginStructure`：通过。
- Plugin Verifier 1.409：IDEA 2024.1.1（IC-241.15989.150）`Compatible`，本次无在线证书链告警。
- ZIP：260,704 bytes；SHA-256 `62EB2B6A08E8659EEC3734EA8A8C644BC68DAC496D14B1F25850FA5A0C98E625`。

## 2026-08-24 - OpenAI 兼容端点 404 跟进

### Symptom and security boundary
- 用户报告 DeepSeek API Key 与 URL 配置后，连接测试稳定返回 HTTP 404。
- 用户在聊天中粘贴了完整密钥；该密钥不进入仓库、命令、日志或自动化测试，并应立即撤销/轮换。
- 初步检索显示插件配置字段虽然名为 `apiBaseUrl`，传输层却把它直接作为最终 POST URI，默认值本身也是完整 `/chat/completions` 地址；需要确认 UI 是否误导用户输入了根地址。

### Confirmed root causes
- DeepSeek 官方 Quick Start 把 OpenAI `base_url` 定义为 `https://api.deepseek.com`，实际 Chat API 是 `POST /chat/completions`；官方集成文档也给出 `https://api.deepseek.com/v1/chat/completions`。
- 插件的 `ModelEndpointValidator` 旧实现拒绝无路径根地址，并把 `/v1` 原样作为最终 POST 地址；设置字段的 “Base URL” 命名与传输层的“最终端点”语义不一致。
- DeepSeek 2026-04-24 变更记录宣布 `deepseek-chat` 与 `deepseek-reasoner` 于 2026-07-24 停用；当前官方模型名为 `deepseek-v4-flash` 与 `deepseek-v4-pro`。正确路径配旧模型仍可能返回 404。
- 受限网络下 curl 首次返回连接失败；放行后用无效占位密钥访问根地址、`/v1` 和完整端点均返回 401，证明本机到 DeepSeek 的网络/TLS/认证入口可达。认证失败发生在路由/模型检查之前，因而不需要也不应使用已泄露的真实密钥定位本插件的 URL 拼接问题。

### Preset scope
- 用户追加要求预置常见模型配置，API Key 留空。
- 只选择有官方 OpenAI Chat Completions 兼容说明且能接受现有 Bearer + messages + JSON object 契约的配置：智谱 GLM-5.2、DeepSeek V4 Flash/Pro、通义千问 Qwen Plus、OpenAI GPT-4.1 mini、Gemini 3.7 Flash。
- 预设是 UI 派生状态，不新增持久化字段；未知或用户编辑过的组合显示“自定义”。

### Focused verification
- Base URL 规范化、HTTP 404、完整正文超时、真实本机 `/v1/chat/completions` 路径、官方 DeepSeek 旧模型拦截、Settings 模态回调和模型预设聚焦测试均通过。
- Settings 交互测试确认：选择 DeepSeek 预设后地址与模型正确填入，尚未保存的 API Key 输入框被清空。

### Final 1.2.2 verification
- 完整测试：117 项，0 failures / errors / skipped，25 个测试套件。
- `verifyPluginProjectConfiguration`、`verifyPluginStructure`：通过。
- Plugin Verifier 1.409：`com.codementor:1.2.2` 对 IDEA 2024.1.1（IC-241.15989.150）为 `Compatible`。
- ZIP：265,427 bytes；SHA-256 `04253FE4B8B4394063DCBBC2D751552C1788622765B8E70E69325B3EB7099D48`。
- ZIP 只包含 `code-mentor/lib/code-mentor-1.2.2.jar`；JAR 内已核对 plugin.xml、LICENSE、NOTICE、PRIVACY.md 与图标，元数据为 ID `com.codementor`、版本 `1.2.2`、兼容范围 `241` 至 `241.*`。

## 2026-08-24 - 导师请求超时与加载动效

### Initial evidence
- 用户确认设置页连接测试已经成功，但真实使用稳定显示“导师请求失败 / 请求超时”。
- `ModelHttpTransport` 当前默认完整响应超时仍为 30 秒；`ZhiPuModelClient` 的连接测试和真实评审/解释/验证/追问/练习都构造相同默认 transport。最小“只回复 OK”能在 30 秒内成功，不能证明结构化代码评审也能在同一时间内完成。
- UI/UX 检索建议动效克制、反馈靠近触发操作并保持可访问性；本插件应优先使用 IntelliJ 原生小型进度图标，避免额外色彩、遮罩或布局缩放。

### Confirmed design
- IDEA 2024.1 的 `com.intellij.util.ui.AsyncProcessIcon` 是一个实现 `Disposable` 的 Swing 组件，提供 `resume()`、`suspend()`、`isRunning()` 和 `dispose()`；适合由 Tool Window 生命周期直接管理。
- 会话内已有四类独立忙碌信号：`ReviewStatus.REQUESTING`、任意 `VerificationStatus.VERIFYING`、任意 `FollowUpResult.Status.REQUESTING`、`ExerciseResult.Status.GENERATING`。应在 `ReviewSession` 集中派生，UI 不再重复遍历规则。
- Tool Window 另有学习统计、Markdown 导出和清除历史三个本地 Future；它们不是会话模型状态，需要与会话忙碌状态在面板内合并，但仍不新增第二份业务状态。

### Implemented and focused verification
- `ZhiPuModelClient` 的真实导师 transport 显式使用 120 秒，连接探测 transport 显式使用 30 秒；二者继续使用 10 秒连接建立上限以及同一有界正文/取消实现。
- `ReviewSession.hasPendingOperation()` 集中派生所有模型忙碌状态；Tool Window 的原生 `AsyncProcessIcon` 合并本地统计、导出和清理 Future，固定占位、空闲不绘制、面板销毁时释放。
- 审计发现隐藏窗口中的 `Animator.isRunning()` 不代表请求状态；每次刷新改为无条件按 busy 调用 `resume/suspend`，避免任务在隐藏期间结束后残留动画。
- 聚焦回归 22 项全部通过，覆盖完整正文超时、30/120 秒策略、四类 session 忙碌生命周期以及 Tool Window 紧凑尺寸、忙闲提示和销毁。

### Final 1.2.3 verification
- 完整测试：120 项，0 failures / errors / skipped，26 个测试套件。
- `verifyPluginProjectConfiguration`、`verifyPluginStructure`：通过。
- Plugin Verifier 1.409：`com.codementor:1.2.3` 对 IDEA 2024.1.1（IC-241.15989.150）为 `Compatible`。
- ZIP：266,710 bytes；SHA-256 `61E88C85C786A8B0A236430BCF06CB1E1A2744BEDA2F26098B815085E37791AA`。
- ZIP 只包含 `code-mentor/lib/code-mentor-1.2.3.jar`；JAR 内已核对 plugin.xml、LICENSE、NOTICE、PRIVACY.md 与图标，元数据为 ID `com.codementor`、版本 `1.2.3`、兼容范围 `241` 至 `241.*`。

## 2026-08-24 - 模型最终内容为空跟进

### Confirmed root cause
- 用户实际错误由 `JsonResponseParser.validateResponseText()` 抛出：HTTP 200 且 `choices[0].message.content` 是空字符串；不是 URL、认证、超时或 JSON 外层响应读取失败。
- `ZhiPuModelClient` 当前给连接测试与真实请求发送相同的普通 Chat Completions 形态，但真实结构化请求带 `response_format=json_object` 且 `max_tokens=2400`；格式失败后的第二次调用原样复用相同请求体。
- DeepSeek 官方 JSON Output 文档明确记录该模式“有概率返回空 content”，建议修改 prompt 并合理设置 `max_tokens`：https://api-docs.deepseek.com/zh-cn/guides/json_mode/
- DeepSeek V4 官方思考模式默认启用，推理放在 `reasoning_content`、最终答案才放在 `content`；当前插件既未显式关闭思考，也不能把内部推理当成最终教学 JSON：https://api-docs.deepseek.com/zh-cn/guides/thinking_mode
- 因此最小 `OK` 连接探测可以成功，而复杂导师 JSON 可能在有限输出预算内只产生推理或触发 DeepSeek 已知的空 content；原样重试无法执行官方建议的 prompt 缓解。

### Fix direction
- 只对 `api.deepseek.com` 官方 OpenAI 端点显式发送 `thinking.type=disabled`，不把 DeepSeek 私有字段发给其他兼容服务。
- 官方 DeepSeek 的结构化请求把输出预算提高到 8192 tokens；其他端点保持 2400，避免破坏自定义小模型兼容性。
- 空 `content` 由传输层结合 `finish_reason`/是否存在 reasoning 安全分类，不记录原文；结构化解析或空内容首次失败后，第二次请求追加明确的最终 JSON/纯文本修复指令，而不是原样重放。
- 增加本机 HTTP 端到端测试：首个响应模拟空 content，第二个返回合法评审 JSON，必须通过真实 client worker 与 callback 成功闭环。

### Implemented and focused verification
- 官方 DeepSeek 请求体现在显式包含 `thinking.type=disabled`；结构化请求 `max_tokens=8192`，普通兼容端点继续 `2400` 且没有 `thinking` 字段。
- `ModelHttpTransport.EmptyContentException` 只暴露安全分类；reasoning-only 测试确认错误消息不包含模拟思考原文。
- 第二次有限尝试会基于原请求体追加固定协议修复指令，结构化请求明确从 `{` 开始只给完整 JSON，纯文本请求继续遵循各自系统格式。
- 设置页连接测试改为合成 `ReviewResult` JSON 协议探测，并复用同一空内容/解析重试；不读取项目数据。
- 13 项聚焦测试全部通过；其中 `ZhiPuModelClientReviewTest` 使用本机临时 HTTP 服务，第一次返回 reasoning-only 空 content，第二次返回合法空建议评审，真实 worker 最终触发成功 callback。

### Final 1.2.4 verification
- 完整测试：124 项，0 failures / errors / skipped，27 个测试套件。
- `verifyPluginProjectConfiguration`、`verifyPluginStructure`：通过。
- Plugin Verifier 1.409：`com.codementor:1.2.4` 对 IDEA 2024.1.1（IC-241.15989.150）为 `Compatible`。
- ZIP：269,733 bytes；SHA-256 `F2DC02FA35DDABD6A52221E7B882018C29E31BCC112FD55343AADB05CF89BF02`。
- ZIP 只包含 `code-mentor/lib/code-mentor-1.2.4.jar`；JAR 内已核对 plugin.xml、LICENSE、NOTICE、PRIVACY.md 与图标，元数据为 ID `com.codementor`、版本 `1.2.4`、兼容范围 `241` 至 `241.*`。
