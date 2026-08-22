package com.codementor.review;

/**
 * 一条导师建议 —— 对应 AI 输出协议中的单个 suggestion（最终方案第 9 节）。
 *
 * <p>侧栏先展示证据、思考提示、最小下一步和完成标准，再按三层渐进式展示答案：
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
    private final String verificationCriteria; // 修改验证时用于判断问题是否解决
    private final String nextStep; // 学习者先亲手完成的一步，不是整段替写代码
    private final String selfCheckQuestion; // 理解或修改后的主动回忆问题

    /**
     * 私有构造器：统一走 {@link Builder}。
     * 多个参数都是 String，公开构造器等于给“传错顺序”留门；
     * 私有后 Builder 是唯一构造入口（嵌套类不受 private 限制）。
     */
    private Suggestion(String id, Category category, Certainty certainty,
                       String title, String evidence, String hint,
                       String explanation, String example, String knowledgePoint,
                       String verificationCriteria, String nextStep,
                       String selfCheckQuestion) {
        this.id = id;
        this.category = category;
        this.certainty = certainty;
        this.title = title;
        this.evidence = evidence;
        this.hint = hint;
        this.explanation = explanation;
        this.example = example;
        this.knowledgePoint = knowledgePoint;
        this.verificationCriteria = verificationCriteria;
        this.nextStep = nextStep;
        this.selfCheckQuestion = selfCheckQuestion;
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
    public String getVerificationCriteria() { return verificationCriteria; }
    public String getNextStep() { return nextStep; }
    public String getSelfCheckQuestion() { return selfCheckQuestion; }

    @Override
    public String toString() {
        return "[" + category.getLabel() + "/" + certainty + "] " + title;
    }

    /**
     * Builder —— 用于模型 JSON 解析和测试桩构造 Suggestion。
     *
     * <p>为什么需要 Builder：多个同类型参数极易在模型解析时把
     * evidence 和 hint 之类相邻参数填反，编译期查不出来。
     * Builder 让每个字段都有名字，传错一目了然。
     *
     * <p>必填项：category、certainty、title。其余有合理默认值（空串），
     * 调用方按需链式补充。build() 时做最小校验。
     */
    public static final class Builder {
        private String id = "";
        private Category category;
        private Certainty certainty;
        private String title;
        private String evidence = "";
        private String hint = "";
        private String explanation = "";
        private String example = "";
        private String knowledgePoint = "";
        private String verificationCriteria = "";
        private String nextStep = "";
        private String selfCheckQuestion = "";

        public Builder id(String id) { this.id = id; return this; }
        public Builder category(Category category) { this.category = category; return this; }
        public Builder certainty(Certainty certainty) { this.certainty = certainty; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder evidence(String evidence) { this.evidence = evidence; return this; }
        public Builder hint(String hint) { this.hint = hint; return this; }
        public Builder explanation(String explanation) { this.explanation = explanation; return this; }
        public Builder example(String example) { this.example = example; return this; }
        public Builder knowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; return this; }
        public Builder verificationCriteria(String verificationCriteria) {
            this.verificationCriteria = verificationCriteria;
            return this;
        }
        public Builder nextStep(String nextStep) { this.nextStep = nextStep; return this; }
        public Builder selfCheckQuestion(String selfCheckQuestion) {
            this.selfCheckQuestion = selfCheckQuestion;
            return this;
        }

        public Suggestion build() {
            if (category == null) {
                throw new IllegalStateException("Suggestion 必须有 category");
            }
            if (certainty == null) {
                throw new IllegalStateException("Suggestion 必须有 certainty");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalStateException("Suggestion 必须有 title");
            }
            return new Suggestion(id, category, certainty, title,
                    evidence, hint, explanation, example, knowledgePoint,
                    verificationCriteria, nextStep, selfCheckQuestion);
        }
    }
}
