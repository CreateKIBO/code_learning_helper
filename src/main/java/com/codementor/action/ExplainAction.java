package com.codementor.action;

import com.codementor.review.ReviewMode;

/** Alt+Shift+M / 右键菜单的代码解释入口。 */
public final class ExplainAction extends AbstractMentorAction {

    public ExplainAction() {
        super(ReviewMode.EXPLAIN);
    }
}
