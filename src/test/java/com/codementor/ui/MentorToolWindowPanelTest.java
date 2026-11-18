package com.codementor.ui;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewSession;
import com.codementor.review.ReviewMode;
import com.codementor.review.ReviewResult;
import com.codementor.review.Suggestion;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.ui.AsyncProcessIcon;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import com.intellij.ui.components.JBLabel;

public class MentorToolWindowPanelTest extends LightPlatformTestCase {

    public void testNextStepShowsTaskWithoutErrorCertaintyOrModificationVerification() throws Throwable {
        EdtTestUtil.runInEdtAndWait(() -> {
            ReviewSession session = ReviewSession.getInstance(getProject());
            session.reset();
            MentorToolWindowPanel panel = new MentorToolWindowPanel(getProject());
            try {
                assertTrue(descendants(panel, JButton.class).stream()
                        .anyMatch(button -> "下一步怎么写".equals(button.getText())));
                session.startRequest(context().withReviewMode(ReviewMode.NEXT_STEP), "next");
                session.succeed("next", new ReviewResult("先明确输入输出", List.of(
                        new Suggestion.Builder().id("S1").category(Suggestion.Category.JAVA_USAGE)
                                .certainty(Suggestion.Certainty.MEDIUM).title("写出输入输出")
                                .evidence("void run() {}").hint("预期是什么？")
                                .nextStep("写一行契约注释").verificationCriteria("能说明边界行为")
                                .selfCheckQuestion("空输入怎么办？").build())));
                assertTrue(descendants(panel, JBLabel.class).stream()
                        .anyMatch(label -> label.getText() != null && label.getText().startsWith("编码任务 · ")));
                assertTrue(descendants(panel, JButton.class).stream()
                        .anyMatch(button -> "继续编码指导".equals(button.getText())));
                assertFalse(descendants(panel, JButton.class).stream()
                        .anyMatch(button -> "我改好了，验证".equals(button.getText())));
            } finally {
                panel.dispose();
                session.reset();
            }
        });
    }

    public void testCompactActivityIndicatorFollowsSessionLifecycle() throws Throwable {
        ReviewSession session = ReviewSession.getInstance(getProject());
        AtomicReference<MentorToolWindowPanel> panelRef = new AtomicReference<>();
        EdtTestUtil.runInEdtAndWait(() -> {
            session.reset();
            panelRef.set(new MentorToolWindowPanel(getProject()));
        });
        MentorToolWindowPanel panel = panelRef.get();
        AsyncProcessIcon indicator = descendants(panel, AsyncProcessIcon.class).get(0);
        try {
            assertFalse(indicator.isRunning());
            assertTrue("indicator should stay compact",
                    indicator.getPreferredSize().width <= 24
                            && indicator.getPreferredSize().height <= 24);

            EdtTestUtil.runInEdtAndWait(() ->
                    session.startRequest(context(), "review"));
            // 无头测试组件未挂到可显示窗口，IntelliJ 的 Animator 会自动暂停；
            // 工具提示仍能验证面板已切换到忙碌显示契约。
            assertEquals(
                    "CodeMentor 正在处理导师请求或本地学习数据",
                    indicator.getToolTipText());

            EdtTestUtil.runInEdtAndWait(() ->
                    session.fail("review", "failed"));
            assertEquals(
                    "CodeMentor 当前没有后台任务",
                    indicator.getToolTipText());
        } finally {
            EdtTestUtil.runInEdtAndWait(panel::dispose);
        }
        assertTrue(indicator.isDisposed());
    }

    private static ReviewContext context() {
        return new ReviewContext(
                "src/Demo.java",
                "Demo#run",
                "void run() {}",
                "class Demo { void run() {} }",
                false,
                1L,
                "fingerprint",
                0,
                13);
    }

    private static <T extends Component> List<T> descendants(
            Component root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        collectDescendants(root, type, matches);
        return matches;
    }

    private static <T extends Component> void collectDescendants(
            Component component, Class<T> type, List<T> matches) {
        if (type.isInstance(component)) matches.add(type.cast(component));
        if (!(component instanceof Container container)) return;
        for (Component child : container.getComponents()) {
            collectDescendants(child, type, matches);
        }
    }
}
