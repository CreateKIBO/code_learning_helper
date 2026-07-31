package com.codementor.history;

import com.codementor.review.FeedbackType;
import com.codementor.review.ReviewContext;
import com.codementor.review.Suggestion;
import com.codementor.review.VerificationResult;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** 应用级学习记录入口；用单线程保持追加事件顺序。 */
@Service(Service.Level.APP)
public final class LearningHistoryService implements Disposable {

    private static final Logger LOG = Logger.getInstance(LearningHistoryService.class);

    private final ExecutorService executor =
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "CodeMentor Learning History", 1);
    private final LearningEventStore store = new LearningEventStore(
            Path.of(PathManager.getSystemPath(), "codementor", "learning-events.jsonl"));

    public static LearningHistoryService getInstance() {
        return ApplicationManager.getApplication().getService(LearningHistoryService.class);
    }

    public void recordReview(Project project,
                             ReviewContext context,
                             List<Suggestion> suggestions,
                             String reviewId) {
        for (Suggestion suggestion : suggestions) {
            append(new LearningEvent(
                    reviewId,
                    LearningEvent.Type.REVIEWED,
                    project.getName(),
                    context.getFilePath(),
                    context.getTargetName(),
                    suggestion.getId(),
                    suggestion.getKnowledgePoint(),
                    suggestion.getTitle(),
                    suggestion.getEvidence(),
                    ""));
        }
    }

    public void recordExplanationViewed(Project project,
                                        ReviewContext context,
                                        Suggestion suggestion,
                                        String reviewId) {
        append(eventFor(
                LearningEvent.Type.EXPLANATION_VIEWED,
                project, context, suggestion, reviewId, "", ""));
    }

    public void recordFeedback(Project project, ReviewContext context,
                               Suggestion suggestion, FeedbackType feedback,
                               String reviewId) {
        append(eventFor(
                LearningEvent.Type.FEEDBACK,
                project, context, suggestion, reviewId, "", feedback.name()));
    }

    public void recordVerification(Project project, ReviewContext context,
                                   Suggestion suggestion, VerificationResult result,
                                   String reviewId) {
        append(eventFor(
                LearningEvent.Type.VERIFICATION_COMPLETED,
                project, context, suggestion, reviewId,
                result.getEvidence(), result.getStatus().name()));
    }

    public CompletableFuture<Path> exportMarkdown(Path target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                store.exportMarkdown(target);
                return target;
            } catch (IOException e) {
                throw new IllegalStateException("导出学习记录失败：" + e.getMessage(), e);
            }
        }, executor);
    }

    public CompletableFuture<List<KnowledgeReminder>> findReminders(
            int minimumOccurrences, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.findRepeatedKnowledgePoints(minimumOccurrences, limit);
            } catch (IOException e) {
                LOG.warn("读取复习提醒失败", e);
                return List.of();
            }
        }, executor);
    }

    public CompletableFuture<LearningSummary> loadSummary() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.summarize();
            } catch (IOException e) {
                throw new IllegalStateException("读取学习统计失败：" + e.getMessage(), e);
            }
        }, executor);
    }

    private LearningEvent eventFor(LearningEvent.Type type,
                                   Project project,
                                   ReviewContext context,
                                   Suggestion suggestion,
                                   String reviewId,
                                   String detail,
                                   String outcome) {
        return new LearningEvent(
                reviewId,
                type,
                project.getName(),
                context.getFilePath(),
                context.getTargetName(),
                suggestion.getId(),
                suggestion.getKnowledgePoint(),
                suggestion.getTitle(),
                detail,
                outcome);
    }

    private void append(LearningEvent event) {
        executor.execute(() -> {
            try {
                store.append(event);
            } catch (IOException e) {
                LOG.warn("学习记录写入失败", e);
            }
        });
    }

    @Override
    public void dispose() {
        executor.shutdownNow();
    }
}
