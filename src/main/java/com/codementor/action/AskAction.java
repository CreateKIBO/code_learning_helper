package com.codementor.action;

import com.codementor.review.ReviewMode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;

/**
 * 带着问题问导师：对选中的代码或当前方法提一个具体问题。
 *
 * <p>复用解释模式的整条管线；问题与“下一步编码指导”的学习目标走同一个数据块，
 * 因此同样会脱敏、出现在发送预览里，并参与缓存隔离。
 */
public final class AskAction extends AbstractMentorAction {

    private static final int MAX_QUESTION_CHARS = 1_000;

    public AskAction() {
        super(ReviewMode.EXPLAIN);
    }

    @Override
    protected String requestLearningGoal(Project project) {
        return Messages.showInputDialog(project,
                "关于选中的代码或当前方法，你想弄明白什么？（最多 " + MAX_QUESTION_CHARS + " 字）\n"
                        + "例如：这里为什么用 Optional 而不是直接返回 null？\n"
                        + "留空则按普通“解释代码”处理。问题会脱敏后随上下文发送。",
                "CodeMentor 带着问题问导师", Messages.getQuestionIcon(), "",
                new InputValidator() {
                    @Override
                    public boolean checkInput(String input) {
                        return input.length() <= MAX_QUESTION_CHARS;
                    }

                    @Override
                    public boolean canClose(String input) {
                        return checkInput(input);
                    }
                });
    }
}
