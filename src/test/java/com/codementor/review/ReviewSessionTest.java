package com.codementor.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
