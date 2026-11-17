package com.codementor.review;

import java.util.List;

/**
 * Immutable structured result for one review or explanation request.
 *
 * <p>{@link #steps} 是可选的结果级讲解：解释模式下是逐段讲解，下一步指导模式下是整体思路；
 * 评审模式恒为空。
 */
public record ReviewResult(String summary, List<Suggestion> suggestions, List<GuideStep> steps) {

    public ReviewResult {
        summary = summary == null ? "" : summary.trim();
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public ReviewResult(String summary, List<Suggestion> suggestions) {
        this(summary, suggestions, List.of());
    }

    public ReviewResult withSuggestions(List<Suggestion> replacement) {
        return new ReviewResult(summary, replacement, steps);
    }

    public ReviewResult withSteps(List<GuideStep> replacement) {
        return new ReviewResult(summary, suggestions, replacement);
    }
}
