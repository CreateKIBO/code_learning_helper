package com.codementor.review;

import com.codementor.javaweb.JavaWebContext;
import com.codementor.settings.CodeMentorSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewCacheAndDeduplicationTest {

    @Test
    void removesDuplicateSuggestionsAndReassignsStableIds() {
        Suggestion first = suggestion("old-a", "资源管理", "关闭资源");
        Suggestion duplicate = suggestion("old-b", "资源管理", "关闭资源");
        Suggestion other = suggestion("old-c", "异常", "不要吞异常");

        List<Suggestion> result =
                SuggestionDeduplicator.deduplicate(List.of(first, duplicate, other));

        assertEquals(2, result.size());
        assertEquals("S1", result.get(0).getId());
        assertEquals("S2", result.get(1).getId());
    }

    @Test
    void cacheSeparatesCodeFingerprintsAndModelConfigurations() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext first = context("fingerprint-a");
        ReviewContext second = context("fingerprint-b");
        List<Suggestion> suggestions = List.of(suggestion("S1", "集合", "使用泛型"));

        cache.put(first, "model-a", CodeMentorSettings.TeachingLevel.JAVAWEB, suggestions);

        assertTrue(cache.get(
                first, "model-a", CodeMentorSettings.TeachingLevel.JAVAWEB).isPresent());
        assertTrue(cache.get(
                second, "model-a", CodeMentorSettings.TeachingLevel.JAVAWEB).isEmpty());
        assertTrue(cache.get(
                first, "model-b", CodeMentorSettings.TeachingLevel.JAVAWEB).isEmpty());
    }

    @Test
    void cacheInvalidatesWhenProjectLearningRulesChange() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext oldRules = context("same", "Controller 只做参数接收");
        ReviewContext newRules = context("same", "Controller 可以编排简单业务");
        List<Suggestion> suggestions = List.of(suggestion("S1", "分层", "保持轻薄"));

        cache.put(
                oldRules,
                "model-a",
                CodeMentorSettings.TeachingLevel.JAVAWEB,
                suggestions);

        assertTrue(cache.get(
                newRules,
                "model-a",
                CodeMentorSettings.TeachingLevel.JAVAWEB).isEmpty());
    }

    @Test
    void cacheDoesNotMixReviewAndExplanationModes() {
        ReviewCache cache = new ReviewCache(2);
        ReviewContext review = context("same", "");
        ReviewContext explanation = review.withReviewMode(ReviewMode.EXPLAIN);
        List<Suggestion> suggestions = List.of(suggestion("S1", "流程", "理解流程"));

        cache.put(
                review,
                "model-a",
                CodeMentorSettings.TeachingLevel.BEGINNER,
                suggestions);

        assertTrue(cache.get(
                explanation,
                "model-a",
                CodeMentorSettings.TeachingLevel.BEGINNER).isEmpty());
    }

    private static ReviewContext context(String fingerprint) {
        return context(fingerprint, "");
    }

    private static ReviewContext context(String fingerprint, String rules) {
        return new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                "class Demo { void run() {} }",
                false,
                1L,
                fingerprint,
                0,
                13,
                JavaWebContext.unknown(),
                rules);
    }

    private static Suggestion suggestion(String id, String knowledgePoint, String title) {
        return new Suggestion.Builder()
                .id(id)
                .category(Suggestion.Category.JAVA_USAGE)
                .certainty(Suggestion.Certainty.MEDIUM)
                .title(title)
                .knowledgePoint(knowledgePoint)
                .verificationCriteria("问题不再存在")
                .build();
    }
}
