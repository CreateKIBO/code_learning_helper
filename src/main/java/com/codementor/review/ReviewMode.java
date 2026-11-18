package com.codementor.review;

/** 用户发起导师请求时的学习意图。 */
public enum ReviewMode {
    REVIEW("评审我的写法"),
    EXPLAIN("解释这段代码"),
    NEXT_STEP("指导下一步编码");

    private final String label;

    ReviewMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean supportsModificationVerification() {
        return this == REVIEW;
    }
}
