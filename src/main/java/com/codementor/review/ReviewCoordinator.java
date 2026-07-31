package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.context.PsiContextExtractor;
import com.codementor.history.LearningHistoryService;
import com.codementor.javaweb.ProjectLearningRulesService;
import com.codementor.privacy.FileExclusionPolicy;
import com.codementor.privacy.SendPreviewFormatter;
import com.codementor.settings.CodeMentorSettings;
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

/**
 * 项目级评审协调器：统一手动与保存触发的采集、同意、缓存、取消和状态更新。
 */
@Service(Service.Level.PROJECT)
public final class ReviewCoordinator implements Disposable {

    public enum Trigger {
        MANUAL,
        SAVE
    }

    private final Project project;
    private final ReviewCache cache = new ReviewCache(50);
    private ExpiryWatcher currentWatcher;
    private CancellableRequest currentRequest;

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

    public void review(
            Editor editor, PsiFile psiFile, Trigger trigger, ReviewMode mode) {
        review(editor, psiFile, trigger, null, mode);
    }

    private void review(
            Editor editor,
            PsiFile psiFile,
            Trigger trigger,
            Integer preferredOffset,
            ReviewMode mode) {
        if (project.isDisposed()) return;
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        if (!settings.isReviewEnabled()) return;
        if (trigger == Trigger.SAVE
                && (!settings.isSaveReviewEnabled() || !settings.hasConsentedToSend())) {
            return;
        }
        FileExclusionPolicy.Decision exclusion =
                new FileExclusionPolicy(settings.getExcludedFilePatterns())
                        .evaluate(relativePath(psiFile));
        if (exclusion.excluded()) {
            if (trigger == Trigger.MANUAL) {
                Messages.showWarningDialog(
                        project,
                        "当前文件命中排除规则「" + exclusion.matchedPattern()
                                + "」，不会采集上下文或调用模型。",
                        "CodeMentor 隐私保护");
            }
            return;
        }

        ProjectLearningRulesService.getInstance(project).loadRules()
                .thenAccept(rules ->
                        collectContext(
                                editor, psiFile, trigger, preferredOffset, mode, rules));
    }

    private void collectContext(
            Editor editor,
            PsiFile psiFile,
            Trigger trigger,
            Integer preferredOffset,
            ReviewMode mode,
            String projectRules) {
        if (project.isDisposed()) return;
        ReadAction.<ReviewContext>nonBlocking(() -> {
                    ReviewContext context =
                            PsiContextExtractor.extract(
                                    project, editor, psiFile, preferredOffset);
                    return context == null
                            ? null
                            : context.withProjectRules(projectRules)
                                    .withReviewMode(mode);
                })
                .coalesceBy(this)
                .expireWith(project)
                .finishOnUiThread(
                        ModalityState.defaultModalityState(),
                        context -> onContextCollected(editor, context, trigger))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void onContextCollected(Editor editor, ReviewContext context, Trigger trigger) {
        if (context == null) {
            if (trigger == Trigger.MANUAL) {
                resetAndExplainMissingTarget();
            }
            return;
        }
        if (editor.getDocument().getModificationStamp() != context.getDocumentStamp()) {
            return;
        }

        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        String model = settings.getModel();
        CodeMentorSettings.TeachingLevel teachingLevel = settings.getTeachingLevel();
        Optional<List<Suggestion>> cached =
                cache.get(context, model, teachingLevel);
        if (trigger == Trigger.SAVE && cached.isPresent()) {
            return;
        }
        if (trigger == Trigger.MANUAL && !ensureCodeSendingConsent(context)) {
            ReviewSession.getInstance(project).reset();
            return;
        }

        cancelCurrentRequest();
        detachCurrentWatcher();

        ReviewSession session = ReviewSession.getInstance(project);
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        session.startRequest(context, requestId);

        Document document = editor.getDocument();
        ExpiryWatcher watcher = new ExpiryWatcher(
                document,
                context,
                session,
                requestId,
                this::cancelCurrentRequest);
        watcher.attach();
        currentWatcher = watcher;

        if (cached.isPresent()) {
            completeReview(
                    session, requestId, document, context, cached.get(), model, teachingLevel);
            return;
        }

        ReviewService service = CodeMentorService.getInstance().getReviewService();
        CancellableRequest request = service.review(
                context,
                requestId,
                new ReviewService.Callback() {
                    @Override
                    public void onSuccess(List<Suggestion> suggestions) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                completeReview(
                                        session,
                                        requestId,
                                        document,
                                        context,
                                        suggestions,
                                        model,
                                        teachingLevel));
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            session.fail(requestId, errorMessage);
                            if (currentWatcher == watcher) {
                                detachCurrentWatcher();
                            }
                        });
                    }
                });
        currentRequest = request;
        if (session.getStatus() == ReviewStatus.EXPIRED) {
            cancelCurrentRequest();
        }
    }

    private void completeReview(ReviewSession session,
                                String requestId,
                                Document document,
                                ReviewContext context,
                                List<Suggestion> rawSuggestions,
                                String model,
                                CodeMentorSettings.TeachingLevel teachingLevel) {
        if (document.getModificationStamp() != context.getDocumentStamp()) {
            session.expire(requestId);
            return;
        }
        List<Suggestion> suggestions =
                SuggestionDeduplicator.deduplicate(rawSuggestions);
        if (!session.succeed(requestId, suggestions)) {
            return;
        }
        currentRequest = null;
        cache.put(context, model, teachingLevel, suggestions);
        LearningHistoryService history = LearningHistoryService.getInstance();
        if (context.getReviewMode() == ReviewMode.REVIEW) {
            history.recordReview(project, context, suggestions, requestId);
            history.findReminders(3, 3).thenAccept(reminders ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()
                                && ReviewSession.getInstance(project).getContext() == context) {
                            ReviewSession.getInstance(project)
                                    .setKnowledgeReminders(reminders);
                        }
                    }));
        }
    }

    private void resetAndExplainMissingTarget() {
        cancelCurrentRequest();
        detachCurrentWatcher();
        ReviewSession.getInstance(project).reset();
        Messages.showInfoMessage(
                project,
                "请选中一段代码，或把光标放在某个方法内部后再评审。",
                "CodeMentor");
    }

    private boolean ensureCodeSendingConsent(ReviewContext context) {
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        boolean firstConsent = !settings.hasConsentedToSend();
        if (!firstConsent && !settings.isPreviewEveryManualRequest()) return true;
        int choice = Messages.showOkCancelDialog(
                project,
                "<html><body style='width:420px'>"
                        + "即将把以下上下文发送给 AI 模型进行评审"
                        + "（敏感信息已自动遮盖为 ***）：<br><br>"
                        + "<pre style='background:#f0f0f0;padding:6px;'>"
                        + previewEscape(SendPreviewFormatter.format(context, 1_200))
                        + "</pre><br>"
                        + "代码会发送到你配置的模型服务。是否继续？"
                        + "</body></html>",
                firstConsent ? "首次发送确认 · CodeMentor" : "发送前预览 · CodeMentor",
                firstConsent ? "发送并记住" : "本次发送",
                "取消",
                Messages.getQuestionIcon());
        if (choice != Messages.OK) return false;
        if (firstConsent) {
            settings.setConsentedToSend(true);
        }
        return true;
    }

    private static String previewEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String relativePath(PsiFile psiFile) {
        if (psiFile.getVirtualFile() == null) return psiFile.getName();
        String path = psiFile.getVirtualFile().getPath();
        String base = project.getBasePath();
        if (base != null) {
            String normalizedBase = base.replace('\\', '/');
            if (path.startsWith(normalizedBase + "/")) {
                return path.substring(normalizedBase.length() + 1);
            }
        }
        return psiFile.getVirtualFile().getName();
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
        cancelCurrentRequest();
        detachCurrentWatcher();
    }
}
