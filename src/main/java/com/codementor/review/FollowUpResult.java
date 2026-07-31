package com.codementor.review;

/** 侧栏中一条建议的追问状态。 */
public final class FollowUpResult {

    public enum Status {
        IDLE,
        REQUESTING,
        SUCCESS,
        FAILED
    }

    private final Status status;
    private final String answer;

    public FollowUpResult(Status status, String answer) {
        this.status = status;
        this.answer = answer == null ? "" : answer;
    }

    public Status getStatus() { return status; }
    public String getAnswer() { return answer; }

    public static FollowUpResult idle() {
        return new FollowUpResult(Status.IDLE, "");
    }

    public static FollowUpResult requesting() {
        return new FollowUpResult(Status.REQUESTING, "");
    }

    public static FollowUpResult failed(String message) {
        return new FollowUpResult(Status.FAILED, message);
    }
}
