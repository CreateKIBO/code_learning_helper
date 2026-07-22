# CodeMentor 个人版 — 阶段 0：技术探针（v0.2 体验优化版）

> 这是一个 IntelliJ IDEA 插件，目标是成为你学 Java / JavaWeb 时的"住在 IDE 里的代码教练"。
> 当前是**阶段 0**：核心链路（读懂代码 → 触发评审 → 侧栏展示）已跑通，用**假响应**（非真实模型）。
> **v0.2 新增**：自动过期检测、重新评审按钮、空状态美化、过期横幅、失败状态演示。

---

## 1. 阶段 0 要验证什么

按《最终方案.md》第 13 节和第 16 节，阶段 0 的验收标准只有一条：

> **在测试 IDE 中按一次 Alt+M，侧栏能显示当前方法名 + 一条不会错位的导师提示。**

为此本阶段实现了：

| 能力 | 文件 | 说明 |
|------|------|------|
| PSI 上下文采集 | `context/PsiContextExtractor.java` | 选中代码优先；未选中取光标所在 `PsiMethod` |
| 评审状态机 | `review/ReviewSession.java` | UI 的唯一状态来源，支持 IDLE/REQUESTING/SUCCESS/FAILED/EXPIRED |
| 代码指纹 + 文档 stamp | `review/ReviewContext.java` | 修正"只靠行号定位"的硬伤（行号会漂移） |
| 假模型服务 | `review/FakeReviewService.java` | 硬编码 3 条 JavaWeb 教学建议，先跑通 UI |
| 触发动作 | `action/ReviewAction.java` | Alt+M / 右键菜单，PSI 读锁采集 + EDT 更新 |
| 教练侧栏 | `ui/MentorToolWindowFactory.java` + `MentorToolWindowPanel.java` | 订阅会话，按状态渲染卡片，点击展开讲解 |

**还没做（按方案规划，后续阶段补）**：
- 真实大模型接入（阶段 1）
- 修改验证 / 错题本 / 学习记录（阶段 2）
- 行内 Inlay / 波浪线 / QuickFix（学习成本高，先验证主链路）

### v0.2 新增的体验功能（建议重点测试）

1. **自动过期检测** ⭐ 核心安全机制
   - 评审成功后，在**被评审的方法内**改一个字符 → 侧栏顶部立刻弹出橙色"代码已修改，建议可能过期"横幅
   - 改**别的方法**不会误报（只追踪目标方法范围）
2. **重新评审按钮**
   - 评审成功/过期/失败后，侧栏右上角出现「重新评审」按钮，点击等同 Alt+M
3. **空状态美化**
   - 刚打开侧栏时，显示居中图标 + "按 Alt+M 评审当前方法"引导
4. **失败状态演示**
   - 假服务每 5 次请求会故意失败 1 次，你能看到红色"评审失败"+ 错误原因 + 重试引导
   - 多按几次 Alt+M 就能看到（第 5、10、15…次）

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

构建产物：`build/distributions/CodeMentor-0.1.0-SNAPSHOT.zip`（可手动安装到真实 IDEA）。

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

**Q4：假建议看起来跟我的代码无关？**
A：正常——阶段 0 是**假服务**，返回的是固定内容，只是用来验证 UI 和状态机。阶段 1 接真实模型后，建议才会针对你的真实代码。

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
    │   │   ├── FakeReviewService.java        # 阶段 0 假服务
    │   │   ├── ReviewSession.java            # 状态机（UI 唯一来源）
    │   │   ├── ReviewContext.java            # 上下文 + 指纹
    │   │   ├── ReviewStatus.java             # 状态枚举
    │   │   └── Suggestion.java               # 建议数据模型
    │   └── ui/
    │       ├── MentorToolWindowFactory.java  # 侧栏工厂
    │       └── MentorToolWindowPanel.java    # 侧栏内容（订阅会话）
    └── resources/META-INF/plugin.xml         # 注册 Action / ToolWindow
```

---

## 7. 下一步（阶段 1）

阶段 0 跑通后进入阶段 1（见《最终方案.md》第 13 节）：
1. 上下文采集补充 imports / 类签名 / 字段 / IDEA 诊断摘要
2. 接入真实大模型（智谱 GLM / 通义 / DeepSeek 二选一，你来定）
3. 实现结构化 JSON 解析 + 失败重试 + 降级
4. 加上敏感信息遮盖和"发送前预览"
5. 用 `PasswordSafe` 存 API Key

阶段 0 → 阶段 1 的切换点：只需把 `ReviewAction` 里的
```java
private final ReviewService reviewService = new FakeReviewService();
```
换成真实适配器，业务代码一行不用动——这就是"统一接口"的价值。
