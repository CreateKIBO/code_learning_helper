package com.codementor.action;

import com.codementor.review.ReviewMode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;

/** 根据当前方法或选区及有限项目上下文提供渐进式编码指导。 */
public final class NextStepAction extends AbstractMentorAction {

    public NextStepAction() {
        super(ReviewMode.NEXT_STEP);
    }

    @Override
    protected String requestLearningGoal(Project project) {
        return Messages.showInputDialog(project,
                "你接下来想实现什么？（可留空，最多 1000 字）\n"
                        + "例如：补完当前方法的参数校验。目标会脱敏后随上下文发送。",
                "CodeMentor 下一步编码指导", Messages.getQuestionIcon(), "",
                new InputValidator() {
                    @Override
                    public boolean checkInput(String input) {
                        return input.length() <= 1_000;
                    }

                    @Override
                    public boolean canClose(String input) {
                        return checkInput(input);
                    }
                });
    }
}
