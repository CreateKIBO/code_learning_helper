package com.codementor.history;

import java.util.List;

/** 由追加式学习事件即时计算的本地学习摘要。 */
public final class LearningSummary {

    private final int reviewedSuggestions;
    private final int explanationViews;
    private final int completedVerifications;
    private final int resolvedVerifications;
    private final int helpfulFeedback;
    private final int notHelpfulFeedback;
    private final int falsePositiveFeedback;
    private final List<KnowledgeReminder> weakKnowledgePoints;

    public LearningSummary(int reviewedSuggestions,
                           int explanationViews,
                           int completedVerifications,
                           int resolvedVerifications,
                           int helpfulFeedback,
                           int notHelpfulFeedback,
                           int falsePositiveFeedback,
                           List<KnowledgeReminder> weakKnowledgePoints) {
        this.reviewedSuggestions = reviewedSuggestions;
        this.explanationViews = explanationViews;
        this.completedVerifications = completedVerifications;
        this.resolvedVerifications = resolvedVerifications;
        this.helpfulFeedback = helpfulFeedback;
        this.notHelpfulFeedback = notHelpfulFeedback;
        this.falsePositiveFeedback = falsePositiveFeedback;
        this.weakKnowledgePoints = List.copyOf(weakKnowledgePoints);
    }

    public int getReviewedSuggestions() { return reviewedSuggestions; }
    public int getExplanationViews() { return explanationViews; }
    public int getCompletedVerifications() { return completedVerifications; }
    public int getResolvedVerifications() { return resolvedVerifications; }
    public int getHelpfulFeedback() { return helpfulFeedback; }
    public int getNotHelpfulFeedback() { return notHelpfulFeedback; }
    public int getFalsePositiveFeedback() { return falsePositiveFeedback; }
    public List<KnowledgeReminder> getWeakKnowledgePoints() {
        return weakKnowledgePoints;
    }

    public int getResolutionRatePercent() {
        return percentage(resolvedVerifications, completedVerifications);
    }

    public int getExplanationViewRatePercent() {
        return percentage(explanationViews, reviewedSuggestions);
    }

    private static int percentage(int value, int total) {
        return total == 0 ? 0 : Math.round(value * 100f / total);
    }
}
