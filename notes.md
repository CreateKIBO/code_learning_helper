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
