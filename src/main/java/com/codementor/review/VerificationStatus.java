package com.codementor.review;

/** 一条建议的修改验证状态。 */
public enum VerificationStatus {
    IDLE("尚未验证"),
    VERIFYING("验证中…"),
    RESOLVED("已解决"),
    PARTIALLY_RESOLVED("部分解决"),
    UNRESOLVED("仍未解决"),
    FAILED("验证失败");

    private final String label;

    VerificationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
