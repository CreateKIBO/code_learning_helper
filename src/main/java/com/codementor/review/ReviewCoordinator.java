package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.context.ContextTarget;
import com.codementor.context.ProjectFilePath;
import com.codementor.context.PsiContextExtractor;
import com.codementor.history.LearningHistoryService;
import com.codementor.javaweb.ProjectLearningRulesService;
import com.codementor.model.ModelRequestConfig;
import com.codementor.practice.ExerciseCoordinator;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorSettings;
import com.codementor.ui.SendPreviewDialog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates target capture, privacy consent, caching, cancellation and review session updates. */
@Service(Service.Level.PROJECT)
public final class ReviewCoordinator implements Disposable {

    public enum Trigger {
        MANUAL,
        SAVE
    }

    private final Project project;
    private final ReviewCache cache = new ReviewCache(50);
    private final AtomicLong generation = new AtomicLong();

    private CompletableFuture<String> rulesFuture;
    private Future<?> contextFuture;
    private ExpiryWatcher currentWatcher;
    private CancellableRequest currentRequest;
    private long activeManualGeneration = -1L;
    private Document activeManualDocument;
    private long activeManualDocumentStamp = -1L;

    public ReviewCoordinator(Project project) {
        this.project = project;
    }

    public static ReviewCoordinator getInstance(Project project) {
        return project.getService(ReviewCoordinator.class);
    }

    public void review(Editor editor, PsiFile psiFile, Trigger trigger) {
        review(editor, psiFile, trigger, null, ReviewMode.REVIEW);
    }

    public void review(
            Editor editor, PsiFile psiFile, Trigger trigger, Integer preferredOffset) {
        review(editor, psiFile, trigger, preferredOffset, ReviewMode.REVIEW);
    }

    public void review(Editor editor, PsiFile psiFile, Trigger trigger, ReviewMode mode) {
        review(editor, psiFile, trigger, null, mode);
    }

    private void review(
            Editor editor,
            PsiFile psiFile,
            Trigger trigger,
            Integer preferredOffset,
            ReviewMode mode) {
        if (project.isDisposed() || editor == null || editor.isDisposed() || psiFile == null) {
            return;
        }
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        if (!settings.isReviewEnabled()) return;

        final ModelRequestConfig config;
        try {
            config = ModelRequestConfig.capture(settings);
        } catch (IllegalArgumentException e) {
            if (trigger == Trigger.MANUAL) {
                Messages.showErrorDialog(project, e.getMessage(), "CodeMentor 配置无效");
            }
            return;
        }

        CodeMentorProjectConsent consent = CodeMentorProjectConsent.getInstance(project);
        if (trigger == Trigger.SAVE
                && (!settings.isSaveReviewEnabled() || !consent.hasConsentFor(config))) {
            return;
        }
        if (trigger == Trigger.SAVE
                && (hasActiveManualPipeline()
                        || ReviewSession.getInstance(project).getStatus()
                                == ReviewStatus.REQUESTING)) {
            // A delayed save callback must not replace a user-initiated request.
            return;
        }

        ProjectFilePath.Result projectFile = ProjectFilePath.resolve(project, psiFile);
        OutboundRequestGuard.Decision fileDecision =
                OutboundRequestGuard.getInstance(project).evaluateFile(projectFile);
        if (!fileDecision.allowed()) {
            if (trigger == Trigger.MANUAL) {
                Messages.showWarningDialog(
                        project, fileDecision.reason(), "CodeMentor 隐私保护");
            }
            return;
        }

        ContextTarget target = ContextTarget.capture(editor, preferredOffset);
        long initialStamp = target.document().getModificationStamp();
        long requestGeneration = generation.incrementAndGet();
        cancelPipeline();
        if (trigger == Trigger.MANUAL) {
            activeManualGeneration = requestGeneration;
            activeManualDocument = target.document();
            activeManualDocumentStamp = initialStamp;
        }
        cancelLearningSubtasks();
        // A superseded request must never leave the tool window stuck in REQUESTING,
        // and an old SUCCESS result must not lose its expiry watcher after a save.
        ReviewSession.getInstance(project).reset();

        CompletableFuture<String> pendingRules =
                ProjectLearningRulesService.getInstance(project).loadRules();
        rulesFuture = pendingRules;
        pendingRules.whenComplete((rules, error) ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrent(requestGeneration)) {
                        finishManualPipeline(requestGeneration);
                        return;
                    }
                    rulesFuture = null;
                    if (target.document().getModificationStamp() != initialStamp
                            || editor.isDisposed()
                            || !psiFile.isValid()) {
                        finishManualPipeline(requestGeneration);
                        return;
                    }
                    collectContext(
                            target,
                            psiFile,
                            trigger,
                            mode,
                            config,
                            requestGeneration,
                            initialStamp,
                            error == null && rules != null ? rules : "");
                }));
    }

    private void collectContext(
            ContextTarget target,
            PsiFile psiFile,
            Trigger trigger,
            ReviewMode mode,
            ModelRequestConfig config,
            long requestGeneration,
            long initialStamp,
            String projectRules) {
        contextFuture = ReadAction.<ReviewContext>nonBlocking(() -> {
                    if (!isCurrent(requestGeneration) || !psiFile.isValid()) return null;
                    ReviewContext context = PsiContextExtractor.extract(project, target, psiFile);
                    return context == null
                            ? null
                            : context.withProjectRules(projectRules).withReviewMode(mode);
                })
                .withDocumentsCommitted(project)
                .expireWhen(() -> !isCurrent(requestGeneration)
                        || target.document().getModificationStamp() != initialStamp)
                .expireWith(project)
                .finishOnUiThread(
                        ModalityState.defaultModalityState(),
                        context -> onContextCollected(
                                target.document(),
                                context,
                                trigger,
                                config,
                                requestGeneration))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void onContextCollected(
            Document document,
            ReviewContext context,
            Trigger trigger,
            ModelRequestConfig config,
            long requestGeneration) {
        if (!isCurrent(requestGeneration)) return;
        contextFuture = null;
        if (context == null) {
            finishManualPipeline(requestGeneration);
            if (trigger == Trigger.MANUAL) resetAndExplainMissingTarget();
            return;
        }
        if (document.getModificationStamp() != context.getDocumentStamp()) {
            finishManualPipeline(requestGeneration);
            return;
        }

        Optional<ReviewCache.CachedReview> cached = cache.get(context, config);
        if (cached.isEmpty() && trigger == Trigger.MANUAL
                && !ensureCodeSendingConsent(config, context)) {
            finishManualPipeline(requestGeneration);
            ReviewSession.getInstance(project).reset();
            return;
        }

        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, context);
        if (cached.isEmpty() && !outbound.allowed()) {
            finishManualPipeline(requestGeneration);
            failBeforeRequest(trigger, outbound.reason());
            return;
        }

        ReviewSession session = ReviewSession.getInstance(project);
        String requestId = UUID.randomUUID().toString();
        session.startRequest(context, requestId);

        ExpiryWatcher watcher = new ExpiryWatcher(
                document,
                context,
                session,
                requestId,
                () -> onWatcherExpired(requestGeneration));
        currentWatcher = watcher;
        watcher.attach();

        if (cached.isPresent()) {
            completeReview(
                    session,
                    requestId,
                    context,
                    cached.get().result(),
                    config,
                    requestGeneration,
                    cached.get().learningReviewId());
            return;
        }

        currentRequest = CodeMentorService.getInstance().getReviewService().review(
                config,
                context,
                requestId,
                new ReviewService.Callback() {
                    @Override
                    public void onSuccess(ReviewResult result) {
                        ApplicationManager.getApplication().invokeLater(() -> completeReview(
                                session,
                                requestId,
                                context,
                                result,
                                config,
                                requestGeneration,
                                ""));
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrent(requestGeneration)) return;
                            currentRequest = null;
                            finishManualPipeline(requestGeneration);
                            session.fail(requestId, errorMessage);
                            detachCurrentWatcher();
                        });
                    }
                });
        if (!isCurrent(requestGeneration) || session.getStatus() == ReviewStatus.EXPIRED) {
            cancelCurrentRequest();
        }
    }

    private void completeReview(
            ReviewSession session,
            String requestId,
            ReviewContext context,
            ReviewResult rawResult,
            ModelRequestConfig config,
            long requestGeneration,
            String cachedLearningReviewId) {
        if (!isCurrent(requestGeneration)) return;
        List<Suggestion> suggestions = SuggestionDeduplicator.deduplicate(
                rawResult.suggestions());
        ReviewResult result = rawResult.withSuggestions(suggestions);
        boolean fromCache = cachedLearningReviewId != null
                && !cachedLearningReviewId.isBlank();
        String learningReviewId = fromCache ? cachedLearningReviewId : requestId;
        if (!session.succeed(requestId, result, learningReviewId)) return;

        currentRequest = null;
        finishManualPipeline(requestGeneration);
        if (!fromCache) {
            cache.put(context, config, result, learningReviewId);
        }
        if (context.getReviewMode() != ReviewMode.REVIEW) return;

        LearningHistoryService history = LearningHistoryService.getInstance();
        if (!fromCache) {
            history.recordReview(project, context, suggestions, learningReviewId);
        }
        history.findReminders(3, 3).thenAccept(reminders ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    ReviewSession currentSession = ReviewSession.getInstance(project);
                    if (!project.isDisposed()
                            && isCurrent(requestGeneration)
                            && currentSession.getContext() == context) {
                        currentSession.setKnowledgeReminders(reminders);
                    }
                }));
    }

    private boolean ensureCodeSendingConsent(
            ModelRequestConfig config, ReviewContext context) {
        CodeMentorProjectConsent consent = CodeMentorProjectConsent.getInstance(project);
        boolean firstConsent = !consent.hasConsentFor(config);
        if (!firstConsent
                && !CodeMentorSettings.getInstance().isPreviewEveryManualRequest()) {
            return true;
        }
        if (!SendPreviewDialog.confirm(project, config, context, firstConsent)) {
            return false;
        }
        if (firstConsent) consent.grant(config);
        return true;
    }

    private void failBeforeRequest(Trigger trigger, String message) {
        ReviewSession.getInstance(project).reset();
        if (trigger == Trigger.MANUAL) {
            Messages.showWarningDialog(project, message, "CodeMentor 未发送");
        }
    }

    private void resetAndExplainMissingTarget() {
        cancelPipeline();
        ReviewSession.getInstance(project).reset();
        Messages.showInfoMessage(
                project,
                "请选中一段代码，或把光标放在某个方法内部后再评审。",
                "CodeMentor");
    }

    private void onWatcherExpired(long requestGeneration) {
        if (!isCurrent(requestGeneration)) return;
        // Invalidate callbacks synchronously. The session UI transition is queued by
        // ExpiryWatcher, so cancellation alone cannot stop a response already queued on EDT.
        generation.incrementAndGet();
        cancelCurrentRequest();
        finishManualPipeline(requestGeneration);
    }

    ContextTarget captureVerificationTarget(Editor editor, ReviewContext context) {
        ExpiryWatcher watcher = currentWatcher;
        return watcher == null ? null : watcher.captureCurrentTarget(editor, context);
    }

    boolean matchesVerificationTarget(
            ReviewContext originalContext, ReviewContext currentContext) {
        ExpiryWatcher watcher = currentWatcher;
        return watcher != null
                && watcher.matchesCurrentTarget(originalContext, currentContext);
    }

    private boolean isCurrent(long expectedGeneration) {
        return !project.isDisposed() && generation.get() == expectedGeneration;
    }

    private boolean hasActiveManualPipeline() {
        if (activeManualGeneration < 0) return false;
        ReviewStatus status = ReviewSession.getInstance(project).getStatus();
        if (status != ReviewStatus.REQUESTING
                && activeManualDocument != null
                && activeManualDocument.getModificationStamp()
                        != activeManualDocumentStamp) {
            cancelPipeline();
            return false;
        }
        boolean active = rulesFuture != null
                || contextFuture != null
                || currentRequest != null
                || status == ReviewStatus.REQUESTING;
        if (!active) clearActiveManualPipeline();
        return active;
    }

    private void finishManualPipeline(long requestGeneration) {
        if (activeManualGeneration == requestGeneration) {
            clearActiveManualPipeline();
        }
    }

    private void clearActiveManualPipeline() {
        activeManualGeneration = -1L;
        activeManualDocument = null;
        activeManualDocumentStamp = -1L;
    }

    private void cancelLearningSubtasks() {
        VerificationCoordinator.getInstance(project).cancelAll("已开始新的代码评审。");
        FollowUpCoordinator.getInstance(project).cancelAll();
        ExerciseCoordinator.getInstance(project).cancelCurrent();
    }

    private void cancelPipeline() {
        clearActiveManualPipeline();
        if (rulesFuture != null) {
            rulesFuture.cancel(true);
            rulesFuture = null;
        }
        if (contextFuture != null) {
            contextFuture.cancel(true);
            contextFuture = null;
        }
        cancelCurrentRequest();
        detachCurrentWatcher();
    }

    /** Cancels all project model work. Cancellation cannot recall data already received remotely. */
    public void cancelAll() {
        generation.incrementAndGet();
        cancelPipeline();
        cancelLearningSubtasks();
        ReviewSession.getInstance(project).reset();
    }

    /** Clears volatile review state together with persistent learning history deletion. */
    public void clearLearningState() {
        cancelAll();
        cache.clear();
    }

    private void cancelCurrentRequest() {
        if (currentRequest != null) {
            currentRequest.cancel();
            currentRequest = null;
        }
    }

    private void detachCurrentWatcher() {
        if (currentWatcher != null) {
            currentWatcher.detach();
            currentWatcher = null;
        }
    }

    @Override
    public void dispose() {
        generation.incrementAndGet();
        cancelPipeline();
    }
}
