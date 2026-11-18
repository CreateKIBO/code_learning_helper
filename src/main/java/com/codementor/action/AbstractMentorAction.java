package com.codementor.action;

import com.codementor.review.ReviewCoordinator;
import com.codementor.review.ReviewMode;
import com.codementor.ui.MentorToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** 评审与解释动作共享的编辑器入口逻辑。 */
abstract class AbstractMentorAction extends AnAction implements DumbAware {

    private final ReviewMode mode;

    protected AbstractMentorAction(ReviewMode mode) {
        this.mode = mode;
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || psiFile == null) {
            return;
        }

        String learningGoal = requestLearningGoal(project);
        if (learningGoal == null) return;

        ensureToolWindowVisible(project);
        ReviewCoordinator.getInstance(project)
                .review(editor, psiFile, ReviewCoordinator.Trigger.MANUAL, mode, learningGoal);
    }

    /** null 表示用户取消；空串表示不附加本次学习目标。 */
    protected String requestLearningGoal(Project project) {
        return "";
    }

    @Override
    public final void update(@NotNull AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = editor != null
                && file != null
                && "java".equalsIgnoreCase(file.getExtension());
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static void ensureToolWindowVisible(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(MentorToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow != null && toolWindow.isAvailable()) {
            toolWindow.activate(null);
        }
    }
}
