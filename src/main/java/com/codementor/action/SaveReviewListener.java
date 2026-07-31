package com.codementor.action;

import com.codementor.review.ReviewCoordinator;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 默认关闭的保存触发器；防抖后只评审最后实际编辑、且指纹尚未缓存的方法。 */
public final class SaveReviewListener implements FileDocumentManagerListener {

    private static final long DEBOUNCE_MILLIS = 750;
    private final Map<Document, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        CodeMentorSettings settings = CodeMentorSettings.getInstance();
        if (!settings.isReviewEnabled()
                || !settings.isSaveReviewEnabled()
                || !settings.hasConsentedToSend()) {
            return;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !"java".equalsIgnoreCase(file.getExtension())) {
            return;
        }

        ScheduledFuture<?> old = pending.remove(document);
        if (old != null) {
            old.cancel(false);
        }

        long expectedStamp = document.getModificationStamp();
        int modifiedOffset =
                LastEditTracker.consumeLastEditOffset(document, expectedStamp);
        if (modifiedOffset < 0) {
            return;
        }
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        ScheduledFuture<?> future = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> {
                    pending.remove(document, self.get());
                    if (document.getModificationStamp() != expectedStamp) {
                        return;
                    }
                    ApplicationManager.getApplication().invokeLater(
                            () -> triggerForOpenProject(document, modifiedOffset));
                },
                DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS);
        self.set(future);
        pending.put(document, future);
    }

    private static void triggerForOpenProject(Document document, int modifiedOffset) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null || editor.getDocument() != document) continue;
            PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
            documentManager.commitDocument(document);
            PsiFile psiFile = documentManager.getPsiFile(document);
            if (psiFile == null) return;
            ReviewCoordinator.getInstance(project)
                    .review(
                            editor,
                            psiFile,
                            ReviewCoordinator.Trigger.SAVE,
                            modifiedOffset);
            return;
        }
    }
}
