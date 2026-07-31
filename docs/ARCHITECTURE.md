# CodeMentor 架构文档

> 住在 IntelliJ IDEA 里的 Java / JavaWeb 学习教练。本文档描述整体架构、模块职责、
> 核心数据流与线程模型，便于阅读和维护代码。

---

## 1. 设计目标与边界

**目标**：帮助学习者理解并修改自己的 Java 代码，形成「发现问题 → 自己修改 → 再次验证 → 复习练习」的闭环。

**刻意不做的事**（这些边界决定了整个架构）：
- ❌ 不替用户写代码 / 自动改代码（学习工具，不是 Copilot）
- ❌ 不生成整个项目 / 代写毕业设计
- ❌ 不做服务端、账号系统、数据库（个人本地工具）
- ❌ 不做行内 Inlay / 波浪线 / QuickFix（复杂度高，后期才考虑）

**核心约束**（贯穿所有模块）：
- 提示在前、答案在后；每条建议必带代码证据；每次最多 3 条建议
- 旧建议绝不覆盖新代码（过期检测 + requestId 守卫）
- 敏感信息发送前遮盖；API Key 走系统钥匙串；首次发送需用户确认
- 网络与 PSI 重活绝不阻塞 EDT

---

## 2. 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  触发层 (action / listener)                                  │
│  ReviewAction(Alt+M) · ExplainAction(Alt+Shift+M)           │
│  SaveReviewListener(可选保存触发) · LastEditTracker          │
└──────────────────────────┬──────────────────────────────────┘
                           │ 调用
┌──────────────────────────▼──────────────────────────────────┐
│  协调层 (review)  —— 每条业务流程一个 Coordinator            │
│  ReviewCoordinator    评审/解释主流程                        │
│  VerificationCoordinator  修改验证                           │
│  FollowUpCoordinator  针对单条建议追问                       │
│  ExerciseCoordinator  薄弱知识点小练习                       │
└──────────────────────────┬──────────────────────────────────┘
                           │ 读写
┌──────────────────────────▼──────────────────────────────────┐
│  状态层  ReviewSession（项目级，UI 唯一真实来源）             │
│  + ExpiryWatcher（RangeMarker 过期检测）                     │
│  + ReviewCache（指纹缓存，免重复请求）                       │
└──────────────────────────┬──────────────────────────────────┘
                           │ 经由 CodeMentorService 取实现
┌──────────────────────────▼──────────────────────────────────┐
│  模型层 (model)                                              │
│  ZhiPuModelClient  智谱 GLM（OpenAI 兼容）真实客户端          │
│  PromptBuilder  教学导向 system prompt                       │
│  JsonResponseParser  结构化解析 + 枚举降级                    │
│  PrivacyFilter  发送前遮盖                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                ┌──────────▼──────────┐
                │  智谱 GLM API        │
                └─────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  支撑层（被以上各层使用）                                     │
│  context/   PSI 上下文采集、技术标签、IDEA 诊断、模型上下文   │
│  history/   本地学习事件（JSONL）+ 统计 + Markdown 导出       │
│  javaweb/   分层角色识别 + 项目学习约定                       │
│  practice/  小练习生成                                        │
│  privacy/   文件排除策略 + 发送预览格式化                     │
│  settings/  持久化设置 + API Key 安全存储                     │
│  ui/        教练侧栏 + 建议卡片交互                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心数据流：一次评审

```
用户按 Alt+M（光标在方法内）
   │
   ▼
ReviewAction（EDT）
   ├─ 读锁内采集 PSI（ReadAction.nonBlocking，后台线程）
   │     └─ PsiContextExtractor.extract()
   │          ├─ 选中代码 优先；否则取光标所在 PsiMethod
   │          ├─ 记录方法范围（methodStart/methodEnd，供过期检测）
   │          └─ 计算代码指纹（fingerprint）
   │
   ▼ 回到 EDT
ReviewCoordinator.review()
   ├─ 文件排除检查（privacy/FileExclusionPolicy）
   ├─ 缓存命中检查（ReviewCache，指纹 + 模式 + 配置）
   │     └─ 命中：直接 succeed，不发网络请求
   ├─ 首次发送确认（CodeMentorSettings.consentedToSend）
   ├─ ReviewSession.startRequest() → 进入 REQUESTING
   ├─ 挂载 ExpiryWatcher（RangeMarker，评审期间即开始监控）
   │
   ▼ 经 CodeMentorService 取 ReviewService 实现
ZhiPuModelClient.review()（后台守护线程）
   ├─ ApiKeyStore.getApiKey()（PasswordSafe，后台线程）
   ├─ PrivacyFilter.mask()  遮盖敏感信息
   ├─ PromptBuilder 拼教学 prompt
   ├─ HttpClient POST chat/completions（30s 超时）
   ├─ JsonResponseParser.parse()
   │     ├─ 剥离 markdown 代码块包裹
   │     ├─ category/certainty 枚举降级（未知值不崩）
   │     └─ 截断到最多 3 条
   └─ 解析失败重试 1 次
   │
   ▼ 回调（后台线程）→ invokeLater → EDT
ReviewSession.succeed(requestId, suggestions)
   ├─ requestId 不匹配则丢弃（迟到响应守卫）
   ├─ SuggestionDeduplicator 分配稳定 ID（S1/S2/S3）
   ├─ LearningHistoryService.recordReview()（异步写 JSONL）
   └─ fireChanged() → UI 刷新
   │
   ▼
MentorToolWindowPanel 渲染卡片
```

---

## 4. 关键设计决策

### 4.1 为什么用 requestId 守卫？

快速连按 Alt+M 时，先发的请求更慢，后到的响应不能覆盖新会话。`ReviewSession` 的 `succeed/fail/expire` 都带 requestId，**只接受当前请求**的回调。这个守卫贯穿评审、追问、验证、练习四条流程。

### 4.2 为什么过期检测用 RangeMarker？

早期版本用「发起时记录的静态 offset」判断代码是否变化。但用户在**方法外**编辑后，目标方法的 offset 会整体漂移——既会误报（改别的方法也判过期），也会漏报。`RangeMarker` 是 IntelliJ 的活体范围标记，随编辑自动平移/伸缩，天然解决这个问题。

更进一步：watcher 在**请求发起时**就挂载（不是成功后），这样评审进行中（真实模型要数秒）的编辑也能被捕获。

### 4.3 为什么有协调层？

如果把所有流程逻辑塞进 `ReviewAction`，会变成上千行的巨型类。每条业务流程（评审、验证、追问、练习）独立成 Coordinator，各自管理自己的状态（pending 请求、过期监控、取消），`ReviewAction` 只负责触发。这让每条流程都能独立演进和测试。

### 4.4 为什么模型客户端实现统一接口？

`ReviewService` 是统一接口，`ZhiPuModelClient` 是一个实现。换模型供应商（通义/DeepSeek 等）只需新增一个实现类并在 `CodeMentorService` 里切换，业务代码零改动。`FakeReviewService` 保留用于离线演示和自动测试桩。

### 4.5 为什么学习事件用追加式 JSONL？

不做数据库（个人本地工具的边界约束）。每条学习事件追加到本地 `.jsonl` 文件，统计结果由事件实时计算，不维护容易失真的计数。写盘走单线程执行器保证顺序，关闭时优雅 `shutdown()` + `awaitTermination` 不丢事件。

---

## 5. 线程模型（最重要的 correctness 约束）

| 操作 | 必须的线程 | 原因 |
|------|-----------|------|
| PSI 读取（PsiMethod / PsiFile） | 读锁内，且 `ReadAction.nonBlocking` 放后台 | PSI 访问要读锁；放 EDT 会触发「Slow operations on EDT」 |
| 网络请求（HttpClient） | 后台守护线程 | 阻塞操作，绝不阻塞 EDT |
| `PasswordSafe` 读写 | 后台线程 | 阻塞操作，平台明确禁止 EDT |
| `ReviewSession` 状态变更 | EDT | 监听器（UI）约定在 EDT 回调 |
| UI 更新 | EDT（`invokeLater`） | Swing 单线程模型 |

**统一模式**：后台线程做完工作 → `invokeLater` 回 EDT → 更新 session / UI。

---

## 6. 模块职责速查

| 包 | 职责 | 关键类 |
|----|------|--------|
| `action` | 用户触发入口 | `ReviewAction`, `ExplainAction`, `SaveReviewListener`, `LastEditTracker` |
| `review` | 评审流程协调与状态 | `ReviewCoordinator`, `VerificationCoordinator`, `FollowUpCoordinator`, `ExerciseCoordinator`, `ReviewSession`, `ReviewCache`, `ExpiryWatcher` |
| `model` | 真实模型客户端 | `ZhiPuModelClient`, `PromptBuilder`, `JsonResponseParser`, `PrivacyFilter` |
| `context` | PSI 上下文采集 | `PsiContextExtractor`, `ModelContextBuilder`, `TechnologyTagDetector`, `IdeaDiagnosticCollector` |
| `history` | 本地学习记录 | `LearningHistoryService`, `LearningEventStore`, `LearningEvent`, `LearningSummary` |
| `javaweb` | Web 层专项 | `JavaWebContextAnalyzer`, `ProjectLearningRulesService` |
| `practice` | 小练习 | `ExerciseCoordinator`, `Exercise` |
| `privacy` | 隐私控制 | `FileExclusionPolicy`, `SendPreviewFormatter` |
| `settings` | 配置与密钥 | `CodeMentorSettings`, `CodeMentorConfigurable`, `ApiKeyStore` |
| `ui` | 教练侧栏 | `MentorToolWindowPanel`, `SuggestionCardPanel`, `MentorToolWindowFactory` |

---

## 7. 配置与持久化

| 数据 | 存储方式 | 位置 |
|------|---------|------|
| API 地址、模型、教学级别、开关、排除模式 | `PersistentStateComponent` → XML | `idea/system/codementor.xml`（应用级） |
| **API Key** | `PasswordSafe`（系统钥匙串） | Windows DPAPI / macOS Keychain，不明文落盘 |
| 学习事件 | JSONL 追加 | `idea/system/codementor/learning-events.jsonl` |
| 项目学习约定 | 用户自建 | 项目根 `.codementor/rules.md` |

---

## 8. 测试策略

- **离线回归语料**：`test/resources/regression/java-learning-corpus.json`，20 条 Java/JavaWeb 代码片段，验证隐私过滤、提示协议、分类规则——**不调用真实模型**，不消耗 API 额度。
- **假服务桩**：`FakeReviewService` 模拟评审/验证/追问响应，用于 UI 和状态机测试。
- **目标指标**：过期建议错误显示次数 = 0；结构化响应一次重试后解析成功率 ≥ 98%；每次评审最多 3 条建议。

---

## 9. 演进路线

项目按 7 个阶段逐步构建（见 `最终方案.md` 第 13 节）：

- **阶段 0** 技术探针：PSI 采集 + 假响应 + 侧栏
- **阶段 1** 可用 MVP：真实模型 + 设置页 + JSON 解析 + 隐私遮盖
- **阶段 2** 学习闭环：修改验证 + 学习记录 + Markdown 导出
- **阶段 3** 体验优化：保存触发 + 请求取消 + 指纹缓存 + 追问 + 复习提醒
- **阶段 4** JavaWeb 专项：分层角色识别 + 项目学习约定 + 小练习
- **阶段 5** 双模式与精确上下文：评审/解释双模式 + 技术标签 + IDEA 诊断摘要
- **阶段 6** 隐私控制与学习成效：文件排除 + 发送预览 + 本地统计 + 回归语料

> 所有阶段都**不**增加服务端、数据库、自动改代码、命令执行、Inlay、Inspection 或 QuickFix。
