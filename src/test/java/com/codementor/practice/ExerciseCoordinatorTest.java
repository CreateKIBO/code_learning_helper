package com.codementor.practice;

import com.codementor.review.FeedbackType;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseCoordinatorTest {

    @Test
    void currentSuggestionFallbackExcludesClosedOrLowConfidenceItems() {
        Suggestion medium = suggestion(Suggestion.Certainty.MEDIUM);
        Suggestion low = suggestion(Suggestion.Certainty.LOW);
        VerificationResult resolved = new VerificationResult(
                VerificationStatus.RESOLVED, "问题已经消失", "");
        VerificationResult verifying = VerificationResult.verifying();

        assertTrue(ExerciseCoordinator.isPracticeCandidate(medium, null, null));
        assertFalse(ExerciseCoordinator.isPracticeCandidate(low, null, null));
        assertFalse(ExerciseCoordinator.isPracticeCandidate(
                medium, FeedbackType.FALSE_POSITIVE, null));
        assertFalse(ExerciseCoordinator.isPracticeCandidate(medium, null, resolved));
        assertFalse(ExerciseCoordinator.isPracticeCandidate(medium, null, verifying));
    }

    private static Suggestion suggestion(Suggestion.Certainty certainty) {
        return new Suggestion.Builder()
                .id("S1")
                .category(Suggestion.Category.JAVA_USAGE)
                .certainty(certainty)
                .title("集合类型")
                .knowledgePoint("Java 泛型")
                .build();
    }
}
