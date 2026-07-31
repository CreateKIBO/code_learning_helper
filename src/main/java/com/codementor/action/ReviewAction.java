package com.codementor.action;

import com.codementor.review.ReviewMode;

/** Alt+M / 编辑器右键菜单的手动评审入口。 */
public final class ReviewAction extends AbstractMentorAction {

    public ReviewAction() {
        super(ReviewMode.REVIEW);
    }
}
