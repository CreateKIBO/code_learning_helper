package com.codementor.review;

/** 针对当前建议的追问请求。 */
public final class FollowUpRequest {

    private final ReviewContext context;
    private final Suggestion suggestion;
    private final String question;

    public FollowUpRequest(ReviewContext context, Suggestion suggestion, String question) {
        this.context = context;
        this.suggestion = suggestion;
        this.question = question;
    }

    public ReviewContext getContext() { return context; }
    public Suggestion getSuggestion() { return suggestion; }
    public String getQuestion() { return question; }
}
