package com.codementor.review;

import java.util.List;

/** Immutable structured result for one review or explanation request. */
public record ReviewResult(String summary, List<Suggestion> suggestions) {

    public ReviewResult {
        summary = summary == null ? "" : summary.trim();
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public ReviewResult withSuggestions(List<Suggestion> replacement) {
        return new ReviewResult(summary, replacement);
    }
}
