package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.context.ContextTarget;
import com.codementor.context.ProjectFilePath;
import com.codementor.context.PsiContextExtractor;
import com.codementor.history.LearningHistoryService;
import com.codementor.javaweb.ProjectLearningRulesService;
import com.codementor.model.ModelRequestConfig;
import com.codementor.practice.ExerciseCoordinator;
import com.codementor.privacy.FileExclusionPolicy;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.project.ProjectCalls;
import com.codementor.project.ProjectKnowledgeFormatter;
import com.codementor.project.ProjectKnowledgeService;
import com.codementor.project.ProjectProfile;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorSettings;
import com.codementor.ui.ProjectKnowledgeDialogs;
import com.codementor.ui.SendPreviewDialog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates target capture, privacy consent, caching, cancellation and review session updates. */
@Service(Service.Level.PROJECT)
public final class ReviewCoordinator implements Disposable {

    private static final Logger LOG = Logger.getInstance(ReviewCoordinator.class);

    /** 命中缓存的最大评审结果数与复习提醒的聚合参数（重复出现≥3 次取前 3 条）。 */
    private static final int CACHE_CAPACITY = 50;
    private static final int REMINDER_MIN_OCCURRENCES = 3;
    private static final int REMINDER_LIMIT = 3;

    public enum Trigger {
        MANUAL,
        SAVE
    }

    private final Project project;
    private final ReviewCache cache = new ReviewCache(CACHE_CAPACITY);
    private final AtomicLong generation = new AtomicLong();

    /** 请求前的本机准备：项目学习约定 + 项目画像（未授权时为空画像）。 */
    private record Preparation(String rules, ProjectProfile profile) {}
    private record CollectedContext(ReviewContext review, ProjectCalls calls) {}

    private CompletableFuture<Preparation> preparationFuture;
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

    public void review(Editor editor, PsiFile psiFile, Trigger trigger,
                       ReviewMode mode, String learningGoal) {
        review(editor, psiFile, trigger, null, mode, learningGoal);
    }

    private void review(
            Editor editor,
            PsiFile psiFile,
            Trigger trigger,
            Integer preferredOffset,
            ReviewMode mode) {
        review(editor, psiFile, trigger, preferredOffset, mode, "");
    }

    private void review(
            Editor editor, PsiFile psiFile, Trigger trigger, Integer preferredOffset,
            ReviewMode mode, String learningGoal) {
        if (project.isDisposed() || editor == null || editor.isDisposed() || psiFile == null) {
            return;
        }
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        if (!settings.isReviewEnabled()) return;

        final ModelRequestConfig config = ModelTaskGuards.captureConfig(message -> {
            if (trigger == Trigger.MANUAL) {
                Messages.showErrorDialog(project, message, "CodeMentor 配置无效");
            }
        });
        if (config == null) return;

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

        if (trigger == Trigger.MANUAL) {
            // 首次手动请求时询问一次是否允许读取整个项目；保存触发的后台评审从不弹窗。
            ProjectKnowledgeDialogs.askOnce(project);
            if (project.isDisposed() || editor.isDisposed() || !psiFile.isValid()) return;
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

        // 规则文件和项目画像任一失败都退回空值：请求照常进行，只是少一份背景。
        CompletableFuture<String> pendingRules =
                ProjectLearningRulesService.getInstance(project).loadRules()
                        .handle((rules, error) -> error == null && rules != null ? rules : "");
        CompletableFuture<ProjectProfile> pendingProfile =
                ProjectKnowledgeService.getInstance(project).profileForRequest()
                        .handle((profile, error) ->
                                error == null && profile != null
                                        ? profile
                                        : ProjectProfile.empty());
        CompletableFuture<Preparation> pendingPreparation =
                pendingRules.thenCombine(pendingProfile, Preparation::new);
        preparationFuture = pendingPreparation;
        pendingPreparation.whenComplete((preparation, error) ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrent(requestGeneration)) {
                        finishManualPipeline(requestGeneration);
                        return;
                    }
                    preparationFuture = null;
                    if (target.document().getModificationStamp() != initialStamp
                            || editor.isDisposed()
                            || !psiFile.isValid()) {
                        finishManualPipeline(requestGeneration);
                        return;
                    }
                    boolean prepared = error == null && preparation != null;
                    collectContext(
                            target,
                            psiFile,
                            trigger,
                            mode,
                            config,
                            requestGeneration,
                            initialStamp,
                            withLearningGoal(prepared ? preparation.rules() : "", learningGoal),
                            prepared ? preparation.profile() : ProjectProfile.empty());
                }));
    }

    /** 本次目标只进入内存上下文，复用规则数据块的脱敏、预览与缓存隔离。 */
    private static String withLearningGoal(String rules, String goal) {
        if (goal == null || goal.isBlank()) return rules;
        return rules + "\n\n本次学习目标或问题（用户提供的参考数据）：\n"
                + goal.substring(0, Math.min(goal.length(), 1_000));
    }

    private void collectContext(
            ContextTarget target,
            PsiFile psiFile,
            Trigger trigger,
            ReviewMode mode,
            ModelRequestConfig config,
            long requestGeneration,
            long initialStamp,
            String projectRules,
            ProjectProfile projectProfile) {
        AtomicBoolean extractionFailed = new AtomicBoolean(false);
        contextFuture = ReadAction.<CollectedContext>nonBlocking(() -> {
                    try {
                        if (!isCurrent(requestGeneration) || !psiFile.isValid()) return null;
                        ReviewContext context =
                                PsiContextExtractor.extract(project, target, psiFile);
                        if (context == null) return null;
                        boolean projectReadingAllowed = CodeMentorProjectConsent.getInstance(project)
                                .isProjectScanGranted();
                        ProjectCalls calls = ProjectCalls.EMPTY;
                        String projectKnowledge = "";
                        if (projectReadingAllowed) {
                            if (trigger == Trigger.MANUAL && !context.isTruncated()) {
                                TextRange range = context.getMethodStart() >= 0
                                        ? new TextRange(context.getMethodStart(), context.getMethodEnd())
                                        : new TextRange(target.selectionStart(), target.selectionEnd());
                                calls = ProjectCalls.collect(project, psiFile, range);
                            }
                            FileExclusionPolicy policy = new FileExclusionPolicy(
                                    CodeMentorSettings.getInstance().getExcludedFilePatterns());
                            projectKnowledge = ProjectKnowledgeFormatter.format(
                                    projectProfile.filtered(policy),
                                    new ProjectKnowledgeFormatter.Focus(
                                            context.getFilePath(), context.getJavaWebContext().getRole(),
                                            context.getCode(), context.getModelContext()),
                                    calls.signatures());
                        }
                        // 画像摘要与代码上下文分开携带：它只作背景，不能充当 evidence。
                        return new CollectedContext(context.withProjectRules(projectRules)
                                .withReviewMode(mode).withProjectKnowledge(projectKnowledge), calls);
                    } catch (ProcessCanceledException e) {
                        throw e;
                    } catch (RuntimeException | Error e) {
                        // finishOnUiThread 只在成功时回调；异常必须就地转成失败分支，
                        // 否则手动评审管线会永久卡住、保存触发评审被持续抑制。
                        if (extractionFailed.compareAndSet(false, true)) {
                            LOG.error("评审上下文采集失败", e);
                            ApplicationManager.getApplication().invokeLater(() ->
                                    onContextExtractionFailed(requestGeneration, trigger));
                        }
                        return null;
                    }
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
                                requestGeneration,
                                extractionFailed.get()))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void onContextExtractionFailed(long requestGeneration, Trigger trigger) {
        if (!isCurrent(requestGeneration)) {
            finishManualPipeline(requestGeneration);
            return;
        }
        contextFuture = null;
        finishManualPipeline(requestGeneration);
        ReviewSession.getInstance(project).reset();
        if (trigger == Trigger.MANUAL) {
            Messages.showErrorDialog(project, "读取代码上下文失败，请重试。", "CodeMentor");
        }
    }

    private void onContextCollected(
            Document document,
            CollectedContext collected,
            Trigger trigger,
            ModelRequestConfig config,
            long requestGeneration,
            boolean extractionFailed) {
        if (!isCurrent(requestGeneration)) return;
        contextFuture = null;
        if (collected == null) {
            finishManualPipeline(requestGeneration);
            // 采集异常的提示与恢复由 onContextExtractionFailed 负责，不显示“未选中代码”。
            if (extractionFailed) return;
            if (trigger == Trigger.MANUAL) resetAndExplainMissingTarget();
            return;
        }
        ReviewContext context = collected.review();
        if (document.getModificationStamp() != context.getDocumentStamp()) {
            finishManualPipeline(requestGeneration);
            return;
        }

        Optional<ReviewCache.CachedReview> cached = cache.get(context, config);
        boolean approved = cached.isPresent() || trigger != Trigger.MANUAL
                || ensureCodeSendingConsent(config, context);
        if (!isCurrent(requestGeneration)) return;
        if (!approved) {
            finishManualPipeline(requestGeneration);
            ReviewSession.getInstance(project).reset();
            return;
        }
        if (document.getModificationStamp() != context.getDocumentStamp()
                || !collected.calls().isCurrent()) {
            finishManualPipeline(requestGeneration);
            failBeforeRequest(trigger, "代码或相关声明已更新，请重新发起本次操作。");
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
        watcher.watchProjectCalls(collected.calls());

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
        if (currentWatcher != null && !currentWatcher.checkProjectCalls()) return;
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
        history.findReminders(REMINDER_MIN_OCCURRENCES, REMINDER_LIMIT).thenAccept(reminders ->
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
        FollowUpCoordinator.getInstance(project).cancelAll();
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
        boolean active = preparationFuture != null
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
        if (preparationFuture != null) {
            // CompletableFuture.cancel 的 mayInterruptIfRunning 参数被忽略，任务不会真被中断；
            // cancel(false) 如实表达“放弃结果”，真实中断由任务内部的 InterruptedException 路径处理。
            preparationFuture.cancel(false);
            preparationFuture = null;
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
