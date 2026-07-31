package com.codementor.review;

import org.jetbrains.annotations.NotNull;

/** 修改验证请求：保留原始上下文、当前上下文和要验证的建议。 */
public final class VerificationRequest {

    private final ReviewContext originalContext;
    private final ReviewContext currentContext;
    private final Suggestion suggestion;

    public VerificationRequest(@NotNull ReviewContext originalContext,
                               @NotNull ReviewContext currentContext,
                               @NotNull Suggestion suggestion) {
        this.originalContext = originalContext;
        this.currentContext = currentContext;
        this.suggestion = suggestion;
    }

    public ReviewContext getOriginalContext() {
        return originalContext;
    }

    public ReviewContext getCurrentContext() {
        return currentContext;
    }

    public Suggestion getSuggestion() {
        return suggestion;
    }
}
