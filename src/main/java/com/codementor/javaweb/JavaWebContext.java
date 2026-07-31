package com.codementor.javaweb;

import java.util.List;

/** JavaWeb 场景识别结果；只携带教学所需的少量结构化信号。 */
public final class JavaWebContext {

    private static final JavaWebContext UNKNOWN = new JavaWebContext(
            JavaWebRole.UNKNOWN,
            List.of(),
            "按普通 Java 方法评审，不强行套用 Web 分层规则。");

    private final JavaWebRole role;
    private final List<String> concerns;
    private final String reviewFocus;

    public JavaWebContext(JavaWebRole role, List<String> concerns, String reviewFocus) {
        this.role = role;
        this.concerns = List.copyOf(concerns);
        this.reviewFocus = reviewFocus;
    }

    public static JavaWebContext unknown() {
        return UNKNOWN;
    }

    public JavaWebRole getRole() {
        return role;
    }

    public List<String> getConcerns() {
        return concerns;
    }

    public String getReviewFocus() {
        return reviewFocus;
    }

    public boolean isRecognized() {
        return role != JavaWebRole.UNKNOWN;
    }
}
