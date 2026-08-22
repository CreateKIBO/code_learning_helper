# Task Plan: CodeMentor 交互与智能辅助升级

## Goal
在现有 1.1.0 稳定基线上完成一轮面向 Java 学习者的交互、反馈与 AI 教学体验优化，升级版本并重新生成通过 JetBrains 验证的 IDEA 安装包。

## Phases
- [x] Phase 1: 建立计划并确认现有工作区基线
- [x] Phase 2: 审计工具窗口、设置、提示词、状态流与测试缺口
- [x] Phase 3: 设计并实现交互与智能辅助升级
- [x] Phase 4: 补齐测试、可读性/DRY 复核与文档版本更新
- [x] Phase 5: 全量测试、Plugin Verifier、产物内容与校验值验证

## Key Questions
1. 哪些操作目前缺少明确的下一步、进度反馈或错误恢复入口？
2. 如何让 AI 建议遵循“证据 → 原因 → 最小修改 → 自测”而不是直接代写？
3. 如何在不扩大隐私发送范围的前提下增加上下文感知与学习连续性？
4. 新交互能否保持键盘可用、EDT 安全、取消可靠并兼容 IDEA 2024.1.x？

## Decisions Made
- 以 1.1.0 为安全基线，所有新增体验继续复用现有授权、脱敏、请求守卫与协调器。
- 本轮版本按向后兼容的功能升级推进为 1.2.0。
- 视觉遵循 IntelliJ 原生组件和主题，不引入网页式自绘设计或额外 UI 框架。
- AI 输出保持教学导向，不新增自动改写用户代码的能力。
- 模型输出新增“整体总结、最小下一步、自测问题”，并用本地 evidence 原文校验抑制无依据建议。
- Tool Window 保持 IntelliJ 原生主题；高频动作前置，低频统计/隐私/导出收纳到“更多”菜单。
- UI 展开状态和学习统计的“已查看”状态分开保存；新评审清空，过期结果保留。

## Errors Encountered
- UI/UX 与规划技能最初按错误的目录前缀读取；已按技能根目录重新定位并完整读取。
- UI/UX 检索脚本首次执行时系统 PATH 中没有 `python`；改用工作区捆绑的 Python 运行时，不修改系统环境。
- 追问提示词补丁首次把不同文件的上下文合并到一个 `Update File`，`apply_patch` 校验后整组拒绝；已拆分为独立文件补丁，未留下部分修改。
- 首轮 `compileJava` 发现 ReviewCache 的 LRU 泛型仍引用旧结果类型，且工具窗口漏导入 `JBTextArea`；已按编译器定位修正，未涉及行为回退。
- 一次组合 `rg` 审计表达式括号未闭合；已拆成多个 `-e` 模式重新执行，结果仅命中预期测试线程、练习 TODO 和新协议调用。
- 审计修复后的首轮 94 项回归中，重载方法测试对 PSI 类型展示名预期为 `java.lang.String`，实际平台稳定返回 `String`；生产身份使用独立 PSI 序号不受影响，已修正测试期望。
- 一组跨文档说明的大补丁因 README 上下文已变化而整组拒绝，另一次提示词补丁也因精确行不匹配被拒绝；均拆分后重新应用，没有覆盖已有修改。
- 新增协议测试首次暴露两处预期问题：静态执行结论正则漏掉“测试全部通过”的语序，IDE fixture 直接改文档缺少 write command；修正后聚焦回归通过。
- 最终身份审计发现三参数 `createRangeMarker` 的布尔值实际是 `surviveOnExternalChange`，不是“删除后存活”；已改用安全的两参数重载，并加入整篇文档替换后锚点失效测试。
- 最终状态审计发现验证完成后再次编辑仍显示旧“已解决”；ExpiryWatcher 现会持续轻量监听并清除已完成的旧版本验证结论，`VERIFYING` 仍交由验证协调器取消。
- 产物核验脚本首次猜测了不存在的 `build/patchedPluginXmlFiles/plugin.xml`，随后读取 Gradle 实际输出 `build/tmp/patchPluginXml/plugin.xml`；一次 PowerShell XML 属性访问也因连字符语法失败，改用引号属性名后成功核对内嵌元数据。
- Plugin Verifier 读取 JetBrains 在线已记录 API 变更页面时遇到本机 JBR PKIX 证书链告警；实际二进制检查未中断，最终 verdict 为 `Compatible`，Gradle 发布任务成功。

## Status
**Complete** - 106 项测试、项目/结构检查、IDEA 2024.1.1 Plugin Verifier、ZIP 内容与 SHA-256 均已验证，1.2.0 安装包已生成。
