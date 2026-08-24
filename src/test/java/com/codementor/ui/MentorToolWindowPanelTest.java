package com.codementor.ui;

import com.codementor.review.ReviewContext;
import com.codementor.review.ReviewSession;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.ui.AsyncProcessIcon;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MentorToolWindowPanelTest extends LightPlatformTestCase {

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
