package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.context.ContextTarget;
import com.codementor.context.PsiContextExtractor;
import com.codementor.history.LearningHistoryService;
import com.codementor.model.ModelRequestConfig;
import com.codementor.model.PrivacyFilter;
import com.codementor.privacy.OutboundRequestGuard;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/** Re-reads the original target and verifies one suggestion without mixing review generations. */
@Service(Service.Level.PROJECT)
public final class VerificationCoordinator implements Disposable {

    private final Project project;
    private final Map<String, PendingVerification> pending = new HashMap<>();

    public VerificationCoordinator(Project project) {
        this.project = project;
    }

    public static VerificationCoordinator getInstance(Project project) {
        return project.getService(VerificationCoordinator.class);
    }

    /** Called from the EDT by a suggestion card. */
    public void verify(Suggestion suggestion) {
        ReviewSession session = ReviewSession.getInstance(project);
        ReviewContext originalContext = session.getContext();
        if (originalContext == null) return;
        if (originalContext.getMethodStart() < 0) {
            failImmediately(
                    session,
                    suggestion,
                    "选中代码暂不支持修改验证；请把光标放在完整方法内重新评审。");
            return;
        }

        final ModelRequestConfig config;
        try {
            config = ModelRequestConfig.capture(CodeMentorSettings.getInstance());
        } catch (IllegalArgumentException e) {
            failImmediately(session, suggestion, e.getMessage());
            return;
        }
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, originalContext);
        if (!outbound.allowed()) {
            failImmediately(session, suggestion, outbound.reason());
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.isDisposed()) {
            failImmediately(session, suggestion, "请先打开原来评审的 Java 文件。");
            return;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.getDocument());
        if (psiFile == null) {
            failImmediately(session, suggestion, "当前编辑器不是可分析的 Java 文件。");
            return;
        }

        cancelPending(suggestion.getId(), "已开始新的修改验证。");
        String requestId = UUID.randomUUID().toString();
        String learningReviewId = session.getLearningReviewId();
        session.startVerification(suggestion.getId(), requestId);

        ContextTarget target = ReviewCoordinator.getInstance(project)
                .captureVerificationTarget(editor, originalContext);
        if (target == null) {
            failImmediately(
                    session,
                    suggestion,
                    "无法安全定位原来评审的方法；它可能已被删除，请重新评审。");
            return;
        }
        long initialStamp = target.document().getModificationStamp();
        PendingVerification verification = new PendingVerification(
                target.document(),
                session,
                suggestion.getId(),
                requestId,
                originalContext,
                learningReviewId);
        pending.put(suggestion.getId(), verification);

        Future<?> collection = ReadAction.<ReviewContext>nonBlocking(
                        () -> PsiContextExtractor.extract(project, target, psiFile))
                .withDocumentsCommitted(project)
                .coalesceBy(this, suggestion.getId())
                .expireWith(project)
                .finishOnUiThread(
                        ModalityState.defaultModalityState(),
                        currentContext -> onContextCollected(
                                verification,
                                currentContext,
                                suggestion,
                                config,
                                initialStamp))
                .submit(AppExecutorUtil.getAppExecutorService());
        verification.setCollection(collection);
    }

    private void onContextCollected(
            PendingVerification verification,
            ReviewContext currentContext,
            Suggestion suggestion,
            ModelRequestConfig config,
            long initialStamp) {
        if (!isPending(verification) || !verification.isSameReview()) return;
        verification.clearCollection();
        if (verification.document.getModificationStamp() != initialStamp) {
            verification.cancel("采集期间代码再次变化，请停止输入后重试。");
            return;
        }
        ReviewContext originalContext = verification.originalContext;
        if (!ReviewCoordinator.getInstance(project).matchesVerificationTarget(
                originalContext, currentContext)) {
            verification.cancel("请把光标放回原来评审的方法，再点击验证。");
            return;
        }
        if (!hasVerificationInputChanged(originalContext, currentContext)) {
            verification.cancel("代码尚未变化，请先根据提示完成修改。");
            return;
        }
        OutboundRequestGuard.Decision outbound =
                OutboundRequestGuard.getInstance(project).evaluate(config, currentContext);
        if (!outbound.allowed()) {
            verification.cancel(outbound.reason());
            return;
        }

        verification.attach();
        VerificationRequest request =
                new VerificationRequest(originalContext, currentContext, suggestion);
        CancellableRequest handle = CodeMentorService.getInstance().getReviewService().verify(
                config,
                request,
                verification.requestId,
                new ReviewService.VerificationCallback() {
                    @Override
                    public void onSuccess(VerificationResult result) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!finishPending(verification) || !verification.isSameReview()) {
                                return;
                            }
                            if (verification.session.completeVerification(
                                    suggestion.getId(), verification.requestId, result)) {
                                LearningHistoryService.getInstance().recordVerification(
                                        project,
                                        originalContext,
                                        suggestion,
                                        result,
                                        verification.learningReviewId);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!finishPending(verification) || !verification.isSameReview()) {
                                return;
                            }
                            verification.session.failVerification(
                                    suggestion.getId(), verification.requestId, errorMessage);
                        });
                    }
                });
        verification.setRequest(handle);
    }

    static boolean isSameTarget(
            ReviewContext originalContext, ReviewContext currentContext) {
        return originalContext != null
                && currentContext != null
                && originalContext.getMethodStart() >= 0
                && currentContext.getMethodStart() >= 0
                && originalContext.getFilePath().equals(currentContext.getFilePath())
                && !originalContext.getTargetIdentity().isBlank()
                && originalContext.getTargetIdentity().equals(
                        currentContext.getTargetIdentity());
    }

    static boolean hasVerificationInputChanged(
            ReviewContext originalContext, ReviewContext currentContext) {
        return originalContext != null
                && currentContext != null
                && !PrivacyFilter.mask(originalContext.getModelContext()).equals(
                        PrivacyFilter.mask(currentContext.getModelContext()));
    }

    private static void failImmediately(
            ReviewSession session, Suggestion suggestion, String message) {
        String requestId = UUID.randomUUID().toString();
        session.startVerification(suggestion.getId(), requestId);
        session.failVerification(suggestion.getId(), requestId, message);
    }

    private boolean isPending(PendingVerification verification) {
        return pending.get(verification.suggestionId) == verification;
    }

    private void cancelPending(String suggestionId, String message) {
        PendingVerification existing = pending.remove(suggestionId);
        if (existing != null) existing.cancel(message);
    }

    private boolean finishPending(PendingVerification expected) {
        if (!pending.remove(expected.suggestionId, expected)) return false;
        expected.close();
        return true;
    }

    public void cancelAll(String message) {
        for (PendingVerification verification : new ArrayList<>(pending.values())) {
            verification.cancel(message);
        }
        pending.clear();
    }

    @Override
    public void dispose() {
        cancelAll("项目已关闭。");
    }

    private final class PendingVerification implements DocumentListener {
        private final Document document;
        private final ReviewSession session;
        private final String suggestionId;
        private final String requestId;
        private final ReviewContext originalContext;
        private final String learningReviewId;

        private Future<?> collection;
        private CancellableRequest request;
        private boolean attached;
        private boolean closed;

        private PendingVerification(
                Document document,
                ReviewSession session,
                String suggestionId,
                String requestId,
                ReviewContext originalContext,
                String learningReviewId) {
            this.document = document;
            this.session = session;
            this.suggestionId = suggestionId;
            this.requestId = requestId;
            this.originalContext = originalContext;
            this.learningReviewId = learningReviewId;
        }

        private boolean isSameReview() {
            return session.getContext() == originalContext
                    && learningReviewId.equals(session.getLearningReviewId());
        }

        private void setCollection(Future<?> future) {
            collection = future;
            if (closed) future.cancel(true);
        }

        private void clearCollection() {
            collection = null;
        }

        private void attach() {
            document.addDocumentListener(this);
            attached = true;
        }

        private void setRequest(CancellableRequest request) {
            this.request = request;
            if (closed) request.cancel();
        }

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            cancel("验证期间当前文件再次变化，请停止输入后重试。");
        }

        private void cancel(String message) {
            if (closed) return;
            pending.remove(suggestionId, this);
            close();
            session.failVerification(suggestionId, requestId, message);
        }

        private void close() {
            if (closed) return;
            closed = true;
            if (collection != null) {
                collection.cancel(true);
                collection = null;
            }
            if (request != null) {
                request.cancel();
                request = null;
            }
            if (attached) {
                document.removeDocumentListener(this);
                attached = false;
            }
        }
    }
}
