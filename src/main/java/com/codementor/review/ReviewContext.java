package com.codementor.review;

/**
 * 上下文采集结果 —— 一次评审请求要从编辑器里"读懂"的全部信息。
 *
 * <p>对应最终方案第 8 节"上下文采集策略"。阶段 0 只采集最基础的两项：
 * <ul>
 *   <li>{@link #targetName}：选中代码的标识，或光标所在方法/类的签名。</li>
 *   <li>{@link #code}：实际要评审的代码文本。</li>
 * </ul>
 *
 * <p>关键设计：携带 {@link #documentStamp} 和 {@link #fingerprint}（代码指纹）。
 * 这是修正早期方案"只靠行号定位建议"硬伤的核心机制——
 * 用户继续编辑后行号会变，但响应里记录的是发起请求时的指纹，
 * 模型返回后比对，不匹配就标记 EXPIRED，绝不让旧建议盖到新代码上。
 *
 * <p>阶段 0 的指纹用简单的字符串 hashCode；阶段 1 会换更稳的 hash（如 SHA-256 截断）。
 */
public final class ReviewContext {

    /** 相对文件路径，用于日志和学习记录（方案第 8 节）。 */
    private final String filePath;
    /** 评审目标的展示名，如 "UserController#login" 或 "选中代码"。 */
    private final String targetName;
    /** 实际要评审的代码文本。 */
    private final String code;
    /** 文档版本戳：IntelliJ Document.getModificationStamp()。 */
    private final long documentStamp;
    /** 代码内容指纹：用于判断"评审期间的代码"是否还是"现在的代码"。 */
    private final String fingerprint;
    /**
     * 目标方法在文档中的起始 offset（评审光标所在方法时记录）。
     * -1 表示"选中代码"模式——此时无固定范围，文档任何改动都视为可能过期。
     */
    private final int methodStart;
    /** 目标方法在文档中的结束 offset。-1 同 methodStart 语义。 */
    private final int methodEnd;

    public ReviewContext(String filePath, String targetName, String code,
                         long documentStamp, String fingerprint,
                         int methodStart, int methodEnd) {
        this.filePath = filePath;
        this.targetName = targetName;
        this.code = code;
        this.documentStamp = documentStamp;
        this.fingerprint = fingerprint;
        this.methodStart = methodStart;
        this.methodEnd = methodEnd;
    }

    public String getFilePath() { return filePath; }
    public String getTargetName() { return targetName; }
    public String getCode() { return code; }
    public long getDocumentStamp() { return documentStamp; }
    public String getFingerprint() { return fingerprint; }
    public int getMethodStart() { return methodStart; }
    public int getMethodEnd() { return methodEnd; }

    /**
     * 判断文档某次改动[offset, offset+length)是否落在目标方法范围内。
     * 用于过期检测：只有改到目标方法内的代码，才认为建议过期；
     * 改别处不算，避免误报。
     * -1 范围（选中模式）时，任何改动都返回 true（保守处理）。
     */
    public boolean affectsMethodRange(int offset, int length) {
        if (methodStart < 0 || methodEnd < 0) {
            return true; // 选中模式：无法定位范围，保守认为可能过期
        }
        int changeEnd = offset + Math.max(length, 1);
        return offset <= methodEnd && changeEnd >= methodStart;
    }
}
