package com.codementor.review;

import org.jetbrains.annotations.NotNull;

/** 模型对“用户是否解决了原建议”的结构化判断。 */
public final class VerificationResult {

    private final VerificationStatus status;
    private final String evidence;
    private final String nextHint;

    public VerificationResult(@NotNull VerificationStatus status, String evidence, String nextHint) {
        this.status = status;
        this.evidence = evidence == null ? "" : evidence;
        this.nextHint = nextHint == null ? "" : nextHint;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getNextHint() {
        return nextHint;
    }

    public static VerificationResult verifying() {
        return new VerificationResult(VerificationStatus.VERIFYING, "", "");
    }

    public static VerificationResult failed(String message) {
        return new VerificationResult(VerificationStatus.FAILED, message, "");
    }
}
