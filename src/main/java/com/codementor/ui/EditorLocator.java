package com.codementor.ui;

import com.codementor.context.EvidenceLocator;
import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewSession;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

/** 把编辑器光标移到导师引用的代码片段上，方便对照阅读。 */
final class EditorLocator {

    private EditorLocator() {}

    /**
     * 只移动光标并滚动到可见位置，不改动选区：选区决定下一次请求的分析目标，
     * 定位代码不应该悄悄改变它。
     */
    static void reveal(Project project, String snippet) {
        if (project.isDisposed()) return;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.isDisposed()) {
            Messages.showInfoMessage(project, "请先在编辑器里打开被讲解的文件。", "CodeMentor");
            return;
        }
        ReviewContext context = ReviewSession.getInstance(project).getContext();
        int preferredStart = context == null ? 0 : Math.max(0, context.getMethodStart());
        EvidenceLocator.Range range = EvidenceLocator.locate(
                editor.getDocument().getCharsSequence(), snippet, preferredStart);
        if (range == null) {
            Messages.showInfoMessage(
                    project,
                    "当前编辑器里找不到这段代码：它可能已被修改，或者当前打开的不是被讲解的文件。",
                    "CodeMentor");
            return;
        }
        editor.getCaretModel().moveToOffset(range.start());
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }
}
