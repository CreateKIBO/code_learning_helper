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
    private final String question;
    private final String answer;

    public FollowUpResult(Status status, String answer) {
        this(status, "", answer);
    }

    public FollowUpResult(Status status, String question, String answer) {
        this.status = status;
        this.question = question == null ? "" : question;
        this.answer = answer == null ? "" : answer;
    }

    public Status getStatus() { return status; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }

    public static FollowUpResult idle() {
        return new FollowUpResult(Status.IDLE, "");
    }

    public static FollowUpResult requesting(String question) {
        return new FollowUpResult(Status.REQUESTING, question, "");
    }

    public static FollowUpResult success(String question, String answer) {
        return new FollowUpResult(Status.SUCCESS, question, answer);
    }

    public static FollowUpResult failed(String question, String message) {
        return new FollowUpResult(Status.FAILED, question, message);
    }
}
