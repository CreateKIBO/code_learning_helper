package com.codementor.review;

import com.codementor.javaweb.JavaWebContext;

import java.util.List;

/**
 * 上下文采集结果 —— 一次评审请求要从编辑器里"读懂"的全部信息。
 *
 * <p>对应最终方案第 8 节"上下文采集策略"，主要包含：
 * <ul>
 *   <li>{@link #targetName}：选中代码的标识，或光标所在方法/类的签名。</li>
 *   <li>{@link #code}：实际要评审的代码文本。</li>
 *   <li>{@link #modelContext}：经过长度限制的包、导入、类型摘要和目标代码。</li>
 *   <li>{@link #javaWebContext} 与 {@link #projectRules}：专项教学上下文。</li>
 * </ul>
 *
 * <p>关键设计：携带 {@link #documentStamp} 和 {@link #fingerprint}（代码指纹）。
 * 这是修正早期方案"只靠行号定位建议"硬伤的核心机制——
 * 用户继续编辑后行号会变，但响应里记录的是发起请求时的指纹，
 * 模型返回后比对，不匹配就标记 EXPIRED，绝不让旧建议盖到新代码上。
 */
public final class ReviewContext {

    private static final int MAX_STORED_CODE_CHARS = 16_000;
    private static final String STORED_CODE_TRUNCATION_MARKER =
            "\n/* CodeMentor: target middle omitted */\n";

    /** 相对文件路径，用于日志和学习记录（方案第 8 节）。 */
    private final String filePath;
    /** 文件是否位于当前项目根目录内；项目外文件禁止发送。 */
    private final boolean insideProject;
    /** 评审目标的展示名，如 "UserController#login(LoginDto)" 或 "选中代码"。 */
    private final String targetName;
    /** 用于验证同一 PSI 方法的内部身份；与可修改的方法签名分离。 */
    private final String targetIdentity;
    /** 实际要评审的代码文本。 */
    private final String code;
    /** 发送给模型的有界上下文：包/imports/类摘要/字段/目标代码。 */
    private final String modelContext;
    /** modelContext 是否因字符预算被截断。 */
    private final boolean truncated;
    /** 文档版本戳：IntelliJ Document.getModificationStamp()。 */
    private final long documentStamp;
    /** 代码内容指纹：用于判断"评审期间的代码"是否还是"现在的代码"。 */
    private final String fingerprint;
    /**
     * 目标方法在文档中的起始 offset（评审光标所在方法时记录）。
     * 仅供 {@link ExpiryWatcher} 创建 RangeMarker 用——RangeMarker 会随编辑自动平移，
     * 不要直接拿这个静态值做范围判断（编辑后会漂移）。
     * -1 表示"选中代码"模式——此时无固定范围，文档任何改动都视为可能过期。
     */
    private final int methodStart;
    /** 目标方法在文档中的结束 offset。语义同 {@link #methodStart}。 */
    private final int methodEnd;
    /** 当前类型的 JavaWeb 分层角色和专项关注点。 */
    private final JavaWebContext javaWebContext;
    /** 项目根目录 .codementor/rules.md 中的个人学习约定。 */
    private final String projectRules;
    /** 评审改进或解释代码。 */
    private final ReviewMode reviewMode;
    /** 根据 imports / 注解识别的技术标签。 */
    private final List<String> technologyTags;
    /** 当前目标范围内 IDEA 已产生的错误/警告摘要。 */
    private final List<String> diagnostics;

    public ReviewContext(String filePath, String targetName, String code,
                         String modelContext, boolean truncated,
                         long documentStamp, String fingerprint,
                         int methodStart, int methodEnd) {
        this(filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                JavaWebContext.unknown(), "",
                ReviewMode.REVIEW, List.of(), List.of());
    }

    public ReviewContext(String filePath, String targetName, String code,
                         String modelContext, boolean truncated,
                         long documentStamp, String fingerprint,
                         int methodStart, int methodEnd,
                         JavaWebContext javaWebContext, String projectRules) {
        this(filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, projectRules,
                ReviewMode.REVIEW, List.of(), List.of());
    }

    public ReviewContext(String filePath, String targetName, String code,
                         String modelContext, boolean truncated,
                         long documentStamp, String fingerprint,
                         int methodStart, int methodEnd,
                         JavaWebContext javaWebContext, String projectRules,
                         ReviewMode reviewMode,
                         List<String> technologyTags,
                         List<String> diagnostics) {
        this(filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, projectRules, reviewMode,
                technologyTags, diagnostics, true);
    }

    public ReviewContext(String filePath, String targetName, String code,
                         String modelContext, boolean truncated,
                         long documentStamp, String fingerprint,
                         int methodStart, int methodEnd,
                         JavaWebContext javaWebContext, String projectRules,
                         ReviewMode reviewMode,
                         List<String> technologyTags,
                         List<String> diagnostics,
                         boolean insideProject) {
        this(filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, projectRules, reviewMode,
                technologyTags, diagnostics, insideProject, targetName);
    }

    private ReviewContext(String filePath, String targetName, String code,
                          String modelContext, boolean truncated,
                          long documentStamp, String fingerprint,
                          int methodStart, int methodEnd,
                          JavaWebContext javaWebContext, String projectRules,
                          ReviewMode reviewMode,
                          List<String> technologyTags,
                          List<String> diagnostics,
                          boolean insideProject,
                          String targetIdentity) {
        this.filePath = filePath;
        this.insideProject = insideProject;
        this.targetName = targetName;
        this.targetIdentity = targetIdentity == null ? "" : targetIdentity;
        this.code = boundedCode(code);
        this.modelContext = modelContext;
        this.truncated = truncated;
        this.documentStamp = documentStamp;
        this.fingerprint = fingerprint;
        this.methodStart = methodStart;
        this.methodEnd = methodEnd;
        this.javaWebContext = javaWebContext == null
                ? JavaWebContext.unknown()
                : javaWebContext;
        this.projectRules = projectRules == null ? "" : projectRules;
        this.reviewMode = reviewMode == null ? ReviewMode.REVIEW : reviewMode;
        this.technologyTags = technologyTags == null
                ? List.of()
                : List.copyOf(technologyTags);
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public ReviewContext withProjectRules(String rules) {
        return new ReviewContext(
                filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, rules, reviewMode, technologyTags, diagnostics,
                insideProject, targetIdentity);
    }

    public ReviewContext withReviewMode(ReviewMode mode) {
        return new ReviewContext(
                filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, projectRules, mode, technologyTags, diagnostics,
                insideProject, targetIdentity);
    }

    public ReviewContext withTargetIdentity(String identity) {
        return new ReviewContext(
                filePath, targetName, code, modelContext, truncated,
                documentStamp, fingerprint, methodStart, methodEnd,
                javaWebContext, projectRules, reviewMode, technologyTags, diagnostics,
                insideProject, identity);
    }

    public String getFilePath() { return filePath; }
    public boolean isInsideProject() { return insideProject; }
    public String getTargetName() { return targetName; }
    public String getTargetIdentity() { return targetIdentity; }
    public String getCode() { return code; }
    public String getModelContext() { return modelContext; }
    public boolean isTruncated() { return truncated; }
    public long getDocumentStamp() { return documentStamp; }
    public String getFingerprint() { return fingerprint; }
    public int getMethodStart() { return methodStart; }
    public int getMethodEnd() { return methodEnd; }
    public JavaWebContext getJavaWebContext() { return javaWebContext; }
    public String getProjectRules() { return projectRules; }
    public ReviewMode getReviewMode() { return reviewMode; }
    public List<String> getTechnologyTags() { return technologyTags; }
    public List<String> getDiagnostics() { return diagnostics; }

    private static String boundedCode(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_STORED_CODE_CHARS) return value;
        int budget = MAX_STORED_CODE_CHARS - STORED_CODE_TRUNCATION_MARKER.length();
        int headBudget = budget * 2 / 3;
        int tailBudget = budget - headBudget;
        return value.substring(0, headBudget)
                + STORED_CODE_TRUNCATION_MARKER
                + value.substring(value.length() - tailBudget);
    }
}
