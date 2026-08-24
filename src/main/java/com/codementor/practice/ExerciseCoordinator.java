package com.codementor.practice;

import com.codementor.CodeMentorService;
import com.codementor.history.KnowledgeReminder;
import com.codementor.history.LearningHistoryService;
import com.codementor.javaweb.JavaWebRole;
import com.codementor.model.ModelRequestConfig;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.review.CancellableRequest;
import com.codementor.review.FeedbackType;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewService;
import com.codementor.review.ReviewSession;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.codementor.review.VerificationStatus;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Generates one bounded practice task from local learning history. */
@Service(Service.Level.PROJECT)
public final class ExerciseCoordinator implements Disposable {

    private final Project project;
    private final AtomicLong generation = new AtomicLong();
    private CompletableFuture<List<KnowledgeReminder>> reminderFuture;
    private CancellableRequest currentRequest;

    public ExerciseCoordinator(Project project) {
        this.project = project;
    }

    public static ExerciseCoordinator getInstance(Project project) {
        return project.getService(ExerciseCoordinator.class);
    }

    public void generate() {
        long requestGeneration = generation.incrementAndGet();
        cancelPendingWork();

        ReviewSession session = ReviewSession.getInstance(project);
        final ModelRequestConfig config;
        try {
            config = ModelRequestConfig.capture(CodeMentorSettings.getInstance());
        } catch (IllegalArgumentException e) {
            failImmediately(session, e.getMessage());
            return;
        }
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, null);
        if (!outbound.allowed()) {
            failImmediately(session, outbound.reason());
            return;
        }

        String requestId = UUID.randomUUID().toString();
        ReviewContext contextSnapshot = session.getContext();
        List<Suggestion> suggestionsSnapshot = session.getSuggestions() == null
                ? List.of()
                : List.copyOf(session.getSuggestions());
        session.startExercise(requestId);

        reminderFuture = LearningHistoryService.getInstance().findReminders(2, 1);
        reminderFuture.whenComplete((reminders, error) ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrent(requestGeneration)) return;
                    reminderFuture = null;
                    if (error != null) {
                        session.failExercise(requestId, "读取本地学习记录失败，请稍后重试。");
                        return;
                    }
                    startModelRequest(
                            requestGeneration,
                            requestId,
                            config,
                            contextSnapshot,
                            suggestionsSnapshot,
                            reminders == null ? List.of() : reminders);
                }));
    }

    private void startModelRequest(
            long requestGeneration,
            String requestId,
            ModelRequestConfig config,
            ReviewContext context,
            List<Suggestion> suggestions,
            List<KnowledgeReminder> reminders) {
        if (!isCurrent(requestGeneration)) return;
        ReviewSession session = ReviewSession.getInstance(project);
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, null);
        if (!outbound.allowed()) {
            session.failExercise(requestId, outbound.reason());
            return;
        }
        List<Suggestion> eligibleSuggestions = suggestions.stream()
                .filter(suggestion -> isPracticeCandidate(
                        suggestion,
                        session.getFeedback(suggestion.getId()),
                        session.getVerificationResult(suggestion.getId())))
                .toList();
        ExerciseTarget target = chooseTarget(eligibleSuggestions, reminders);
        if (target == null) {
            session.failExercise(
                    requestId,
                    "还没有可用于出题的学习记录。先完成一次代码评审，或让某个知识点累计出现两次。");
            return;
        }

        JavaWebRole role = context == null
                ? JavaWebRole.UNKNOWN
                : context.getJavaWebContext().getRole();
        ExerciseRequest request = new ExerciseRequest(
                target.knowledgePoint,
                target.occurrences,
                config.teachingLevel(),
                role);
        currentRequest = CodeMentorService.getInstance().getReviewService().generateExercise(
                config,
                request,
                requestId,
                new ReviewService.ExerciseCallback() {
                    @Override
                    public void onSuccess(Exercise exercise) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrent(requestGeneration)) return;
                            if (!isFallbackStillEligible(target, session)) {
                                currentRequest = null;
                                session.failExercise(
                                        requestId,
                                        "这条建议的状态已经变化，旧练习未展示。请重新生成。");
                                return;
                            }
                            if (session.completeExercise(requestId, exercise)) {
                                currentRequest = null;
                            }
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrent(requestGeneration)) return;
                            if (session.failExercise(requestId, errorMessage)) {
                                currentRequest = null;
                            }
                        });
                    }
                });
    }

    private static ExerciseTarget chooseTarget(
            List<Suggestion> suggestions, List<KnowledgeReminder> reminders) {
        if (!reminders.isEmpty()) {
            KnowledgeReminder reminder = reminders.get(0);
            return new ExerciseTarget(
                    reminder.getKnowledgePoint(), reminder.getOccurrences(), "");
        }
        return suggestions.stream()
                .filter(suggestion -> !suggestion.getKnowledgePoint().isBlank())
                .findFirst()
                .map(suggestion -> new ExerciseTarget(
                        suggestion.getKnowledgePoint(), 1, suggestion.getId()))
                .orElse(null);
    }

    static boolean isPracticeCandidate(
            Suggestion suggestion,
            FeedbackType feedback,
            VerificationResult verification) {
        return suggestion != null
                && suggestion.getCertainty() != Suggestion.Certainty.LOW
                && feedback != FeedbackType.FALSE_POSITIVE
                && (verification == null
                        || (verification.getStatus() != VerificationStatus.RESOLVED
                                && verification.getStatus() != VerificationStatus.VERIFYING));
    }

    private static boolean isFallbackStillEligible(
            ExerciseTarget target, ReviewSession session) {
        if (target.suggestionId.isBlank()) return true;
        return session.getSuggestions() != null
                && session.getSuggestions().stream()
                        .filter(suggestion -> target.suggestionId.equals(suggestion.getId()))
                        .findFirst()
                        .map(suggestion -> isPracticeCandidate(
                                suggestion,
                                session.getFeedback(suggestion.getId()),
                                session.getVerificationResult(suggestion.getId())))
                        .orElse(false);
    }

    private static void failImmediately(ReviewSession session, String message) {
        String requestId = UUID.randomUUID().toString();
        session.startExercise(requestId);
        session.failExercise(requestId, message);
    }

    private boolean isCurrent(long expected) {
        return !project.isDisposed() && generation.get() == expected;
    }

    public void cancelCurrent() {
        generation.incrementAndGet();
        cancelPendingWork();
        if (!project.isDisposed()) {
            ReviewSession.getInstance(project).cancelExercise();
        }
    }

    private void cancelPendingWork() {
        if (reminderFuture != null) {
            // CompletableFuture.cancel 的 mayInterruptIfRunning 参数被忽略，只表达“放弃结果”。
            reminderFuture.cancel(false);
            reminderFuture = null;
        }
        if (currentRequest != null) {
            currentRequest.cancel();
            currentRequest = null;
        }
    }

    @Override
    public void dispose() {
        cancelCurrent();
    }

    private record ExerciseTarget(
            String knowledgePoint, int occurrences, String suggestionId) {}
}
