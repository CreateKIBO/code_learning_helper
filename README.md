# CodeMentor 个人版 — Java / JavaWeb 学习教练（v0.6.0）

> 这是一个 IntelliJ IDEA 插件，目标是成为你学 Java / JavaWeb 时的"住在 IDE 里的代码教练"。
> 阶段 0/1 已完成：插件能够采集 PSI 上下文、调用真实模型、校验结构化响应并安全展示建议。
> 阶段 2 已加入“自己修改 → 验证结果 → 反馈 → 本地学习记录 → Markdown 导出”的学习闭环。
> 阶段 3 已加入可选保存触发、请求取消、指纹缓存、建议去重、追问和复习提醒。
> 阶段 4 已加入 JavaWeb 分层角色识别、项目学习约定和薄弱知识点小练习。
> 阶段 5 已加入“评审 / 解释”双模式、相关字段裁剪、技术标签与 IDEA 诊断摘要。
> 阶段 6 已加入发送前隐私控制、稳定学习事件关联、本地学习统计和 20 条离线回归语料。

---

## 1. 当前能力

当前验收目标：

> **帮助你理解并修改自己的 Java 代码，形成“发现问题—自己修改—再次验证—复习练习”的闭环。**

为此本阶段实现了：

| 能力 | 文件 | 说明 |
|------|------|------|
| PSI 上下文采集 | `context/PsiContextExtractor.java` | 选中代码优先；未选中取光标所在 `PsiMethod` |
| 评审状态机 | `review/ReviewSession.java` | UI 的唯一状态来源，支持 IDLE/REQUESTING/SUCCESS/FAILED/EXPIRED |
| 代码指纹 + 文档 stamp | `review/ReviewContext.java` | 修正"只靠行号定位"的硬伤（行号会漂移） |
| 真实模型 | `model/ZhiPuModelClient.java` | OpenAI 兼容请求、结构化解析和一次解析重试 |
| 触发动作 | `action/ReviewAction.java` | Alt+M / 右键菜单，PSI 读锁采集 + EDT 更新 |
| 修改验证 | `review/VerificationCoordinator.java` | 重新采集当前方法，验证单条建议是否解决 |
| 学习记录 | `history/` | JSONL 追加事件、本地统计、Markdown 导出 |
| 教练侧栏 | `ui/MentorToolWindowFactory.java` + `MentorToolWindowPanel.java` | 建议、反馈、验证状态和复习导出 |
| JavaWeb 场景识别 | `javaweb/JavaWebContextAnalyzer.java` | 识别 Controller / Service / Repository / DTO 等角色与专项关注点 |
| 项目学习约定 | `javaweb/ProjectLearningRulesService.java` | 后台读取并缓存 `.codementor/rules.md`，最多 8000 字符 |
| 小练习 | `practice/` | 根据重复知识点生成一道 10~20 分钟练习，不发送项目源码、不生成完整项目 |
| 双模式学习 | `action/ReviewAction.java` + `ExplainAction.java` | `Alt+M` 评审写法，`Alt+Shift+M` 解释代码 |
| 精确模型上下文 | `context/ModelContextBuilder.java` | 目标代码优先，补充相关字段、技术标签和有限 IDEA 诊断摘要 |
| 文件排除与发送预览 | `privacy/` | 内置/自定义排除模式；模型调用前停止；预览内容再次脱敏 |
| 本地学习统计 | `history/LearningEventStore.java` | 按稳定评审 ID 聚合验证、讲解、反馈与薄弱知识点 |
| 离线回归基线 | `test/resources/regression/java-learning-corpus.json` | 20 条 Java/JavaWeb 语料，不调用真实模型 |

**有意不做**：整项目生成、毕业设计代写、服务端账号系统、行内 Inlay / 波浪线 / QuickFix。

### 建议重点测试

1. **自动过期检测** ⭐ 核心安全机制
   - 评审成功后，在**被评审的方法内**改一个字符 → 侧栏顶部立刻弹出橙色"代码已修改，建议可能过期"横幅
   - 改**别的方法**不会误报（只追踪目标方法范围）
2. **重新评审按钮**
   - 评审成功/过期/失败后，侧栏右上角出现「重新评审」按钮，点击等同 Alt+M
3. **空状态美化**
   - 刚打开侧栏时，显示居中图标 + "按 Alt+M 评审当前方法"引导
4. **修改验证与学习记录**
   - 修改代码后，过期横幅下仍保留原建议
   - 点击“验证我的修改”，查看已解决/部分解决/仍未解决
   - 对建议标记“有帮助/没帮助/误报”，再点“导出复习”生成 Markdown
5. **阶段 3 日常体验**
   - 在 Settings → Tools → CodeMentor 中可选开启“保存时评审”，默认关闭
   - 保存触发只评审最后实际编辑的方法；无法可靠定位时会跳过，不根据已移走的光标猜测
   - 编辑评审目标时会取消尚未完成的旧请求
   - 相同代码指纹和模型配置直接复用内存缓存
   - 可针对单条建议追问；知识点累计出现 3 次后显示复习提醒
6. **阶段 4 JavaWeb 专项**
   - Controller / Service / Repository / DTO 会显示对应角色和评审重点
   - 项目根目录存在 `.codementor/rules.md` 时，评审会参考其中的个人学习约定
   - 点击“生成小练习”，会优先选择近期重复出现的知识点；无重复记录时使用当前建议
7. **阶段 5 双模式与精确上下文**
   - `Alt+M` 返回需要自己修改和验证的建议
   - `Alt+Shift+M` 返回理解要点，不计入重复错误统计，也不显示“验证我的修改”
   - 侧栏显示识别到的 JavaWeb 角色和技术标签
   - 模型上下文只包含目标实际引用的类字段；IDEA 错误/警告只作为解释依据
8. **阶段 6 隐私控制与学习成效**
   - Settings → Tools → CodeMentor 可配置文件排除模式和“每次手动发送前预览”
   - `.env`、私钥、证书、构建目录等内置敏感路径在上下文采集和模型调用前停止
   - 侧栏“发送预览”显示当前请求的脱敏内容；“学习统计”只汇总本机 JSONL 事件
   - 自动测试覆盖 20 条 Java/JavaWeb 回归语料，全程不消耗 API 额度

### 1.1 健壮性保护

1. **迟到响应守卫** ⭐
   - 每次请求有唯一 requestId，`ReviewSession` 只接受当前请求的成功/失败/过期回调
   - 快速连按 Alt+M 时，慢的旧响应回来会被直接丢弃，不会覆盖新会话
2. **过期检测改用 RangeMarker**
   - 旧实现用静态 offset 判断，用户在**方法外**编辑后 offset 漂移 → 误报 + 漏报
   - RangeMarker 随编辑自动平移/伸缩，改别的方法不再误判；纯插入/纯删除判定也补齐了
3. **评审期间即开始监控**
   - watcher 从"成功后挂载"提前到"请求发起时挂载"：评审进行中改代码会立刻标过期，
     迟到的响应再回来也会被守卫丢弃——旧结果永远盖不到新代码上
4. **监听器泄漏修复**
   - 侧栏面板销毁时退订项目级 `ReviewSession`，项目关闭不再泄漏
5. **小动作**
   - `update()` 改看 `VirtualFile` 扩展名，BGT 线程零 PSI 接触
   - 文件路径真正改为相对项目根（不再把本机绝对路径写进日志）
   - 清理未使用 import、误导性注释、占位 vendor url

---

## 2. 环境要求

| 项 | 要求 | 你的情况 |
|----|------|----------|
| IntelliJ IDEA | **2024.1.1**（目标平台，build 241，JBR 17） | ✅ |
| JDK | **Java 17**（与 IDEA 2024.1 的 JBR 一致） | ✅ 已装 17.0.8 |
| Gradle | 不需要单独装，用项目里的 `gradlew`（指向 Gradle 9.0） | ✅ |
| 网络 | 首次构建需下载 Gradle 发行版和 IntelliJ Platform 依赖 | 需可访问 services.gradle.org |

> 如果首次下载 Gradle 很慢，可在 `gradle/wrapper/gradle-wrapper.properties` 把 `distributionUrl` 换成腾讯/阿里镜像（见本文件第 6 节）。

---

## 3. 在 IDEA 里运行插件（推荐方式）

最简单、最适合学习的方式：

1. **用 IntelliJ IDEA 2024.1.1 打开本项目**
   - File → Open → 选 `G:\code_learning_helper`
   - IDEA 会自动识别为 Gradle 项目，开始下载 Gradle 9.0 + 依赖（**首次可能 5~15 分钟**，取决于网速）
2. **等右下角进度条走完**（"Indexing..." / "Gradle sync"）
3. **运行沙箱 IDE**
   - 右侧 Gradle 面板 → `Tasks → intellij platform → runIde`，双击
   - 或者：右上角"Run Configuration"下拉 → 选 `runIde` → 点绿色三角 ▶
4. **会启动一个新的 IDEA 窗口**（这就是"沙箱 IDE"，插件装在里面）
5. **在沙箱 IDE 里**：
   - 随便打开一个 Java 文件，光标放到某个方法里
   - 按 **Alt+M**，或右键 → "请导师评审(M)"
   - 右侧应该弹出 **CodeMentor** 侧栏，显示"评审目标：类名#方法名"，状态从"评审中…"变成"评审完成"，列出 3 条建议卡片
   - 点卡片标题可展开"为什么 / 参考示例 / 知识点"

---

## 4. 命令行构建（不打开 IDEA 也能验证）

```bash
# 在项目根目录（Git Bash 或 cmd）
./gradlew.bat build          # 完整构建 + 打包插件 zip
./gradlew.bat compileJava    # 只编译，最快验证代码对不对
./gradlew.bat runIde         # 启动沙箱 IDE
```

构建产物：`build/distributions/code-mentor-0.6.0-SNAPSHOT.zip`（可手动安装到真实 IDEA）。

---

## 5. 常见问题

**Q1：首次 `runIde` 卡在下载 Gradle / 依赖？**
A：国内网络访问 `services.gradle.org` 可能很慢。把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 改为镜像，例如：
```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.0.0-bin.zip
```
依赖仓库（`build.gradle.kts` 里的 `mavenCentral()`）也可加阿里云镜像：
```kotlin
maven { url = uri("https://maven.aliyun.com/repository/public") }
```

**Q2：报错 "Plugin can't be loaded: requires Java 21"？**
A：不会出现。本项目 `pluginSinceBuild=241`、目标 IDE 2024.1 用 JBR 17。如果你看到这个错，说明沙箱 IDE 起错了版本，检查 `build.gradle.kts` 里 `intellijIdeaCommunity("2024.1.1")` 是否正确。

**Q3：按 Alt+M 没反应？**
A：先确认——
1. 当前打开的是 `.java` 文件（非 `.txt`/`.md`）
2. 光标在某个方法**内部**，或者**选中**了一段代码
3. 右键编辑器，菜单底部能看到 "请导师评审(M)"
4. 看 IDEA 右下角 CodeMentor 侧栏图标，点开看是否有错误提示

**Q4：修改后为什么建议显示“已过期”？**
A：这是安全保护。原建议仍会保留供你点击“验证我的修改”，但插件不会把旧建议冒充成对新代码的实时结论。

---

## 6. 项目结构

```
code_learning_helper/
├── build.gradle.kts              # 构建：IntelliJ Platform Gradle Plugin 2.x
├── settings.gradle.kts
├── gradle.properties             # 目标 IDE 版本、插件兼容范围
├── gradlew / gradlew.bat         # Gradle Wrapper（指向 Gradle 9.0）
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── src/main/
    ├── java/com/codementor/
    │   ├── action/ReviewAction.java          # Alt+M 触发评审
    │   ├── context/PsiContextExtractor.java  # PSI 读取选中/当前方法
    │   ├── review/
    │   │   ├── ReviewService.java            # 统一模型接口
    │   │   ├── FakeReviewService.java        # 离线演示 / 自动测试桩
    │   │   ├── ReviewSession.java            # 状态机（UI 唯一来源）
    │   │   ├── ReviewContext.java            # 上下文 + 指纹
    │   │   ├── ReviewStatus.java             # 状态枚举
    │   │   ├── Suggestion.java               # 建议数据模型
    │   │   └── VerificationCoordinator.java  # 修改验证流程
    │   ├── history/                           # JSONL 学习事件 + Markdown 导出
    │   ├── javaweb/                           # 分层角色识别 + 项目学习约定
    │   ├── privacy/                           # 文件排除、敏感信息遮盖、发送预览
    │   ├── practice/                          # 薄弱知识点小练习
    │   └── ui/
    │       ├── MentorToolWindowFactory.java  # 侧栏工厂
    │       ├── MentorToolWindowPanel.java    # 侧栏状态与整体布局
    │       └── SuggestionCardPanel.java      # 单条建议交互
    └── resources/META-INF/plugin.xml         # 注册 Action / ToolWindow
```

---

## 7. 可选的项目学习约定

在你正在学习的项目根目录创建 `.codementor/rules.md`，例如：

```markdown
# 我的学习约定
- Controller 只负责 HTTP 输入输出与调用 Service
- 优先提示我理解事务传播，不要直接给最终答案
- REST 接口错误统一由全局异常处理器转换
```

文件内容会和当前评审上下文一起发送给已配置的模型服务，请不要在其中写入密钥。
CodeMentor 将它作为编码与学习约定，不能用它覆盖系统提示或要求执行代码。

---

## 8. 隐私排除与发送预览

在 Settings → Tools → CodeMentor 中可逐行或用分号填写自定义 glob，例如：

```text
**/internal/**
*Credential.java
src/test/fixtures/secrets/**
```

规则忽略大小写，支持 `*`、`**`、`?`。内置规则始终排除 `.env`、常见私钥/证书、
`.git`、`.idea`、`build`、`target`、`out` 和 `node_modules`。命中后不会采集 PSI、
读取项目学习约定或调用模型。首次发送仍需确认，也可开启“每次手动发送前预览”；
侧栏中的“发送预览”可以随时检查最近一次请求的脱敏内容。
