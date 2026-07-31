package com.codementor.review;

import com.codementor.CodeMentorService;
import com.codementor.context.PsiContextExtractor;
import com.codementor.history.LearningHistoryService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
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

/** 协调“重新读取代码 → 调模型验证 → 更新会话 → 写学习记录”的完整流程。 */
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

    /**
     * 从当前编辑器重新采集代码并验证指定建议。该方法应由 EDT 上的 UI 操作调用。
     */
    public void verify(Suggestion suggestion) {
        ReviewSession session = ReviewSession.getInstance(project);
        ReviewContext originalContext = session.getContext();
        if (originalContext == null) {
            return;
        }

        String verificationRequestId = UUID.randomUUID().toString().substring(0, 8);
        cancelPending(suggestion.getId(), "已开始新的修改验证。");
        session.startVerification(suggestion.getId(), verificationRequestId);

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            session.failVerification(
                    suggestion.getId(), verificationRequestId, "请先打开原来评审的 Java 文件。");
            return;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            session.failVerification(
                    suggestion.getId(), verificationRequestId, "当前编辑器不是可分析的 Java 文件。");
            return;
        }

        ReadAction.<ReviewContext>nonBlocking(
                        () -> PsiContextExtractor.extract(project, editor, psiFile))
                .coalesceBy(this, suggestion.getId())
                .expireWith(project)
                .finishOnUiThread(
                        ModalityState.defaultModalityState(),
                        currentContext -> onContextCollected(
                                editor.getDocument(),
                                originalContext,
                                currentContext,
                                suggestion,
                                verificationRequestId))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void onContextCollected(Document document,
                                    ReviewContext originalContext,
                                    ReviewContext currentContext,
                                    Suggestion suggestion,
                                    String verificationRequestId) {
        ReviewSession session = ReviewSession.getInstance(project);
        if (currentContext == null
                || !originalContext.getFilePath().equals(currentContext.getFilePath())
                || !originalContext.getTargetName().equals(currentContext.getTargetName())) {
            session.failVerification(
                    suggestion.getId(),
                    verificationRequestId,
                    "请把光标放回原来评审的方法，再点击验证。");
            return;
        }
        if (originalContext.getFingerprint().equals(currentContext.getFingerprint())) {
            session.failVerification(
                    suggestion.getId(),
                    verificationRequestId,
                    "代码尚未变化，请先根据提示完成修改。");
            return;
        }
        if (document.getModificationStamp() != currentContext.getDocumentStamp()) {
            session.failVerification(
                    suggestion.getId(),
                    verificationRequestId,
                    "采集后代码再次变化，请停止输入后重试。");
            return;
        }

        VerificationRequest request =
                new VerificationRequest(originalContext, currentContext, suggestion);
        PendingVerification pendingVerification = new PendingVerification(
                document, session, suggestion.getId(), verificationRequestId, currentContext);
        pending.put(suggestion.getId(), pendingVerification);
        pendingVerification.attach();
        CancellableRequest requestHandle =
                CodeMentorService.getInstance().getReviewService().verify(
                request,
                verificationRequestId,
                new ReviewService.VerificationCallback() {
                    @Override
                    public void onSuccess(VerificationResult result) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!finishPending(suggestion.getId(), pendingVerification)) {
                                return;
                            }
                            if (document.getModificationStamp() != currentContext.getDocumentStamp()) {
                                session.failVerification(
                                        suggestion.getId(),
                                        verificationRequestId,
                                        "验证期间代码再次变化，请重新验证。");
                                return;
                            }
                            if (session.completeVerification(
                                    suggestion.getId(), verificationRequestId, result)) {
                                LearningHistoryService.getInstance().recordVerification(
                                        project,
                                        originalContext,
                                        suggestion,
                                        result,
                                        session.getLearningReviewId());
                            }
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!finishPending(suggestion.getId(), pendingVerification)) {
                                return;
                            }
                            session.failVerification(
                                    suggestion.getId(), verificationRequestId, errorMessage);
                        });
                    }
                });
        pendingVerification.setRequest(requestHandle);
    }

    private void cancelPending(String suggestionId, String message) {
        PendingVerification existing = pending.remove(suggestionId);
        if (existing != null) {
            existing.cancel(message);
        }
    }

    private boolean finishPending(
            String suggestionId, PendingVerification expected) {
        if (pending.get(suggestionId) != expected) {
            return false;
        }
        pending.remove(suggestionId);
        expected.close();
        return true;
    }

    @Override
    public void dispose() {
        for (PendingVerification verification : new ArrayList<>(pending.values())) {
            verification.cancel("项目已关闭。");
        }
        pending.clear();
    }

    private final class PendingVerification implements DocumentListener {
        private final Document document;
        private final ReviewSession session;
        private final String suggestionId;
        private final String requestId;
        private CancellableRequest request;
        private boolean closed;
        /**
         * 目标方法的活体范围（与 {@link ExpiryWatcher} 同思路）。
         * 验证期间只有改到这个方法范围内才算"代码又变了"，改别处不误判（P1-4）。
         * 选中模式（范围 -1）时为 null，退化为"任意改动都取消"。
         */
        private RangeMarker methodRange;

        private PendingVerification(Document document,
                                    ReviewSession session,
                                    String suggestionId,
                                    String requestId,
                                    ReviewContext currentContext) {
            this.document = document;
            this.session = session;
            this.suggestionId = suggestionId;
            this.requestId = requestId;
            if (currentContext != null
                    && currentContext.getMethodStart() >= 0
                    && currentContext.getMethodEnd() > currentContext.getMethodStart()) {
                methodRange = document.createRangeMarker(
                        currentContext.getMethodStart(), currentContext.getMethodEnd(), true);
            }
        }

        private void attach() {
            document.addDocumentListener(this);
        }

        private void setRequest(CancellableRequest request) {
            this.request = request;
            if (closed) {
                request.cancel();
            }
        }

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            // 只在改动触及目标方法范围时才取消验证；改别处不打扰（P1-4）。
            if (affectsMethodRange(event)) {
                cancel("验证期间代码再次变化，请停止输入后重试。");
            }
        }

        /** 改动是否触及目标方法范围。无 marker（选中模式）时保守认为触及。 */
        private boolean affectsMethodRange(DocumentEvent event) {
            if (methodRange == null || !methodRange.isValid()) {
                return true;
            }
            int start = methodRange.getStartOffset();
            int end = methodRange.getEndOffset();
            int offset = event.getOffset();
            if (event.getOldLength() == 0) {
                return offset >= start && offset <= end;
            }
            return offset < end && offset + event.getOldLength() > start;
        }

        private void cancel(String message) {
            if (closed) return;
            pending.remove(suggestionId, this);
            close();
            if (request != null) {
                request.cancel();
            }
            session.failVerification(suggestionId, requestId, message);
        }

        private void close() {
            if (closed) return;
            closed = true;
            document.removeDocumentListener(this);
            if (methodRange != null) {
                methodRange.dispose();
                methodRange = null;
            }
        }
    }
}
