package com.codementor.review;

import java.util.List;

/**
 * 一条导师建议 —— 对应 AI 输出协议中的单个 suggestion（最终方案第 9 节）。
 *
 * <p>阶段 0 暂不接入真实模型，这个类先定义好结构，后续接入模型时直接复用。
 * 三层渐进式展示（提示在前、答案在后）：
 * <ol>
 *   <li>{@link #hint} —— 只指方向，不立即给答案。</li>
 *   <li>{@link #explanation} —— 完整讲解（默认折叠，用户点开才看）。</li>
 *   <li>{@link #example} —— 参考代码（再点一次才显示，强调不是唯一答案）。</li>
 * </ol>
 */
public final class Suggestion {

    /** 建议类别。用枚举而不是字符串，避免模型乱填导致 UI 无法分类。 */
    public enum Category {
        CORRECTNESS("正确性"),      // 明确错误：空指针、资源未关闭
        READABILITY("可读性"),      // 命名、结构
        JAVA_USAGE("Java 用法"),    // 语言特性用得不对
        DESIGN("设计"),            // 类职责、分层、OOP
        JAVAWEB("JavaWeb");        // Controller/校验/事务/REST

        private final String label;
        Category(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /** 置信类型：区分"明确错误"和"个人偏好"，防止把偏好说成错误（方案第 9 节）。 */
    public enum Certainty { HIGH, MEDIUM, LOW }

    private final String id;
    private final Category category;
    private final Certainty certainty;
    private final String title;        // 一句话标题
    private final String evidence;     // 代码证据：引用哪段代码
    private final String hint;         // 第一层：引导提示（不给答案）
    private final String explanation;  // 第二层：完整讲解（默认折叠）
    private final String example;      // 第三层：参考代码（可选，进一步折叠）
    private final String knowledgePoint; // 知识点标签，用于学习记录

    public Suggestion(String id, Category category, Certainty certainty,
                      String title, String evidence, String hint,
                      String explanation, String example, String knowledgePoint) {
        this.id = id;
        this.category = category;
        this.certainty = certainty;
        this.title = title;
        this.evidence = evidence;
        this.hint = hint;
        this.explanation = explanation;
        this.example = example;
        this.knowledgePoint = knowledgePoint;
    }

    public String getId() { return id; }
    public Category getCategory() { return category; }
    public Certainty getCertainty() { return certainty; }
    public String getTitle() { return title; }
    public String getEvidence() { return evidence; }
    public String getHint() { return hint; }
    public String getExplanation() { return explanation; }
    public String getExample() { return example; }
    public String getKnowledgePoint() { return knowledgePoint; }

    @Override
    public String toString() {
        return "[" + category.getLabel() + "/" + certainty + "] " + title;
    }
}
