package com.codementor.review;

import com.codementor.practice.Exercise;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewSessionTest {

    @Test
    void keepsSuggestionsVisibleAfterCodeExpires() {
        ReviewSession session = new ReviewSession();
        ReviewContext context = context("old");
        session.startRequest(context, "request-a");
        assertTrue(session.succeed("request-a", List.of(suggestion())));

        session.expire("request-a");

        assertEquals(ReviewStatus.EXPIRED, session.getStatus());
        assertEquals(1, session.getSuggestions().size());
    }

    @Test
    void ignoresLateVerificationResponse() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("old"), "review");
        session.succeed("review", List.of(suggestion()));
        session.startVerification("S1", "verify-new");

        boolean accepted = session.completeVerification(
                "S1",
                "verify-old",
                new VerificationResult(VerificationStatus.RESOLVED, "x", ""));

        assertFalse(accepted);
        assertEquals(
                VerificationStatus.VERIFYING,
                session.getVerificationResult("S1").getStatus());
    }

    @Test
    void invalidatesCompletedVerificationButPreservesActiveRequest() {
        ReviewSession session = new ReviewSession();
        session.startVerification("S1", "verify-current");

        assertFalse(session.invalidateCompletedVerifications());
        assertEquals(
                VerificationStatus.VERIFYING,
                session.getVerificationResult("S1").getStatus());
        assertTrue(session.completeVerification(
                "S1",
                "verify-current",
                new VerificationResult(VerificationStatus.RESOLVED, "fixed", "")));

        assertTrue(session.invalidateCompletedVerifications());
        assertEquals(
                VerificationStatus.IDLE,
                session.getVerificationResult("S1").getStatus());
    }

    @Test
    void cancellableThreadRequestInterruptsWorker() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        CancellableRequest request = CancellableRequest.forThread(worker);
        worker.start();

        request.cancel();

        assertTrue(request.isCancelled());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void keepsLearningReviewIdAfterExpiryAndClearsItOnReset() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("old"), "review-42");
        session.succeed("review-42", List.of(suggestion()));

        session.expire("review-42");

        assertEquals("review-42", session.getLearningReviewId());
        session.reset();
        assertEquals("", session.getLearningReviewId());
    }

    @Test
    void keepsSummaryAndImmutableSuggestionsAfterExpiry() {
        ReviewSession session = new ReviewSession();
        List<Suggestion> mutableSuggestions = new ArrayList<>(List.of(suggestion()));
        ReviewResult result = new ReviewResult("  当前方法职责清晰  ", mutableSuggestions);
        mutableSuggestions.clear();

        session.startRequest(context("old"), "review-summary");
        assertTrue(session.succeed("review-summary", result));

        assertEquals("当前方法职责清晰", session.getSummary());
        assertEquals(1, session.getSuggestions().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> session.getSuggestions().add(suggestion()));

        session.expire("review-summary");

        assertEquals("当前方法职责清晰", session.getSummary());
        assertEquals(1, session.getSuggestions().size());
    }

    @Test
    void cachedReplayKeepsOriginalLearningReviewId() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("same"), "request-for-replay");

        assertTrue(session.succeed(
                "request-for-replay",
                new ReviewResult("缓存摘要", List.of(suggestion())),
                "original-learning-review"));

        assertEquals("original-learning-review", session.getLearningReviewId());
    }

    @Test
    void preservesExpandedCardsOnExpiryButClearsThemForNewReviewAndReset() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("old"), "review-a");
        session.succeed("review-a", List.of(suggestion()));
        assertTrue(session.toggleExplanation("S1"));
        assertTrue(session.toggleExample("S1"));

        session.expire("review-a");

        assertTrue(session.isExplanationExpanded("S1"));
        assertTrue(session.isExampleExpanded("S1"));

        session.startRequest(context("new"), "review-b");

        assertFalse(session.isExplanationExpanded("S1"));
        assertFalse(session.isExampleExpanded("S1"));

        session.succeed("review-b", List.of(suggestion()));
        session.toggleExplanation("S1");
        session.toggleExample("S1");
        session.reset();

        assertFalse(session.isExplanationExpanded("S1"));
        assertFalse(session.isExampleExpanded("S1"));
    }

    @Test
    void keepsFollowUpQuestionThroughSuccessAndFailureAndRejectsLateResponses() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("old"), "review");
        session.succeed("review", List.of(suggestion()));

        session.startFollowUp("S1", "follow-up-a", "为什么要使用泛型？");
        assertEquals(
                "为什么要使用泛型？",
                session.getFollowUpResult("S1").getQuestion());

        session.startFollowUp("S1", "follow-up-b", "类型擦除是什么？");
        assertFalse(session.completeFollowUp("S1", "follow-up-a", "迟到的旧回答"));
        assertEquals(
                FollowUpResult.Status.REQUESTING,
                session.getFollowUpResult("S1").getStatus());
        assertEquals("类型擦除是什么？", session.getFollowUpResult("S1").getQuestion());

        assertTrue(session.completeFollowUp("S1", "follow-up-b", "编译后会擦除部分类型信息。"));
        assertEquals(FollowUpResult.Status.SUCCESS,
                session.getFollowUpResult("S1").getStatus());
        assertEquals("类型擦除是什么？", session.getFollowUpResult("S1").getQuestion());
        assertEquals("编译后会擦除部分类型信息。",
                session.getFollowUpResult("S1").getAnswer());

        session.startFollowUp("S1", "follow-up-c", "那运行期如何检查类型？");
        assertFalse(session.failFollowUp("S1", "follow-up-b", "迟到的旧失败"));
        assertEquals("那运行期如何检查类型？",
                session.getFollowUpResult("S1").getQuestion());

        assertTrue(session.failFollowUp("S1", "follow-up-c", "网络不可用"));
        assertEquals(FollowUpResult.Status.FAILED,
                session.getFollowUpResult("S1").getStatus());
        assertEquals("那运行期如何检查类型？",
                session.getFollowUpResult("S1").getQuestion());
        assertEquals("网络不可用", session.getFollowUpResult("S1").getAnswer());
    }

    @Test
    void cancelsOnlyAnActiveFollowUpAndRejectsItsLateAnswer() {
        ReviewSession session = new ReviewSession();
        session.startRequest(context("old"), "review");
        session.succeed("review", List.of(suggestion()));
        session.startFollowUp("S1", "follow-up", "这个泛型约束有什么作用？");

        assertTrue(session.cancelFollowUp("S1"));
        assertEquals(FollowUpResult.Status.FAILED,
                session.getFollowUpResult("S1").getStatus());
        assertEquals("这个泛型约束有什么作用？",
                session.getFollowUpResult("S1").getQuestion());
        assertEquals("已取消本次追问。",
                session.getFollowUpResult("S1").getAnswer());
        assertFalse(session.completeFollowUp("S1", "follow-up", "迟到回答"));
        assertFalse(session.cancelFollowUp("S1"));
    }

    @Test
    void revealsExerciseHintsWithinBoundsAndClearsProgressAcrossLifecycles() {
        ReviewSession session = new ReviewSession();
        session.startExercise("exercise-a");
        assertTrue(session.completeExercise(
                "exercise-a", exercise("先确定元素类型", "再检查泛型边界")));

        assertEquals(0, session.getRevealedExerciseHints());
        assertTrue(session.revealNextExerciseHint());
        assertEquals(1, session.getRevealedExerciseHints());
        assertTrue(session.revealNextExerciseHint());
        assertEquals(2, session.getRevealedExerciseHints());
        assertFalse(session.revealNextExerciseHint());
        assertEquals(2, session.getRevealedExerciseHints());

        session.startExercise("exercise-b");
        assertEquals(0, session.getRevealedExerciseHints());
        assertTrue(session.completeExercise("exercise-b", exercise("先写出方法签名")));
        assertTrue(session.revealNextExerciseHint());
        session.cancelExercise();
        assertEquals(0, session.getRevealedExerciseHints());

        session.startExercise("exercise-c");
        assertTrue(session.completeExercise("exercise-c", exercise("先从输入开始追踪")));
        assertTrue(session.revealNextExerciseHint());
        session.reset();
        assertEquals(0, session.getRevealedExerciseHints());
    }

    @Test
    void derivesBusyStateAcrossEveryModelBackedOperation() {
        ReviewSession session = new ReviewSession();
        assertFalse(session.hasPendingOperation());

        session.startRequest(context("review"), "review");
        assertTrue(session.hasPendingOperation());
        session.fail("review", "failed");
        assertFalse(session.hasPendingOperation());

        session.startVerification("S1", "verification");
        assertTrue(session.hasPendingOperation());
        session.failVerification("S1", "verification", "failed");
        assertFalse(session.hasPendingOperation());

        session.startFollowUp("S1", "follow-up", "为什么？");
        assertTrue(session.hasPendingOperation());
        session.cancelFollowUp("S1");
        assertFalse(session.hasPendingOperation());

        session.startExercise("exercise");
        assertTrue(session.hasPendingOperation());
        session.cancelExercise();
        assertFalse(session.hasPendingOperation());
    }

    private static ReviewContext context(String fingerprint) {
        return new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                "class Demo { void run() {} }",
                false,
                1L,
                fingerprint,
                0,
                13);
    }

    private static Suggestion suggestion() {
        return new Suggestion.Builder()
                .id("S1")
                .category(Suggestion.Category.JAVA_USAGE)
                .certainty(Suggestion.Certainty.MEDIUM)
                .title("使用泛型")
                .build();
    }

    private static Exercise exercise(String... hints) {
        return new Exercise(
                "泛型练习",
                "Java 泛型",
                "补全一个类型安全的方法",
                "",
                List.of(),
                List.of(hints),
                List.of("不出现未检查类型转换"));
    }
}
