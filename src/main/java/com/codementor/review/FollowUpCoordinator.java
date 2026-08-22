package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.model.ModelRequestConfig;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Manages per-suggestion follow-ups and prevents replies from crossing review generations. */
@Service(Service.Level.PROJECT)
public final class FollowUpCoordinator implements Disposable {

    private static final int MAX_QUESTION_CHARS = 2_000;

    private final Project project;
    private final Map<String, PendingFollowUp> pending = new HashMap<>();

    public FollowUpCoordinator(Project project) {
        this.project = project;
    }

    public static FollowUpCoordinator getInstance(Project project) {
        return project.getService(FollowUpCoordinator.class);
    }

    public void ask(Suggestion suggestion, String question) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) return;

        ReviewSession session = ReviewSession.getInstance(project);
        ReviewContext context = session.getContext();
        if (context == null) return;
        if (normalizedQuestion.length() > MAX_QUESTION_CHARS) {
            failImmediately(
                    session, suggestion, normalizedQuestion,
                    "追问最多 2000 个字符，请缩短后重试。");
            return;
        }

        final ModelRequestConfig config;
        try {
            config = ModelRequestConfig.capture(CodeMentorSettings.getInstance());
        } catch (IllegalArgumentException e) {
            failImmediately(session, suggestion, normalizedQuestion, e.getMessage());
            return;
        }
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, context);
        if (!outbound.allowed()) {
            failImmediately(session, suggestion, normalizedQuestion, outbound.reason());
            return;
        }

        PendingFollowUp previous = pending.remove(suggestion.getId());
        if (previous != null) previous.cancel();

        String requestId = UUID.randomUUID().toString();
        PendingFollowUp followUp = new PendingFollowUp(
                context, session.getLearningReviewId());
        pending.put(suggestion.getId(), followUp);
        session.startFollowUp(suggestion.getId(), requestId, normalizedQuestion);

        CancellableRequest handle = CodeMentorService.getInstance()
                .getReviewService()
                .followUp(
                        config,
                        new FollowUpRequest(context, suggestion, normalizedQuestion),
                        requestId,
                        new ReviewService.FollowUpCallback() {
                            @Override
                            public void onSuccess(String answer) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!finish(suggestion.getId(), followUp)) return;
                                    session.completeFollowUp(
                                            suggestion.getId(), requestId, answer);
                                });
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!finish(suggestion.getId(), followUp)) return;
                                    session.failFollowUp(
                                            suggestion.getId(), requestId, errorMessage);
                                });
                            }
                        });
        followUp.setRequest(handle);
    }

    private static void failImmediately(
            ReviewSession session, Suggestion suggestion, String question, String message) {
        String requestId = UUID.randomUUID().toString();
        session.startFollowUp(suggestion.getId(), requestId, question);
        session.failFollowUp(suggestion.getId(), requestId, message);
    }

    private boolean finish(String suggestionId, PendingFollowUp expected) {
        if (!pending.remove(suggestionId, expected)) return false;
        if (expected.isSameReview()) return true;
        expected.cancel();
        return false;
    }

    public void cancel(String suggestionId) {
        PendingFollowUp followUp = pending.remove(suggestionId);
        if (followUp == null) return;
        followUp.cancel();
        if (!project.isDisposed() && followUp.isSameReview()) {
            ReviewSession.getInstance(project).cancelFollowUp(suggestionId);
        }
    }

    public void cancelAll() {
        for (PendingFollowUp followUp : pending.values()) followUp.cancel();
        pending.clear();
    }

    @Override
    public void dispose() {
        cancelAll();
    }

    private final class PendingFollowUp {
        private final ReviewContext context;
        private final String learningReviewId;
        private CancellableRequest request;
        private boolean cancelled;

        private PendingFollowUp(
                ReviewContext context, String learningReviewId) {
            this.context = context;
            this.learningReviewId = learningReviewId;
        }

        private boolean isSameReview() {
            ReviewSession session = ReviewSession.getInstance(project);
            return session.getContext() == context
                    && learningReviewId.equals(session.getLearningReviewId());
        }

        private void setRequest(CancellableRequest request) {
            this.request = request;
            if (cancelled) request.cancel();
        }

        private void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (request != null) request.cancel();
        }
    }
}
