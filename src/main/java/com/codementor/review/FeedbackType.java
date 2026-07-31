package com.codementor.review;

/** 用户对一条导师建议的反馈。 */
public enum FeedbackType {
    HELPFUL("有帮助"),
    NOT_HELPFUL("没帮助"),
    FALSE_POSITIVE("误报");

    private final String label;

    FeedbackType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
